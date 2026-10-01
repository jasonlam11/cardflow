"""Thin HTTP client for the authorization-service API."""

import httpx


class AuthorizationClient:
    def __init__(self, base_url: str, timeout: float = 5.0):
        self.http = httpx.Client(base_url=base_url, timeout=timeout)

    def close(self) -> None:
        self.http.close()

    def create_card(self, credit_limit_minor: int, currency: str = "USD") -> str:
        r = self.http.post("/cards", json={"creditLimitMinor": credit_limit_minor, "currency": currency})
        r.raise_for_status()
        return r.json()["id"]

    def authorize(self, idempotency_key: str, body: dict, correlation_id: str | None = None) -> httpx.Response:
        headers = {"Idempotency-Key": idempotency_key}
        if correlation_id:
            headers["X-Correlation-Id"] = correlation_id
        return self.http.post("/authorizations", json=body, headers=headers)
