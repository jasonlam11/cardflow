from cardflow_sim.generator import MAX_AMOUNT_MINOR, TrafficGenerator
from cardflow_sim.merchants import MERCHANTS

import pytest


def test_same_seed_same_traffic():
    a, b = TrafficGenerator(seed=7), TrafficGenerator(seed=7)
    cards = ["c1", "c2", "c3"]
    assert [a.charge(cards) for _ in range(50)] == [b.charge(cards) for _ in range(50)]


def test_amounts_are_positive_integers_within_cap():
    gen = TrafficGenerator(seed=1)
    for _ in range(5_000):
        amount = gen.amount_minor(gen.rng.choice(MERCHANTS))
        assert isinstance(amount, int)
        assert 1 <= amount <= MAX_AMOUNT_MINOR


def test_amounts_center_on_merchant_median():
    gen = TrafficGenerator(seed=2)
    coffee = next(m for m in MERCHANTS if m.mcc == "5814")
    draws = sorted(gen.amount_minor(coffee) for _ in range(4_001))
    median = draws[len(draws) // 2]
    assert 0.8 * coffee.median_minor < median < 1.2 * coffee.median_minor


def test_idempotency_keys_are_unique():
    gen = TrafficGenerator(seed=3)
    keys = {gen.charge(["c"]).idempotency_key for _ in range(10_000)}
    assert len(keys) == 10_000


def test_charge_body_matches_api_contract():
    body = TrafficGenerator(seed=4).charge(["card-1"]).body()
    assert set(body) == {"cardId", "merchantId", "merchantName", "mcc", "amountMinor", "currency"}
    assert body["cardId"] == "card-1"
    assert len(body["mcc"]) == 4 and body["mcc"].isdigit()


def test_retry_rate_is_respected():
    gen = TrafficGenerator(seed=5, retry_rate=0.05)
    retries = sum(gen.should_retry() for _ in range(20_000))
    assert 800 < retries < 1_200


def test_credit_limits_in_range():
    gen = TrafficGenerator(seed=6)
    for _ in range(1_000):
        limit = gen.credit_limit_minor()
        assert 50_000 <= limit <= 2_000_000 and limit % 10_000 == 0


def test_invalid_retry_rate_rejected():
    with pytest.raises(ValueError):
        TrafficGenerator(retry_rate=1.5)
