"""PostgresHistoryStore against a real Postgres (Testcontainers). Skipped if Docker isn't available."""

from datetime import datetime, timedelta, timezone

import pytest

from fraud.features import Txn

pg = pytest.importorskip("testcontainers.postgres")


@pytest.fixture(scope="module")
def store():
    from fraud.store import PostgresHistoryStore
    try:
        container = pg.PostgresContainer("postgres:18.6", driver=None).start()
    except Exception as e:  # pragma: no cover
        pytest.skip(f"Docker unavailable: {e}")
    s = PostgresHistoryStore(f"host={container.get_container_host_ip()} port={container.get_exposed_port(5432)} "
                             f"dbname={container.dbname} user={container.username} password={container.password}")
    yield s
    s.close()
    container.stop()


T0 = datetime(2026, 3, 1, tzinfo=timezone.utc)


def t(minutes):
    return Txn(ts=T0 + timedelta(minutes=minutes), amount_minor=1000 + minutes, mcc="5411", merchant_id="m",
               channel="CARD_PRESENT", lat=40.7, lon=-74.0)


def test_recent_returns_prior_rows_oldest_first(store):
    for i, m in enumerate((10, 30, 20, 50)):
        store.record(f"r-{i}", "card-a", t(m), 0.1, "LOW", "v1")
    store.record("other", "card-b", t(25), 0.1, "LOW", "v1")
    history = store.recent("card-a", T0 + timedelta(minutes=40))
    assert [h.amount_minor for h in history] == [1010, 1020, 1030]


def test_record_is_idempotent_per_request(store):
    store.record("same", "card-c", t(5), 0.1, "LOW", "v1")
    store.record("same", "card-c", t(5), 0.9, "HIGH", "v1")
    assert len(store.recent("card-c", T0 + timedelta(hours=1))) == 1


def test_schema_can_be_applied_twice(store):
    from fraud.store import PostgresHistoryStore
    again = PostgresHistoryStore(store.pool.conninfo)
    assert again.healthy()
    again.close()
