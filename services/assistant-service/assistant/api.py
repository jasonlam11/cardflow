"""
assistant-service HTTP API.

POST /chat    {cardId, message} -> grounded answer with citations, or "I don't know"
GET  /health  liveness + DB
GET  /info    provider/model in use and whether this is demo mode (no LLM key)
"""

import logging
import re
import uuid
from contextlib import asynccontextmanager
from typing import Annotated

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse, Response
from prometheus_client import CONTENT_TYPE_LATEST, Counter, Histogram, generate_latest
from pydantic import BaseModel, Field, StringConstraints

from . import logging_config
from .chat import Assistant
from .embeddings import FastEmbedder
from .knowledge import load_chunks
from .ledger import HttpLedger
from .llm.base import LLMUnavailable
from .retrieval import Retriever
from .settings import Settings
from .store import PgVectorStore

log = logging.getLogger("assistant.api")

CHATS = Counter("assistant_chats_total", "Chat requests by outcome", ["outcome"])  # answered | refused | guardrail_<reason>
CHAT_SECONDS = Histogram("assistant_chat_seconds", "End-to-end chat latency",
                         buckets=(0.05, 0.1, 0.25, 0.5, 1, 2, 5, 10, 30))
ROUNDS = Histogram("assistant_llm_rounds", "Model rounds per chat", buckets=(1, 2, 3, 4, 5))
TOKENS = Counter("assistant_llm_tokens_total", "LLM tokens", ["direction"])
COST = Counter("assistant_llm_cost_usd_total", "Estimated LLM spend in USD")
UNAVAILABLE = Counter("assistant_llm_unavailable_total", "Chats that failed because the LLM was unavailable")
SAFE_ID = re.compile(r"^[A-Za-z0-9-]{1,64}$")


def make_llm(settings: Settings):
    from .llm.anthropic_client import AnthropicLLM, has_credentials
    from .llm.fake import DemoLLM

    provider = settings.provider
    if provider == "auto":
        provider = "anthropic" if has_credentials() else "demo"
    if provider == "anthropic":
        return AnthropicLLM(model=settings.model)
    return DemoLLM()


class ChatRequest(BaseModel):
    card_id: Annotated[str, StringConstraints(pattern=r"^[0-9a-fA-F-]{36}$")] = Field(alias="cardId")
    message: Annotated[str, StringConstraints(min_length=1, max_length=2000, strip_whitespace=True)]


def create_app(assistant: Assistant | None = None, app_info: dict | None = None) -> FastAPI:
    @asynccontextmanager
    async def lifespan(app: FastAPI):
        logging_config.configure()
        app.state.store = None
        if assistant is None:
            settings = Settings()
            store = PgVectorStore(settings.dsn)
            retriever = Retriever(store, FastEmbedder(), k=settings.top_k)
            chunks = load_chunks(settings.docs_dir)
            retriever.index(chunks)
            retriever.retrieve("warm-up")  # first query loads the ONNX session; don't make a user wait for it
            llm = make_llm(settings)
            app.state.assistant = Assistant(llm, retriever, HttpLedger(settings.ledger_url), settings.max_rounds)
            app.state.store = store
            app.state.info = {"provider": llm.name, "model": llm.model, "demoMode": llm.name == "demo",
                              "documents": len({c.doc for c in chunks}), "sections": len(chunks)}
        else:
            app.state.assistant = assistant
            app.state.info = app_info or {"provider": assistant.llm.name, "model": assistant.llm.model,
                                      "demoMode": assistant.llm.name == "demo"}
        log.info("assistant-service started: %s", app.state.info)
        yield
        if app.state.store is not None:
            app.state.store.close()

    app = FastAPI(title="CardFlow assistant-service", version="1.0.0", lifespan=lifespan)

    @app.middleware("http")
    async def correlation(request: Request, call_next):
        incoming = request.headers.get("X-Correlation-Id", "")
        cid = incoming if SAFE_ID.match(incoming) else str(uuid.uuid4())
        token = logging_config.correlation_id.set(cid)
        try:
            response = await call_next(request)
        finally:
            logging_config.correlation_id.reset(token)
        response.headers["X-Correlation-Id"] = cid
        return response

    @app.exception_handler(RequestValidationError)
    async def validation_error(request: Request, exc: RequestValidationError):
        errors = {".".join(str(p) for p in e["loc"][1:]) or "body": e["msg"] for e in exc.errors()}
        return JSONResponse(status_code=422, media_type="application/problem+json",
                            content={"title": "Validation failed", "status": 422,
                                     "detail": "Request has invalid fields", "errors": errors})

    @app.exception_handler(LLMUnavailable)
    async def llm_unavailable(request: Request, exc: LLMUnavailable):
        log.warning("LLM unavailable: %s", exc)
        UNAVAILABLE.inc()
        return JSONResponse(status_code=503, media_type="application/problem+json",
                            content={"title": "Assistant unavailable", "status": 503,
                                     "detail": "The assistant is temporarily unavailable. Please try again shortly."})

    @app.exception_handler(Exception)
    async def unexpected(request: Request, exc: Exception):
        log.exception("Unhandled error")
        return JSONResponse(status_code=500, media_type="application/problem+json",
                            content={"title": "Internal error", "status": 500, "detail": "An unexpected error occurred"})

    @app.post("/chat")
    def chat(req: ChatRequest, request: Request):
        r = request.app.state.assistant.answer(req.card_id, req.message)
        CHATS.labels("answered" if not r.refused else f"guardrail_{r.guardrail}" if r.guardrail else "refused").inc()
        CHAT_SECONDS.observe(r.latency_ms / 1000)
        ROUNDS.observe(r.rounds)
        TOKENS.labels("input").inc(r.usage.input_tokens)
        TOKENS.labels("output").inc(r.usage.output_tokens)
        COST.inc(r.usage.cost_usd)
        return {"answer": r.answer, "citations": r.citations, "toolsUsed": r.tools_used, "refused": r.refused,
                "guardrail": r.guardrail, "model": r.model, "demoMode": request.app.state.info.get("demoMode", False),
                "usage": {"inputTokens": r.usage.input_tokens, "outputTokens": r.usage.output_tokens,
                          "costUsd": round(r.usage.cost_usd, 6)},
                "latencyMs": r.latency_ms}

    @app.get("/metrics", include_in_schema=False)
    def metrics():
        return Response(generate_latest(), media_type=CONTENT_TYPE_LATEST)

    @app.get("/health")
    def health(request: Request):
        store = request.app.state.store
        ok = store.healthy() if store is not None else True
        return JSONResponse(status_code=200 if ok else 503, content={"status": "UP" if ok else "DOWN"})

    @app.get("/info")
    def info(request: Request):
        return request.app.state.info

    return app
