import math
from datetime import datetime, timedelta, timezone

from fraud.features import FEATURES, HISTORY_LIMIT, Txn, compute_features, relevant_history, to_vector

T0 = datetime(2026, 3, 1, 12, 0, tzinfo=timezone.utc)
NYC = (40.71, -74.00)
LONDON = (51.51, -0.13)


def txn(minutes=0, amount=2_000, mcc="5411", merchant="m1", channel="CARD_PRESENT", where=NYC):
    lat, lon = where if where else (None, None)
    return Txn(ts=T0 + timedelta(minutes=minutes), amount_minor=amount, mcc=mcc, merchant_id=merchant,
               channel=channel, lat=lat, lon=lon)


def test_no_history_gives_nan_for_history_features():
    f = compute_features([], txn())
    for name in ("amount_to_avg", "minutes_since_last_log", "km_from_last_located", "mcc_first_time"):
        assert math.isnan(f[name]), name
    assert f["txn_count_1h"] == 0


def test_velocity_counts_only_prior_transactions_in_window():
    history = [txn(-120), txn(-50), txn(-8), txn(-3)]
    f = compute_features(history, txn(0))
    assert f["txn_count_10m"] == 2
    assert f["txn_count_1h"] == 3
    assert f["txn_count_24h"] == 4


def test_future_and_too_old_history_is_ignored():
    history = [txn(-60 * 24 * 31), txn(-5), txn(+5)]
    assert len(relevant_history(history, txn(0))) == 1


def test_history_is_capped():
    history = [txn(-i - 1) for i in range(500)][::-1]
    assert len(relevant_history(history, txn(0))) == HISTORY_LIMIT


def test_amount_spike_ratio():
    history = [txn(-300, amount=1_000), txn(-200, amount=3_000)]
    assert compute_features(history, txn(0, amount=40_000))["amount_to_avg"] == 20


def test_first_time_category():
    history = [txn(-100, mcc="5411"), txn(-50, mcc="5814")]
    assert compute_features(history, txn(0, mcc="5944"))["mcc_first_time"] == 1
    assert compute_features(history, txn(0, mcc="5814"))["mcc_first_time"] == 0


def test_impossible_travel_speed():
    # New York, then London 60 minutes later: ~5,570 km/h
    f = compute_features([txn(-60, where=NYC)], txn(0, where=LONDON))
    assert 5_400 < f["km_from_last_located"] < 5_700
    assert f["speed_kmh_from_last_located"] > 5_000


def test_online_transactions_have_no_location_features():
    f = compute_features([txn(-60)], txn(0, channel="ECOMMERCE", where=None))
    assert math.isnan(f["km_from_last_located"])
    assert f["is_ecommerce"] == 1


def test_high_risk_category_flag():
    assert compute_features([], txn(mcc="7995"))["is_high_risk_mcc"] == 1
    assert compute_features([], txn(mcc="5411"))["is_high_risk_mcc"] == 0


def test_vector_follows_feature_order():
    f = compute_features([txn(-5)], txn(0))
    assert to_vector(f) == [f[name] for name in FEATURES]
    assert len(FEATURES) == len(set(FEATURES))
