from datetime import timedelta

from cardflow_sim.dataset import EPOCH, FRAUD_PATTERNS, DatasetGenerator, read_csv, write_csv
from cardflow_sim.geo import haversine_km


def small(seed=11):
    return DatasetGenerator(seed=seed, cards=200, days=30).generate()


def test_deterministic_for_a_seed():
    a, b = small(), small()
    assert [(r.ts, r.card_id, r.amount_minor, r.is_fraud) for r in a] == \
           [(r.ts, r.card_id, r.amount_minor, r.is_fraud) for r in b]


def test_sorted_by_time_and_within_window():
    rows = small()
    assert all(x.ts <= y.ts for x, y in zip(rows, rows[1:]))
    assert rows[0].ts >= EPOCH and rows[-1].ts < EPOCH + timedelta(days=30)


def test_fraud_rate_is_realistic():
    rows = DatasetGenerator(seed=3, cards=600, days=60).generate()
    rate = sum(r.is_fraud for r in rows) / len(rows)
    assert 0.005 < rate < 0.03


def test_every_fraud_row_has_a_known_pattern():
    for r in small():
        assert (r.fraud_pattern in FRAUD_PATTERNS) == bool(r.is_fraud)


def test_online_rows_have_no_location_and_card_present_rows_do():
    for r in small():
        if r.channel == "ECOMMERCE":
            assert r.lat is None and r.country is None
        else:
            assert r.lat is not None and r.country


def test_impossible_travel_is_actually_impossible():
    rows = DatasetGenerator(seed=5, cards=800, days=60).generate()
    by_card = {}
    checked = 0
    for r in rows:
        prev = by_card.get(r.card_id)
        if r.fraud_pattern == "impossible_travel" and prev is not None and prev.lat is not None and r.lat is not None:
            hours = max((r.ts - prev.ts).total_seconds() / 3600, 1e-6)
            if not prev.is_fraud:
                assert haversine_km(prev.lat, prev.lon, r.lat, r.lon) / hours > 900  # faster than a plane
                checked += 1
        if r.lat is not None:
            by_card[r.card_id] = r
    assert checked > 0


def test_csv_round_trip(tmp_path):
    rows = small()[:50]
    path = str(tmp_path / "t.csv.gz")
    write_csv(rows, path)
    back = read_csv(path)
    assert len(back) == 50
    assert back[0]["card_id"] == rows[0].card_id
    assert int(back[0]["amount_minor"]) == rows[0].amount_minor
