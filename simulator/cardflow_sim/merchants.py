"""Synthetic merchant categories with real merchant category codes (MCCs) and typical spend."""

from dataclasses import dataclass


@dataclass(frozen=True)
class Merchant:
    merchant_id: str
    name: str
    mcc: str
    # Median ticket size in cents; amounts are drawn log-normally around it
    median_minor: int
    # "in_person", "online" or "both"
    channel: str = "both"
    # Categories fraudsters favour (easy to resell); not a model input, only used to generate data
    high_risk: bool = False


# Everyday categories; v1 live traffic uses only these
MERCHANTS: tuple[Merchant, ...] = (
    Merchant("m-coffee-01", "Blue Bottle Coffee", "5814", 650, "in_person"),
    Merchant("m-grocery-01", "Fresh Market Grocery", "5411", 6_500, "both"),
    Merchant("m-fuel-01", "Shell Station 214", "5541", 4_500, "in_person"),
    Merchant("m-restaurant-01", "Harbor Grill", "5812", 5_800, "in_person"),
    Merchant("m-ride-01", "QuickRide", "4121", 2_200, "online"),
    Merchant("m-streaming-01", "StreamFlix", "4899", 1_599, "online"),
    Merchant("m-pharmacy-01", "Corner Pharmacy", "5912", 2_400, "both"),
    Merchant("m-electronics-01", "Volt Electronics", "5732", 18_000, "both", high_risk=True),
    Merchant("m-airline-01", "SkyHigh Airlines", "4511", 42_000, "online"),
    Merchant("m-hotel-01", "Bayview Hotel", "7011", 26_000, "in_person"),
)

# Categories that mostly appear in fraud (and occasionally in legitimate spend)
HIGH_RISK_MERCHANTS: tuple[Merchant, ...] = (
    Merchant("m-jewelry-01", "Gold & Co Jewelers", "5944", 45_000, "both", high_risk=True),
    Merchant("m-giftcards-01", "CardMart Gift Cards", "5999", 20_000, "online", high_risk=True),
    Merchant("m-betting-01", "LuckyBet Online", "7995", 15_000, "online", high_risk=True),
    Merchant("m-wire-01", "SwiftSend Money Transfer", "4829", 30_000, "online", high_risk=True),
)

ALL_MERCHANTS: tuple[Merchant, ...] = MERCHANTS + HIGH_RISK_MERCHANTS
EVERYDAY_MCCS: tuple[str, ...] = ("5814", "5411", "5541", "5812", "4121", "4899", "5912")
TRAVEL_MCCS: tuple[str, ...] = ("4511", "7011")
BY_MCC: dict[str, Merchant] = {m.mcc: m for m in ALL_MERCHANTS}
