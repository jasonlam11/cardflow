"""
LLMs that don't call any API:

- DemoLLM: a deterministic stand-in used when no API key is configured and in CI.
  It follows the same protocol as a real model (calls tools, cites sources, says
  "I don't know") using simple rules, so the whole pipeline can run for free.
  It is NOT a measure of model quality.
- ScriptedLLM: returns pre-written responses, to test guardrails against a
  model that misbehaves (uncited answers, made-up numbers, bad citations).
"""

import json
import re
from datetime import date, timedelta
from itertools import count

from .base import AssistantTurn, LLMResponse, ToolCall, ToolResultsTurn, Turn, Usage, UserTurn

_ids = count(1)
_SOURCE = re.compile(r'<source id="doc:([^"]+)" title="([^"]+)">\n(.*?)\n</source>', re.S)
_TODAY = re.compile(r"Today is (\d{4}-\d{2}-\d{2})")
_QUESTION = re.compile(r"<question>\n(.*?)\n</question>", re.S)


class DemoLLM:
    name = "demo"
    model = "demo-rules"

    def respond(self, system: str, turns: list[Turn], tools: list[dict]) -> LLMResponse:
        user = next(t for t in turns if isinstance(t, UserTurn)).text
        question = (_QUESTION.search(user).group(1) if _QUESTION.search(user) else user).lower()
        today = date.fromisoformat(_TODAY.search(user).group(1)) if _TODAY.search(user) else date.today()
        last = turns[-1]

        if isinstance(last, ToolResultsTurn):
            return self._answer_from_tools(turns, last)

        if any(w in question for w in ("ignore", "system prompt", "instructions", "transfer", "send money", "pay my")):
            return _text("I can't help with that. I can only answer questions about your card benefits and your own spending.")
        tool = _pick_tool(question)
        if tool:
            call = ToolCall(f"demo_{next(_ids)}", tool, _tool_args(tool, question, today))
            return LLMResponse("", [call], "tool_use", Usage(), [call])

        sources = _SOURCE.findall(user)
        if not sources:
            return _text("I don't know. I couldn't find that in your card's benefit documents or account data.")
        doc_id, _title, body = sources[0]
        sentences = re.split(r"(?<=[.!?])\s+", " ".join(body.split()))
        return _text(" ".join(sentences[:2]) + f" [doc:{doc_id}]")

    @staticmethod
    def _answer_from_tools(turns: list[Turn], results: ToolResultsTurn) -> LLMResponse:
        call = next(t for t in reversed(turns) if isinstance(t, AssistantTurn)).tool_calls[0]
        data = json.loads(results.results[0].content)
        if "error" in data:
            return _text("I don't know right now: I couldn't reach your account data.")
        cite = f"[tool:{call.name}]"
        if call.name == "get_balance":
            return _text(f"Your current balance is {data['balance']}. {cite}")
        if call.name == "spending_by_category":
            parts = ", ".join(f"{c['category']} {c['total']}" for c in data["categories"][:4]) or "no charges"
            return _text(f"From {data['start_date']} to {data['end_date']} you spent {data['total']} in total: {parts}. {cite}")
        txns = data["transactions"]
        if not txns:
            return _text(f"I found no charges in that period. {cite}")
        listed = "; ".join(f"{t['date']} {t['merchant']} {t['amount']}" for t in txns[:5])
        return _text(f"Here are your charges: {listed}. {cite}")


class ScriptedLLM:
    """Returns the given responses in order (each either a str answer or a list of ToolCall)."""

    name = "scripted"
    model = "scripted"

    def __init__(self, *responses):
        self.responses = list(responses)
        self.seen: list[list[Turn]] = []

    def respond(self, system, turns, tools):
        self.seen.append(list(turns))
        nxt = self.responses.pop(0)
        if isinstance(nxt, list):
            return LLMResponse("", nxt, "tool_use", Usage(input_tokens=100, output_tokens=10), nxt)
        if nxt == "<refusal>":
            return LLMResponse("", [], "refusal", Usage())
        return _text(nxt)


def _text(s: str) -> LLMResponse:
    return LLMResponse(s, [], "end_turn", Usage(), s)


def _pick_tool(q: str) -> str | None:
    if re.search(r"\b(balance|owe)\b", q):
        return "get_balance"
    if re.search(r"\b(largest|biggest|most expensive)\b", q):
        return "largest_transactions"
    if re.search(r"\b(spend|spent|spending)\b", q):
        return "spending_by_category"
    if re.search(r"\b(recent|latest|last \d+ (charges|transactions|purchases))\b", q):
        return "recent_transactions"
    return None


def _tool_args(tool: str, q: str, today: date) -> dict:
    if tool == "get_balance":
        return {}
    if tool == "recent_transactions":
        return {"limit": 5}
    if "last month" in q:
        end = today.replace(day=1) - timedelta(days=1)
        start = end.replace(day=1)
    elif "this month" in q:
        start, end = today.replace(day=1), today
    else:
        start, end = today - timedelta(days=30), today
    args = {"start_date": start.isoformat(), "end_date": end.isoformat()}
    if tool == "largest_transactions":
        args["limit"] = 3
    return args
