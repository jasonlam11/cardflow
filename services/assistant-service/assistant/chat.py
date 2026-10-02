"""One chat request, end to end: redact -> retrieve -> tool loop -> guardrails -> answer."""

import logging
import time
from dataclasses import dataclass, field
from datetime import date

from . import guardrails
from .ledger import LedgerPort
from .llm.base import AssistantTurn, LLMClient, ToolResult, ToolResultsTurn, Turn, Usage, UserTurn
from .prompts import SYSTEM_PROMPT, user_message
from .retrieval import Retriever
from .tools import TOOLS, ToolError, ToolExecutor

log = logging.getLogger("assistant.chat")

UNVERIFIED = ("I don't know. I couldn't verify an answer from your card's benefit documents or your account data, "
              "so I'd rather not guess.")
TOO_MANY_STEPS = "I don't know. That question needed more steps than I'm allowed to take; try asking something narrower."


@dataclass
class ChatResult:
    answer: str
    citations: list[dict]
    tools_used: list[str]
    refused: bool
    guardrail: str | None  # why the model's answer was replaced, if it was
    usage: Usage
    model: str
    rounds: int
    latency_ms: int
    retrieved: list[str] = field(default_factory=list)  # chunk ids, for evals (recall@k)


class Assistant:
    def __init__(self, llm: LLMClient, retriever: Retriever, ledger: LedgerPort, max_rounds: int = 5):
        self.llm = llm
        self.retriever = retriever
        self.ledger = ledger
        self.max_rounds = max_rounds

    def answer(self, card_id: str, message: str, today: date | None = None) -> ChatResult:
        started = time.perf_counter()
        question = guardrails.redact(message)  # personal data never reaches the LLM
        hits = self.retriever.retrieve(question)
        doc_ids = {h.chunk.id for h in hits}
        executor = ToolExecutor(card_id, self.ledger)
        sources_text = [h.chunk.text for h in hits]

        turns: list[Turn] = [UserTurn(user_message(
            question, [(h.chunk.id, f"{h.chunk.title}: {h.chunk.section}", h.chunk.text) for h in hits],
            (today or date.today()).isoformat()))]
        usage = Usage()
        answer, refused, guardrail = TOO_MANY_STEPS, True, "max_rounds"
        rounds = 0

        for rounds in range(1, self.max_rounds + 1):
            response = self.llm.respond(SYSTEM_PROMPT, turns, TOOLS)
            usage.add(response.usage)
            if response.stop_reason == "refusal":
                answer, refused, guardrail = UNVERIFIED, True, "model_refusal"
                break
            if not response.tool_calls:
                answer, refused, guardrail = self._check(response.text, doc_ids, set(executor.calls), sources_text)
                break
            turns.append(AssistantTurn(response.text, response.tool_calls, response.provider_content))
            results = []
            for call in response.tool_calls:
                try:
                    output, is_error = executor.run(call.name, call.input), False
                except ToolError as e:
                    output, is_error = f"Error: {e}", True
                output = guardrails.redact(output)  # tool data is redacted too
                sources_text.append(output)
                results.append(ToolResult(call.id, output, is_error))
            turns.append(ToolResultsTurn(results))  # all results for this turn in one message

        citations = [] if refused else [self._describe(c, hits) for c in guardrails.parse_citations(answer)]
        result = ChatResult(answer, citations, list(dict.fromkeys(executor.calls)), refused, guardrail, usage,
                            getattr(self.llm, "model", ""), rounds, int((time.perf_counter() - started) * 1000),
                            [h.chunk.id for h in hits])
        # Metadata only: never the question, answer or tool data
        log.info("chat rounds=%d tools=%s refused=%s guardrail=%s tokens_in=%d tokens_out=%d cost_usd=%.5f ms=%d",
                 rounds, result.tools_used, refused, guardrail, usage.input_tokens, usage.output_tokens,
                 usage.cost_usd, result.latency_ms)
        return result

    @staticmethod
    def _check(text: str, doc_ids: set[str], tools_called: set[str], sources: list[str]):
        """Returns (answer, refused, guardrail). Replaces answers that aren't grounded."""
        text = text.strip()
        if not text:
            return UNVERIFIED, True, "empty"
        if guardrails.is_refusal(text):
            return text, True, None
        citations = guardrails.parse_citations(text)
        if not citations:
            return UNVERIFIED, True, "uncited"
        if guardrails.invalid_citations(citations, doc_ids, tools_called):
            return UNVERIFIED, True, "invalid_citation"
        if guardrails.ungrounded_amounts(text, sources):
            return UNVERIFIED, True, "ungrounded_amount"
        return text, False, None

    @staticmethod
    def _describe(c: guardrails.Citation, hits) -> dict:
        if c.kind == "doc":
            hit = next((h for h in hits if h.chunk.id == c.ref), None)
            return {"type": "doc", "id": c.ref, "title": f"{hit.chunk.title}: {hit.chunk.section}" if hit else c.ref}
        return {"type": "tool", "id": c.ref, "title": c.ref.replace("_", " ")}
