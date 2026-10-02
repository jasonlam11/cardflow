from fraud.features import FEATURES
from fraud.reasons import FEATURE_REASONS, top_reasons


def test_every_feature_has_a_reason():
    assert set(FEATURES) <= set(FEATURE_REASONS)


def test_only_positive_contributions_become_reasons():
    reasons = top_reasons({"txn_count_10m": 2.0, "amount_to_avg": -1.5, "is_high_risk_mcc": 0.4})
    assert [r["code"] for r in reasons] == ["HIGH_VELOCITY", "HIGH_RISK_MERCHANT_CATEGORY"]


def test_codes_are_deduplicated_keeping_the_strongest():
    reasons = top_reasons({"txn_count_10m": 0.5, "txn_count_1h": 1.2, "amount_to_avg": 0.9})
    assert [r["code"] for r in reasons] == ["HIGH_VELOCITY", "AMOUNT_SPIKE"]
    assert reasons[0]["contribution"] == 1.2


def test_at_most_three():
    contributions = {name: float(i + 1) for i, name in enumerate(FEATURES)}
    assert len(top_reasons(contributions)) == 3
