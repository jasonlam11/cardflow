"""
The assistant's tools: read-only views of the CURRENT cardholder's money.

The card id is bound when the executor is created (from the request, not the
model), so no prompt can make a tool read another card. There is no tool that
moves money.
"""

import json
from dataclasses import dataclass, field
from datetime import date, timedelta

from .ledger import LedgerPort, LedgerUnavailable

MAX_RANGE_DAYS = 366

MCC_CATEGORIES = {
    "5814": "Coffee and fast food", "5812": "Restaurants", "5411": "Groceries", "5541": "Fuel",
    "4121": "Rideshare", "4899": "Streaming", "5912": "Pharmacy", "5732": "Electronics", "4511": "Airlines",
    "7011": "Hotels", "5944": "Jewelry", "5999": "Gift cards", "7995": "Gambling", "4829": "Money transfers",
}

_DATE = {"type": "string", "description": "ISO date YYYY-MM-DD (UTC)", "pattern": r"^\d{4}-\d{2}-\d{2}$"}

TOOLS: list[dict] = [
    {
        "name": "get_balance",
        "description": "Current statement balance of the cardholder's card (what they owe), from the ledger.",
        "input_schema": {"type": "object", "properties": {}, "additionalProperties": False},
    },
    {
        "name": "spending_by_category",
        "description": "Total card spending per merchant category between two dates (inclusive), largest first, "
                       "plus the overall total. Use for questions like 'how much did I spend on dining in March'.",
        "input_schema": {
            "type": "object",
            "properties": {"start_date": _DATE, "end_date": _DATE},
            "required": ["start_date", "end_date"],
            "additionalProperties": False,
        },
    },
    {
        "name": "largest_transactions",
        "description": "The cardholder's largest charges between two dates (inclusive).",
        "input_schema": {
            "type": "object",
            "properties": {
                "start_date": _DATE, "end_date": _DATE,
                "limit": {"type": "integer", "minimum": 1, "maximum": 10},
            },
            "required": ["start_date", "end_date", "limit"],
            "additionalProperties": False,
        },
    },
    {
        "name": "recent_transactions",
        "description": "The cardholder's most recent charges, newest first.",
        "input_schema": {
            "type": "object",
            "properties": {"limit": {"type": "integer", "minimum": 1, "maximum": 20}},
            "required": ["limit"],
            "additionalProperties": False,
        },
    },
]

TOOL_NAMES = frozenset(t["name"] for t in TOOLS)


def dollars(minor: int) -> str:
    return f"${minor / 100:,.2f}"


class ToolError(Exception):
    """Bad tool input; reported back to the model as an error result, not raised to the user."""


@dataclass
class ToolExecutor:
    card_id: str
    ledger: LedgerPort
    calls: list[str] = field(default_factory=list)

    def run(self, name: str, args: dict) -> str:
        if name not in TOOL_NAMES:
            raise ToolError(f"unknown tool {name}")
        self.calls.append(name)
        try:
            result = getattr(self, f"_{name}")(args)
        except LedgerUnavailable:
            result = {"error": "ledger temporarily unavailable"}
        return json.dumps(result, sort_keys=True)

    def _get_balance(self, _args):
        b = self.ledger.balance(self.card_id)
        if b is None:
            return {"balance": dollars(0), "note": "no posted charges yet"}
        return {"balance": dollars(b["balanceMinor"]), "currency": b.get("currency", "USD")}

    def _spending_by_category(self, args):
        start, end = _range(args)
        rows = self.ledger.spending(self.card_id, start, end)
        total = sum(r["totalMinor"] for r in rows)
        return {
            "start_date": start.isoformat(), "end_date": end.isoformat(), "total": dollars(total),
            "categories": [{"category": MCC_CATEGORIES.get(r["mcc"], f"Other ({r['mcc']})"),
                            "total": dollars(r["totalMinor"]), "transactions": r["count"]} for r in rows],
        }

    def _largest_transactions(self, args):
        start, end = _range(args)
        rows = self.ledger.transactions(self.card_id, start, end, "AMOUNT", _limit(args, 10))
        return {"start_date": start.isoformat(), "end_date": end.isoformat(), "transactions": [_txn(r) for r in rows]}

    def _recent_transactions(self, args):
        rows = self.ledger.transactions(self.card_id, None, None, "RECENT", _limit(args, 20))
        return {"transactions": [_txn(r) for r in rows]}


def _txn(r: dict) -> dict:
    return {"date": r["occurredAt"][:10], "merchant": r.get("merchantName") or r.get("description", ""),
            "category": MCC_CATEGORIES.get(r.get("mcc") or "", "Other"), "amount": dollars(r["amountMinor"])}


def _range(args: dict) -> tuple[date, date]:
    try:
        start, end = date.fromisoformat(args["start_date"]), date.fromisoformat(args["end_date"])
    except (KeyError, ValueError) as e:
        raise ToolError("start_date and end_date must be YYYY-MM-DD") from e
    if end < start:
        raise ToolError("end_date is before start_date")
    if end - start > timedelta(days=MAX_RANGE_DAYS):
        raise ToolError(f"date range is limited to {MAX_RANGE_DAYS} days")
    return start, end


def _limit(args: dict, maximum: int) -> int:
    value = args.get("limit", 5)
    if not isinstance(value, int) or not 1 <= value <= maximum:
        raise ToolError(f"limit must be between 1 and {maximum}")
    return value
