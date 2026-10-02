"""
Feature engineering, shared by training and serving.

Both the offline training pipeline and the live API call compute_features()
with the same inputs: the card's earlier transactions plus the current one.
Keeping one implementation avoids training/serving skew, where a model sees
slightly different features in production than it was trained on.

Missing values are NaN on purpose: XGBoost learns how to route them.
"""

import math
from dataclasses import dataclass
from datetime import datetime, timedelta

HISTORY_WINDOW = timedelta(days=30)
HISTORY_LIMIT = 100
# Categories commonly targeted by fraud (easy to resell or cash out). Domain knowledge, not learned.
HIGH_RISK_MCCS = frozenset({"5944", "5999", "7995", "4829", "5732"})


@dataclass(frozen=True)
class Txn:
    ts: datetime
    amount_minor: int
    mcc: str
    merchant_id: str
    channel: str  # CARD_PRESENT or ECOMMERCE
    lat: float | None = None
    lon: float | None = None


# Order matters: it is the model's input column order
FEATURES: tuple[str, ...] = (
    "amount_log",
    "amount_to_avg",
    "txn_count_10m",
    "txn_count_1h",
    "txn_count_24h",
    "amount_sum_1h_log",
    "minutes_since_last_log",
    "km_from_last_located",
    "speed_kmh_from_last_located",
    "km_from_usual_location",
    "mcc_first_time",
    "is_high_risk_mcc",
    "is_ecommerce",
    "hour_of_day",
    "distinct_merchants_1h",
)

NAN = float("nan")


def relevant_history(history: list[Txn], current: Txn) -> list[Txn]:
    """Prior transactions within the window, oldest first, capped at HISTORY_LIMIT most recent."""
    cutoff = current.ts - HISTORY_WINDOW
    recent = [t for t in history if cutoff <= t.ts < current.ts]
    return recent[-HISTORY_LIMIT:]


def compute_features(history: list[Txn], current: Txn) -> dict[str, float]:
    """history: this card's earlier transactions, oldest first (extra/older rows are ignored)."""
    h = relevant_history(history, current)
    now = current.ts

    def within(delta: timedelta) -> list[Txn]:
        return [t for t in h if now - t.ts <= delta]

    last_10m, last_1h, last_24h = within(timedelta(minutes=10)), within(timedelta(hours=1)), within(timedelta(hours=24))

    if h:
        avg = sum(t.amount_minor for t in h) / len(h)
        amount_to_avg = current.amount_minor / avg if avg > 0 else NAN
        minutes_since_last = (now - h[-1].ts).total_seconds() / 60
        mcc_first_time = 0.0 if any(t.mcc == current.mcc for t in h) else 1.0
    else:
        amount_to_avg = minutes_since_last = mcc_first_time = NAN

    located = [t for t in h if t.lat is not None and t.lon is not None]
    km_last = speed = km_usual = NAN
    if current.lat is not None and current.lon is not None and located:
        prev = located[-1]
        km_last = haversine_km(prev.lat, prev.lon, current.lat, current.lon)
        hours = max((now - prev.ts).total_seconds() / 3600, 1 / 60)  # at least one minute
        speed = min(km_last / hours, 20_000.0)
        usual_lat = _median([t.lat for t in located])
        usual_lon = _median([t.lon for t in located])
        km_usual = haversine_km(usual_lat, usual_lon, current.lat, current.lon)

    return {
        "amount_log": math.log1p(current.amount_minor / 100),
        "amount_to_avg": amount_to_avg,
        "txn_count_10m": float(len(last_10m)),
        "txn_count_1h": float(len(last_1h)),
        "txn_count_24h": float(len(last_24h)),
        "amount_sum_1h_log": math.log1p(sum(t.amount_minor for t in last_1h) / 100),
        "minutes_since_last_log": math.log1p(minutes_since_last) if not math.isnan(minutes_since_last) else NAN,
        "km_from_last_located": km_last,
        "speed_kmh_from_last_located": speed,
        "km_from_usual_location": km_usual,
        "mcc_first_time": mcc_first_time,
        "is_high_risk_mcc": 1.0 if current.mcc in HIGH_RISK_MCCS else 0.0,
        "is_ecommerce": 1.0 if current.channel == "ECOMMERCE" else 0.0,
        "hour_of_day": float(now.hour),
        "distinct_merchants_1h": float(len({t.merchant_id for t in last_1h})),
    }


def to_vector(features: dict[str, float]) -> list[float]:
    return [features[name] for name in FEATURES]


def haversine_km(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    r = 6371.0
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp, dl = p2 - p1, math.radians(lon2 - lon1)
    a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * r * math.asin(math.sqrt(a))


def _median(values: list[float]) -> float:
    s = sorted(values)
    mid = len(s) // 2
    return s[mid] if len(s) % 2 else (s[mid - 1] + s[mid]) / 2
