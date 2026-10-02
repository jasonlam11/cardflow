"""
fraud-service HTTP API.

POST /score   features from the card's history + this charge → score, band, top reasons
GET  /health  liveness + database check
GET  /model   model version, thresholds and headline metrics
"""

import logging
import re
import time
import uuid
from contextlib import asynccontextmanager
from datetime import datetime, timedelta, timezone
from enum import Enum
from typing import Annotated

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse, Response
from prometheus_client import CONTENT_TYPE_LATEST, Counter, Histogram, generate_latest
from pydantic import BaseModel, Field, StringConstraints

from . import logging_config
from .features import Txn, compute_features
from .model import FraudModel
from .reasons import top_reasons
from .settings import Settings
from .store import HistoryStore, PostgresHistoryStore

log = logging.getLogger("fraud.api")

# Module level: one set of metrics per process, however many app instances tests create
SCORE_SECONDS = Histogram("fraud_score_seconds", "Time to score one transaction (features + model + SHAP + history)",
                          buckets=(0.001, 0.0025, 0.005, 0.01, 0.025, 0.05, 0.1, 0.25))
SCORES = Counter("fraud_scores_total", "Scored transactions by band", ["band"])
SAFE_ID = re.compile(r"^[A-Za-z0-9-]{1,64}$")
MAX_CLOCK_SKEW = timedelta(minutes=5)


class Channel(str, Enum):
    CARD_PRESENT = "CARD_PRESENT"
    ECOMMERCE = "ECOMMERCE"


class Location(BaseModel):
    lat: float = Field(ge=-90, le=90)
    lon: float = Field(ge=-180, le=180)
    country: Annotated[str, StringConstraints(pattern=r"^[A-Z]{2}$")] | None = None


class ScoreRequest(BaseModel):
    request_id: Annotated[str, StringConstraints(pattern=r"^[A-Za-z0-9_-]{1,64}$")] = Field(alias="requestId")
    card_id: Annotated[str, StringConstraints(min_length=1, max_length=64)] = Field(alias="cardId")
    amount_minor: int = Field(alias="amountMinor", gt=0, le=1_000_000_000_000)
    currency: Annotated[str, StringConstraints(pattern=r"^[A-Z]{3}$")]
    mcc: Annotated[str, StringConstraints(pattern=r"^[0-9]{4}$")]
    merchant_id: Annotated[str, StringConstraints(min_length=1, max_length=64)] = Field(alias="merchantId")
    channel: Channel = Channel.CARD_PRESENT
    merchant_location: Location | None = Field(default=None, alias="merchantLocation")
    occurred_at: datetime | None = Field(default=None, alias="occurredAt")


class ReasonOut(BaseModel):
    code: str
    description: str
    contribution: float


class ScoreResponse(BaseModel):
    requestId: str
    score: float
    band: str
    reasons: list[ReasonOut]
    modelVersion: str


def _warm_up(model: FraudModel, store: HistoryStore) -> None:
    """Pay first-call costs (model, numpy, DB connection) before taking traffic. Measured: cold
    workers answered their first requests past the 300 ms client timeout, which opened the
    circuit breaker in authorization-service right after every restart."""
    now = datetime.now(timezone.utc)
    sample = Txn(ts=now, amount_minor=2_500, mcc="5411", merchant_id="warmup", channel="CARD_PRESENT", lat=40.7, lon=-74.0)
    for _ in range(5):
        model.predict(compute_features([], sample))
    store.recent("warmup", now)


def create_app(model: FraudModel | None = None, store: HistoryStore | None = None) -> FastAPI:
    """Factory so tests can inject a model and an in-memory store."""

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        logging_config.configure()
        settings = Settings()
        app.state.model = model or FraudModel.load(settings.model_dir)
        app.state.store = store or PostgresHistoryStore(settings.dsn)
        _warm_up(app.state.model, app.state.store)
        log.info("fraud-service started with model %s", app.state.model.version)
        yield
        if hasattr(app.state.store, "close"):
            app.state.store.close()

    app = FastAPI(title="CardFlow fraud-service", version="1.0.0", lifespan=lifespan)

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
        # Same RFC 9457 shape as the Java services; no request body echoed back
        errors = {".".join(str(p) for p in e["loc"][1:]) or "body": e["msg"] for e in exc.errors()}
        return JSONResponse(status_code=422, media_type="application/problem+json",
                            content={"title": "Validation failed", "status": 422,
                                     "detail": "Request has invalid fields", "errors": errors})

    @app.exception_handler(Exception)
    async def unexpected(request: Request, exc: Exception):
        log.exception("Unhandled error")
        return JSONResponse(status_code=500, media_type="application/problem+json",
                            content={"title": "Internal error", "status": 500,
                                     "detail": "An unexpected error occurred"})

    @app.post("/score", response_model=ScoreResponse)
    def score(req: ScoreRequest, request: Request) -> ScoreResponse:
        started = time.perf_counter()
        now = datetime.now(timezone.utc)
        occurred = req.occurred_at or now
        if occurred.tzinfo is None:
            occurred = occurred.replace(tzinfo=timezone.utc)
        if occurred > now + MAX_CLOCK_SKEW:
            raise RequestValidationError([{"loc": ("body", "occurredAt"), "msg": "must not be in the future"}])

        loc = req.merchant_location
        txn = Txn(ts=occurred, amount_minor=req.amount_minor, mcc=req.mcc, merchant_id=req.merchant_id,
                  channel=req.channel.value, lat=loc.lat if loc else None, lon=loc.lon if loc else None)
        model: FraudModel = request.app.state.model
        store: HistoryStore = request.app.state.store

        features = compute_features(store.recent(req.card_id, occurred), txn)
        prediction = model.predict(features)
        store.record(req.request_id, req.card_id, txn, prediction.score, prediction.band, model.version)
        reasons = top_reasons(prediction.contributions)
        SCORE_SECONDS.observe(time.perf_counter() - started)
        SCORES.labels(prediction.band).inc()

        # Log the decision, never the raw request (no card or location data in logs)
        log.info("scored request=%s band=%s score=%.4f reasons=%s in %.1fms", req.request_id, prediction.band,
                 prediction.score, [r["code"] for r in reasons], (time.perf_counter() - started) * 1000)
        return ScoreResponse(requestId=req.request_id, score=round(prediction.score, 6), band=prediction.band,
                             reasons=[ReasonOut(**r) for r in reasons], modelVersion=model.version)

    @app.get("/metrics", include_in_schema=False)
    def metrics():
        return Response(generate_latest(), media_type=CONTENT_TYPE_LATEST)

    @app.get("/health")
    def health(request: Request):
        db_ok = request.app.state.store.healthy()
        body = {"status": "UP" if db_ok else "DOWN", "database": "UP" if db_ok else "DOWN",
                "modelVersion": request.app.state.model.version}
        return JSONResponse(status_code=200 if db_ok else 503, content=body)

    @app.get("/model")
    def model_info(request: Request):
        meta = request.app.state.model.metadata
        m = meta["metrics"]
        return {"modelVersion": meta["model_version"], "trainedAt": meta["trained_at"],
                "thresholds": meta["thresholds"], "features": meta["features"],
                "testMetrics": {"prAuc": m["pr_auc"], "rocAuc": m["roc_auc"],
                                "flagged": m["flagged_review_or_high"], "autoDeclined": m["auto_declined_high"]}}

    return app


app = create_app()
