from datetime import datetime, timedelta, timezone
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from fraud.api import create_app
from fraud.model import FraudModel
from fraud.store import InMemoryHistoryStore

MODEL = FraudModel.load(Path(__file__).resolve().parents[1] / "model")
T0 = datetime(2026, 3, 20, 14, 0, tzinfo=timezone.utc)


@pytest.fixture
def client():
    with TestClient(create_app(model=MODEL, store=InMemoryHistoryStore())) as c:
        yield c


def body(request_id, minutes=0, amount=2_500, mcc="5411", card="card-1", channel="CARD_PRESENT",
         where=(40.71, -74.0)):
    b = {"requestId": request_id, "cardId": card, "amountMinor": amount, "currency": "USD", "mcc": mcc,
         "merchantId": f"m-{mcc}", "channel": channel, "occurredAt": (T0 + timedelta(minutes=minutes)).isoformat()}
    if where:
        b["merchantLocation"] = {"lat": where[0], "lon": where[1], "country": "US"}
    return b


def build_history(client, card="card-1", days=10):
    """A normal cardholder: a couple of grocery/coffee purchases a day in New York."""
    for d in range(days):
        for i, (mcc, amount) in enumerate((("5411", 4_000), ("5814", 600))):
            r = client.post("/score", json=body(f"h-{card}-{d}-{i}", minutes=-(days - d) * 1440 + i * 300,
                                                  amount=amount, mcc=mcc, card=card))
            assert r.status_code == 200


def test_normal_purchase_is_low_risk(client):
    build_history(client)
    r = client.post("/score", json=body("normal-1", amount=3_800))
    assert r.status_code == 200
    data = r.json()
    assert data["band"] == "LOW"
    assert 0 <= data["score"] < MODEL.review_threshold
    assert data["modelVersion"] == MODEL.version


def test_velocity_burst_of_high_risk_purchases_is_flagged_with_reasons(client):
    build_history(client)
    last = None
    for i in range(8):
        last = client.post("/score", json=body(f"burst-{i}", minutes=i, amount=25_000, mcc="5999",
                                               channel="ECOMMERCE", where=None)).json()
    assert last["band"] in ("REVIEW", "HIGH")
    codes = [r["code"] for r in last["reasons"]]
    assert 1 <= len(codes) <= 3
    assert "HIGH_VELOCITY" in codes or "HIGH_RISK_MERCHANT_CATEGORY" in codes
    assert all(r["contribution"] > 0 for r in last["reasons"])


def test_impossible_travel_is_flagged(client):
    build_history(client)
    client.post("/score", json=body("nyc", minutes=0, where=(40.71, -74.0)))
    r = client.post("/score", json=body("london", minutes=45, amount=30_000, mcc="5732", where=(51.5, -0.12)))
    data = r.json()
    assert data["band"] in ("REVIEW", "HIGH")
    assert any(c["code"] in ("IMPOSSIBLE_TRAVEL", "UNUSUAL_LOCATION") for c in data["reasons"])


def test_same_request_id_is_recorded_once(client):
    store = client.app.state.store
    client.post("/score", json=body("dup"))
    client.post("/score", json=body("dup"))
    assert sum(1 for k in store.rows if k == "dup") == 1


def test_validation_errors_use_problem_json(client):
    bad = body("x", amount=-5)
    bad["mcc"] = "54"
    r = client.post("/score", json=bad)
    assert r.status_code == 422
    assert r.headers["content-type"].startswith("application/problem+json")
    assert set(r.json()["errors"]) >= {"amountMinor", "mcc"}


def test_future_timestamp_rejected(client):
    future = body("future")
    future["occurredAt"] = (datetime.now(timezone.utc) + timedelta(hours=2)).isoformat()
    assert client.post("/score", json=future).status_code == 422


def test_correlation_id_echoed_and_sanitized(client):
    assert client.get("/health", headers={"X-Correlation-Id": "abc-123"}).headers["X-Correlation-Id"] == "abc-123"
    assert client.get("/health", headers={"X-Correlation-Id": "bad id\n"}).headers["X-Correlation-Id"] != "bad id\n"


def test_health_and_model_endpoints(client):
    assert client.get("/health").json() == {"status": "UP", "database": "UP", "modelVersion": MODEL.version}
    info = client.get("/model").json()
    assert info["thresholds"]["high"] >= info["thresholds"]["review"]
    assert info["testMetrics"]["prAuc"] > 0.5
