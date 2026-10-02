"""Per-card transaction history used to compute features."""

from datetime import datetime
from importlib import resources
from typing import Protocol

from psycopg_pool import ConnectionPool

from .features import HISTORY_LIMIT, HISTORY_WINDOW, Txn


class HistoryStore(Protocol):
    def recent(self, card_id: str, before: datetime) -> list[Txn]: ...

    def record(self, request_id: str, card_id: str, txn: Txn, score: float, band: str, model_version: str) -> None: ...

    def healthy(self) -> bool: ...


class PostgresHistoryStore:
    def __init__(self, dsn: str):
        self.pool = ConnectionPool(dsn, min_size=1, max_size=10, open=True, kwargs={"autocommit": True})
        with self.pool.connection() as conn:
            conn.execute(resources.files("fraud").joinpath("schema.sql").read_text())

    def close(self) -> None:
        self.pool.close()

    def recent(self, card_id: str, before: datetime) -> list[Txn]:
        with self.pool.connection() as conn:
            rows = conn.execute(
                """SELECT occurred_at, amount_minor, mcc, merchant_id, channel, lat, lon
                     FROM card_activity
                    WHERE card_id = %s AND occurred_at < %s AND occurred_at >= %s
                    ORDER BY occurred_at DESC
                    LIMIT %s""",
                (card_id, before, before - HISTORY_WINDOW, HISTORY_LIMIT),
            ).fetchall()
        return [Txn(*row) for row in reversed(rows)]  # oldest first, as compute_features expects

    def record(self, request_id: str, card_id: str, txn: Txn, score: float, band: str, model_version: str) -> None:
        with self.pool.connection() as conn:
            conn.execute(
                """INSERT INTO card_activity (request_id, card_id, occurred_at, amount_minor, mcc, merchant_id,
                                              channel, lat, lon, score, band, model_version)
                   VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
                   ON CONFLICT (request_id) DO NOTHING""",
                (request_id, card_id, txn.ts, txn.amount_minor, txn.mcc, txn.merchant_id, txn.channel, txn.lat,
                 txn.lon, score, band, model_version),
            )

    def healthy(self) -> bool:
        try:
            with self.pool.connection(timeout=2) as conn:
                conn.execute("SELECT 1")
            return True
        except Exception:
            return False


class InMemoryHistoryStore:
    """For tests and local experiments without Postgres."""

    def __init__(self):
        self.rows: dict[str, tuple[str, Txn]] = {}

    def recent(self, card_id: str, before: datetime) -> list[Txn]:
        txns = sorted((t for c, t in self.rows.values() if c == card_id and t.ts < before), key=lambda t: t.ts)
        return [t for t in txns if t.ts >= before - HISTORY_WINDOW][-HISTORY_LIMIT:]

    def record(self, request_id: str, card_id: str, txn: Txn, score: float, band: str, model_version: str) -> None:
        self.rows.setdefault(request_id, (card_id, txn))

    def healthy(self) -> bool:
        return True
