from datetime import datetime, timedelta, timezone

from conftest import charge, compose, create_card, spaced_times, wait_until

NYC = (40.71, -74.0)


def build_normal_history(auth, card, n=12):
    """A few ordinary grocery purchases in New York, hours apart."""
    for when in spaced_times(n):
        r = charge(auth, card, 4_200, occurred_at=when, location=NYC)
        assert r.status_code == 201, r.text
        assert r.json()["fraud"]["scoredBy"] == "MODEL"


def test_fraud_burst_is_held_or_declined_by_the_model(auth):
    card = create_card(auth, limit=5_000_000)
    build_normal_history(auth, card)

    # Then: a rapid burst of online gift-card purchases
    start = datetime.now(timezone.utc) - timedelta(minutes=20)
    outcomes = []
    for i in range(8):
        r = charge(auth, card, 30_000, mcc="5999", channel="ECOMMERCE", occurred_at=start + timedelta(minutes=i))
        body = r.json()
        outcomes.append((body["status"], body.get("declineReason"), body["fraud"]))

    status, reason, fraud = outcomes[-1]
    assert fraud["scoredBy"] == "MODEL"
    assert status == "PENDING_REVIEW" or reason == "FRAUD_SUSPECTED", outcomes[-1]
    assert fraud["band"] in ("REVIEW", "HIGH")
    assert 1 <= len(fraud["reasons"]) <= 3


def test_authorizations_keep_working_when_fraud_service_is_down(auth):
    card = create_card(auth, limit=5_000_000)
    compose("stop", "fraud-service")
    try:
        for when in spaced_times(3):
            r = charge(auth, card, 2_000, occurred_at=when)
            assert r.status_code == 201, r.text
            assert r.json()["fraud"]["scoredBy"] == "RULES_FALLBACK"
        # Rules are conservative: a large charge is held for review, never auto-declined
        big = charge(auth, card, 300_000)
        assert big.status_code == 202
        assert big.json()["fraud"]["scoredBy"] == "RULES_FALLBACK"
    finally:
        compose("start", "fraud-service")

    # Once fraud-service is back (and the circuit breaker lets calls through again), the model takes over
    def scored_by_model():
        r = charge(auth, card, 1_500, occurred_at=datetime.now(timezone.utc))
        return r.json()["fraud"]["scoredBy"] == "MODEL"

    wait_until(scored_by_model, timeout=90, interval=3, message="model scoring to resume")
