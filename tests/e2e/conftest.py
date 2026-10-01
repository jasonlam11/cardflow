"""
End-to-end tests against the real Docker Compose stack.

By default they use a stack you already started (`make up`), which is fast.
With E2E_START_STACK=1 (used in CI), Testcontainers starts and stops the stack.
"""

import os
import pathlib
import subprocess
import time
import uuid

import httpx
import pytest

REPO = pathlib.Path(__file__).resolve().parents[2]
AUTH_URL = os.environ.get("AUTHORIZATION_URL", "http://localhost:8082")


@pytest.fixture(scope="session", autouse=True)
def stack():
    if os.environ.get("E2E_START_STACK") == "1":
        from testcontainers.compose import DockerCompose

        with DockerCompose(context=str(REPO), wait=True, build=True) as compose:
            yield compose
    else:
        yield None


@pytest.fixture(scope="session")
def auth():
    with httpx.Client(base_url=AUTH_URL, timeout=10) as client:
        client.get("/actuator/health").raise_for_status()
        yield client


def ledger_sql(query: str) -> str:
    """Read-only checks run directly against the ledger database (as the admin role)."""
    admin = os.environ.get("POSTGRES_ADMIN_USER", "cardflow_admin")
    out = subprocess.run(
        ["docker", "exec", "cardflow-postgres", "psql", "-U", admin, "-d", "ledger", "-tAc", query],
        check=True, capture_output=True, text=True,
    )
    return out.stdout.strip()


def auth_sql(query: str) -> str:
    admin = os.environ.get("POSTGRES_ADMIN_USER", "cardflow_admin")
    out = subprocess.run(
        ["docker", "exec", "cardflow-postgres", "psql", "-U", admin, "-d", "authorization", "-tAc", query],
        check=True, capture_output=True, text=True,
    )
    return out.stdout.strip()


def compose(*args: str) -> None:
    subprocess.run(["docker", "compose", *args], cwd=REPO, check=True, capture_output=True)


def wait_until(condition, timeout: float = 60, interval: float = 1, message: str = "condition"):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if condition():
            return
        time.sleep(interval)
    raise AssertionError(f"timed out after {timeout}s waiting for {message}")


def charge(auth: httpx.Client, card_id: str, amount: int, key: str | None = None) -> httpx.Response:
    return auth.post(
        "/authorizations",
        headers={"Idempotency-Key": key or str(uuid.uuid4())},
        json={"cardId": card_id, "merchantId": "m-e2e", "merchantName": "E2E Test Shop", "mcc": "5411",
              "amountMinor": amount, "currency": "USD"},
    )


def create_card(auth: httpx.Client, limit: int) -> str:
    r = auth.post("/cards", json={"creditLimitMinor": limit, "currency": "USD"})
    r.raise_for_status()
    return r.json()["id"]
