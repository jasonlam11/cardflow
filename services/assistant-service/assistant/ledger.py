"""Read-only access to one cardholder's ledger data."""

from datetime import date
from typing import Protocol

import httpx


class LedgerPort(Protocol):
    """Everything the assistant can see about money. There are no write methods, by design."""

    def balance(self, card_id: str) -> dict | None: ...

    def spending(self, card_id: str, start: date, end: date) -> list[dict]: ...

    def transactions(self, card_id: str, start: date | None, end: date | None, sort: str, limit: int) -> list[dict]: ...


class LedgerUnavailable(Exception):
    pass


class HttpLedger:
    def __init__(self, base_url: str, timeout: float = 3.0):
        self.http = httpx.Client(base_url=base_url, timeout=timeout)

    def _account_id(self, card_id: str) -> str | None:
        r = self._get("/accounts", params={"externalRef": f"card:{card_id}"})
        return None if r is None else r["id"]

    def _get(self, path: str, params: dict | None = None):
        try:
            r = self.http.get(path, params=params)
        except httpx.HTTPError as e:
            raise LedgerUnavailable(str(e)) from e
        if r.status_code == 404:
            return None
        if r.status_code >= 400:
            raise LedgerUnavailable(f"ledger returned {r.status_code}")
        return r.json()

    def balance(self, card_id):
        account = self._account_id(card_id)
        return None if account is None else self._get(f"/accounts/{account}/balance")

    def spending(self, card_id, start, end):
        account = self._account_id(card_id)
        if account is None:
            return []
        return self._get(f"/accounts/{account}/spending", params={"from": start.isoformat(), "to": end.isoformat()}) or []

    def transactions(self, card_id, start, end, sort, limit):
        account = self._account_id(card_id)
        if account is None:
            return []
        params = {"sort": sort, "size": limit}
        if start:
            params["from"] = start.isoformat()
        if end:
            params["to"] = end.isoformat()
        page = self._get(f"/accounts/{account}/transactions", params=params)
        # Only charges (debits to the card account) are "transactions" from the cardholder's view
        return [t for t in (page or {}).get("content", []) if t["direction"] == "DEBIT"]
