"""
Generates a labeled, time-ordered synthetic transaction history for training
the fraud model. Deterministic for a given seed. Synthetic data only.

Legitimate behaviour follows per-card profiles, with realistic "noise" so
fraud isn't trivially separable: trips, occasional big purchases, first-time
categories and short legit bursts. Fraud is injected as four patterns:
velocity bursts, unusual merchant categories, impossible travel and amount spikes.
"""

import csv
import gzip
import math
import random
import uuid
from dataclasses import asdict, dataclass, field
from datetime import datetime, timedelta, timezone

from .geo import CITIES, City, haversine_km
from .merchants import BY_MCC, EVERYDAY_MCCS, HIGH_RISK_MERCHANTS, MERCHANTS, TRAVEL_MCCS, Merchant

EPOCH = datetime(2026, 1, 1, tzinfo=timezone.utc)
# Hour-of-day weights for legitimate spend: quiet overnight, busy midday/evening
HOUR_WEIGHTS = [1, 0.5, 0.3, 0.3, 0.3, 0.6, 1.5, 3, 4, 5, 5, 6, 7, 6, 5, 5, 5, 6, 7, 7, 6, 4, 3, 2]
FRAUD_PATTERNS = ("velocity_burst", "unusual_mcc", "impossible_travel", "amount_spike")
COLUMNS = ("ts", "card_id", "merchant_id", "merchant_name", "mcc", "amount_minor", "currency", "channel",
           "lat", "lon", "country", "is_fraud", "fraud_pattern")


@dataclass
class Txn:
    ts: datetime
    card_id: str
    merchant_id: str
    merchant_name: str
    mcc: str
    amount_minor: int
    currency: str
    channel: str  # CARD_PRESENT or ECOMMERCE
    lat: float | None
    lon: float | None
    country: str | None
    is_fraud: int = 0
    fraud_pattern: str = ""


@dataclass
class CardProfile:
    card_id: str
    home: City
    favourite_mccs: list[str]
    spend_multiplier: float
    txns_per_day: float
    online_share: float
    trips: list[tuple[int, int, City]] = field(default_factory=list)  # (start_day, end_day, city)

    def city_on(self, day: int) -> City:
        for start, end, city in self.trips:
            if start <= day <= end:
                return city
        return self.home


