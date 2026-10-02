"""Pure, seedable generation of cards and charges (no I/O, so it's easy to test)."""

import math
import random
import uuid
from dataclasses import dataclass

from .merchants import MERCHANTS, Merchant

MAX_AMOUNT_MINOR = 500_000  # $5,000 cap so one draw can't be absurd


@dataclass(frozen=True)
class Charge:
    idempotency_key: str
    card_id: str
    merchant: Merchant
    amount_minor: int
    currency: str = "USD"

    def body(self) -> dict:
        return {
            "cardId": self.card_id,
            "merchantId": self.merchant.merchant_id,
            "merchantName": self.merchant.name,
            "mcc": self.merchant.mcc,
            "amountMinor": self.amount_minor,
            "currency": self.currency,
        }


class TrafficGenerator:
    def __init__(self, seed: int | None = None, retry_rate: float = 0.05):
        if not 0 <= retry_rate <= 1:
            raise ValueError("retry_rate must be between 0 and 1")
        self.rng = random.Random(seed)
        self.retry_rate = retry_rate

    def credit_limit_minor(self) -> int:
        """Card limits between $500 and $20,000, rounded to $100."""
        return self.rng.randint(5, 200) * 10_000

    def amount_minor(self, merchant: Merchant) -> int:
        """Log-normal around the merchant's median: many small tickets, a few large ones."""
        value = self.rng.lognormvariate(math.log(merchant.median_minor), 0.6)
        return max(1, min(MAX_AMOUNT_MINOR, round(value)))

    def charge(self, card_ids: list[str]) -> Charge:
        merchant = self.rng.choice(MERCHANTS)
        key = str(uuid.UUID(int=self.rng.getrandbits(128), version=4))
        return Charge(key, self.rng.choice(card_ids), merchant, self.amount_minor(merchant))

    def should_retry(self) -> bool:
        """Simulates a client that didn't see the response and resends the same request."""
        return self.rng.random() < self.retry_rate
