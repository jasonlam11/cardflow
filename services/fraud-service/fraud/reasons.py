"""Maps model features to human-readable reason codes for analysts and customers."""

from dataclasses import dataclass


@dataclass(frozen=True)
class Reason:
    code: str
    description: str


# Several features can point to the same reason; the strongest contribution wins
FEATURE_REASONS: dict[str, Reason] = {
    "txn_count_10m": Reason("HIGH_VELOCITY", "Many transactions on this card in the last few minutes"),
    "txn_count_1h": Reason("HIGH_VELOCITY", "Many transactions on this card in the last hour"),
    "txn_count_24h": Reason("HIGH_VELOCITY", "Unusually many transactions in the last 24 hours"),
    "distinct_merchants_1h": Reason("HIGH_VELOCITY", "Several different merchants within an hour"),
    "amount_sum_1h_log": Reason("HIGH_VELOCITY", "High total spend in the last hour"),
    "minutes_since_last_log": Reason("HIGH_VELOCITY", "Very little time since the previous transaction"),
    "amount_to_avg": Reason("AMOUNT_SPIKE", "Amount is far above this card's usual spend"),
    "amount_log": Reason("LARGE_AMOUNT", "Large transaction amount"),
    "speed_kmh_from_last_located": Reason("IMPOSSIBLE_TRAVEL", "Too far from the previous in-person transaction for the time elapsed"),
    "km_from_last_located": Reason("UNUSUAL_LOCATION", "Far from the previous in-person transaction"),
    "km_from_usual_location": Reason("UNUSUAL_LOCATION", "Far from where this card is usually used"),
    "mcc_first_time": Reason("UNUSUAL_MERCHANT_CATEGORY", "First purchase in this merchant category"),
    "is_high_risk_mcc": Reason("HIGH_RISK_MERCHANT_CATEGORY", "Merchant category frequently targeted by fraud"),
    "is_ecommerce": Reason("CARD_NOT_PRESENT", "Online (card-not-present) transaction"),
    "hour_of_day": Reason("UNUSUAL_TIME", "Unusual time of day"),
}


def top_reasons(contributions: dict[str, float], limit: int = 3) -> list[dict]:
    """
    contributions: per-feature SHAP values (log-odds; positive = pushes toward fraud).
    Returns up to `limit` distinct reason codes, strongest first. Only features
    that raised the score are reasons; ones that lowered it are not.
    """
    best: dict[str, tuple[float, Reason]] = {}
    for feature, value in contributions.items():
        reason = FEATURE_REASONS.get(feature)
        if reason is None or value <= 0:
            continue
        if reason.code not in best or value > best[reason.code][0]:
            best[reason.code] = (value, reason)
    ranked = sorted(best.values(), key=lambda pair: pair[0], reverse=True)[:limit]
    return [{"code": r.code, "description": r.description, "contribution": round(v, 4)} for v, r in ranked]
