"""Synthetic merchants with real merchant category codes (MCCs) and typical spend."""

from dataclasses import dataclass


@dataclass(frozen=True)
class Merchant:
    merchant_id: str
    name: str
    mcc: str
    # Median ticket size in cents; amounts are drawn log-normally around it
    median_minor: int


MERCHANTS: tuple[Merchant, ...] = (
    Merchant("m-coffee-01", "Blue Bottle Coffee", "5814", 650),
    Merchant("m-grocery-01", "Fresh Market Grocery", "5411", 6_500),
    Merchant("m-fuel-01", "Shell Station 214", "5541", 4_500),
    Merchant("m-restaurant-01", "Harbor Grill", "5812", 5_800),
    Merchant("m-ride-01", "QuickRide", "4121", 2_200),
    Merchant("m-streaming-01", "StreamFlix", "4899", 1_599),
    Merchant("m-pharmacy-01", "Corner Pharmacy", "5912", 2_400),
    Merchant("m-electronics-01", "Volt Electronics", "5732", 18_000),
    Merchant("m-airline-01", "SkyHigh Airlines", "4511", 42_000),
    Merchant("m-hotel-01", "Bayview Hotel", "7011", 26_000),
)