class DatasetGenerator:
    def __init__(self, seed: int = 7, cards: int = 1_500, days: int = 90, fraud_episode_rate: float = 0.5):
        self.rng = random.Random(seed)
        self.cards = cards
        self.days = days
        self.fraud_episode_rate = fraud_episode_rate

    # ---- profiles -------------------------------------------------------
    def profile(self) -> CardProfile:
        rng = self.rng
        home = rng.choices(CITIES, weights=[c.weight for c in CITIES])[0]
        favourites = rng.sample(EVERYDAY_MCCS, k=rng.randint(3, 5))
        if rng.random() < 0.3:
            favourites.append("5732")  # some people do buy electronics regularly
        card = CardProfile(
            card_id=str(uuid.UUID(int=rng.getrandbits(128), version=4)),
            home=home,
            favourite_mccs=favourites,
            spend_multiplier=rng.lognormvariate(0, 0.4),
            txns_per_day=rng.uniform(0.5, 3.0),
            online_share=rng.uniform(0.05, 0.4),
        )
        day = 0
        while day < self.days:
            if rng.random() < 0.012:  # ~1 trip per 80 days
                length = rng.randint(2, 6)
                dest = rng.choice([c for c in CITIES if c != home])
                card.trips.append((day, day + length, dest))
                day += length + 7
            day += 1
        return card

    # ---- legitimate traffic --------------------------------------------
    def _amount(self, merchant: Merchant, multiplier: float, sigma: float = 0.55) -> int:
        value = self.rng.lognormvariate(math.log(merchant.median_minor * multiplier), sigma)
        return max(50, min(2_000_000, round(value)))

    def _txn(self, card: CardProfile, ts: datetime, merchant: Merchant, amount: int, city: City | None,
             online: bool) -> Txn:
        return Txn(ts=ts, card_id=card.card_id, merchant_id=f"{merchant.merchant_id}-{(city or card.home).name[:3].lower()}"
                   if not online else merchant.merchant_id, merchant_name=merchant.name, mcc=merchant.mcc,
                   amount_minor=amount, currency="USD", channel="ECOMMERCE" if online else "CARD_PRESENT",
                   lat=None if online else round(city.lat + self.rng.uniform(-0.05, 0.05), 4),
                   lon=None if online else round(city.lon + self.rng.uniform(-0.05, 0.05), 4),
                   country=None if online else city.country)

    def _pick_online(self, card: CardProfile, merchant: Merchant) -> bool:
        if merchant.channel == "online":
            return True
        if merchant.channel == "in_person":
            return False
        return self.rng.random() < card.online_share

    def legit(self, card: CardProfile) -> list[Txn]:
        rng = self.rng
        out: list[Txn] = []
        for day in range(self.days):
            city = card.city_on(day)
            travelling = city != card.home
            n = self._poisson(card.txns_per_day * (1.3 if travelling else 1.0))
            for _ in range(n):
                hour = rng.choices(range(24), weights=HOUR_WEIGHTS)[0]
                ts = EPOCH + timedelta(days=day, hours=hour, minutes=rng.randint(0, 59), seconds=rng.randint(0, 59))
                if travelling and rng.random() < 0.25:
                    mcc = rng.choice(TRAVEL_MCCS)
                elif rng.random() < 0.85:
                    mcc = rng.choice(card.favourite_mccs)
                else:
                    mcc = rng.choice(EVERYDAY_MCCS + ("5732",))  # occasional first-time category
                merchant = BY_MCC[mcc]
                multiplier = card.spend_multiplier
                if rng.random() < 0.006:
                    multiplier *= rng.uniform(4, 9)  # legit big purchase
                online = self._pick_online(card, merchant)
                out.append(self._txn(card, ts, merchant, self._amount(merchant, multiplier), city, online))
                # Legit mini-burst: e.g. parking + coffee + snack within minutes
                if rng.random() < 0.02:
                    for k in range(rng.randint(1, 3)):
                        m2 = BY_MCC[rng.choice(card.favourite_mccs)]
                        out.append(self._txn(card, ts + timedelta(minutes=rng.randint(2, 12) * (k + 1)), m2,
                                             self._amount(m2, card.spend_multiplier * 0.5), city,
                                             self._pick_online(card, m2)))
            # Rarely, a legitimate purchase in a "high-risk" category (so category alone isn't proof)
            if rng.random() < 0.004:
                m = rng.choice(HIGH_RISK_MERCHANTS)
                ts = EPOCH + timedelta(days=day, hours=rng.randint(9, 21), minutes=rng.randint(0, 59))
                out.append(self._txn(card, ts, m, self._amount(m, card.spend_multiplier), city,
                                     self._pick_online(card, m)))
        return out

    # ---- fraud ----------------------------------------------------------
    def fraud(self, card: CardProfile, legit: list[Txn]) -> list[Txn]:
        rng = self.rng
        out: list[Txn] = []
        for _ in range(self._poisson(self.fraud_episode_rate)):
            pattern = rng.choices(FRAUD_PATTERNS, weights=[0.4, 0.2, 0.2, 0.2])[0]
            start = EPOCH + timedelta(days=rng.uniform(1, self.days - 0.5))
            far = rng.choice([c for c in CITIES if haversine_km(c.lat, c.lon, card.home.lat, card.home.lon) > 1500])

            if pattern == "velocity_burst":
                online = rng.random() < 0.7
                ts = start
                for _ in range(rng.randint(5, 15)):
                    m = rng.choice(HIGH_RISK_MERCHANTS + (BY_MCC["5732"],))
                    online_m = online or m.channel == "online"
                    out.append(self._txn(card, ts, m, self._amount(m, 1.0, 0.7), far, online_m))
                    ts += timedelta(seconds=rng.randint(30, 240))
            elif pattern == "unusual_mcc":
                ts = start
                for _ in range(rng.randint(1, 3)):
                    m = rng.choice(HIGH_RISK_MERCHANTS)
                    out.append(self._txn(card, ts, m, self._amount(m, rng.uniform(1, 3)), card.home,
                                         self._pick_online(card, m) or rng.random() < 0.5))
                    ts += timedelta(hours=rng.uniform(0.5, 6))
            elif pattern == "impossible_travel":
                anchors = [t for t in legit if t.lat is not None]
                if not anchors:
                    continue
                anchor = rng.choice(anchors)
                ts = anchor.ts + timedelta(minutes=rng.randint(15, 90))
                for _ in range(rng.randint(1, 3)):
                    m = BY_MCC[rng.choice(("5732", "5541", "5812", "5411"))]
                    out.append(self._txn(card, ts, m, self._amount(m, card.spend_multiplier * rng.uniform(1, 2.5)),
                                         far, False))
                    ts += timedelta(minutes=rng.randint(5, 40))
            else:  # amount_spike
                ts = start
                for _ in range(rng.randint(1, 2)):
                    m = BY_MCC[rng.choice(card.favourite_mccs)]
                    amount = round(self._amount(m, card.spend_multiplier, 0.3) * rng.uniform(10, 30))
                    out.append(self._txn(card, ts, m, min(amount, 2_000_000), card.city_on(int((ts - EPOCH).days)),
                                         self._pick_online(card, m)))
                    ts += timedelta(minutes=rng.randint(5, 120))

            for t in out:
                if not t.is_fraud:
                    t.is_fraud, t.fraud_pattern = 1, pattern
        return out

    # ---- assembly -------------------------------------------------------
    def generate(self) -> list[Txn]:
        rows: list[Txn] = []
        for _ in range(self.cards):
            card = self.profile()
            legit = self.legit(card)
            rows += legit
            rows += self.fraud(card, legit)
        end = EPOCH + timedelta(days=self.days)
        rows = [r for r in rows if r.ts < end]
        rows.sort(key=lambda r: (r.ts, r.card_id))
        return rows

    def _poisson(self, lam: float) -> int:
        # Knuth's method; fine for small lambda
        limit, k, p = math.exp(-lam), 0, 1.0
        while True:
            p *= self.rng.random()
            if p <= limit:
                return k
            k += 1


def write_csv(rows: list[Txn], path: str) -> None:
    opener = gzip.open if path.endswith(".gz") else open
    with opener(path, "wt", newline="") as f:
        w = csv.DictWriter(f, fieldnames=COLUMNS)
        w.writeheader()
        for r in rows:
            d = asdict(r)
            d["ts"] = r.ts.isoformat().replace("+00:00", "Z")
            w.writerow({k: ("" if d[k] is None else d[k]) for k in COLUMNS})


def read_csv(path: str) -> list[dict]:
    opener = gzip.open if path.endswith(".gz") else open
    with opener(path, "rt", newline="") as f:
        return list(csv.DictReader(f))
