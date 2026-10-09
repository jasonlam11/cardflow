"""PgVectorStore against real Postgres + pgvector (Testcontainers). Skipped without Docker."""

import numpy as np
import pytest

from assistant.embeddings import DIMENSIONS
from assistant.knowledge import Chunk

pg = pytest.importorskip("testcontainers.postgres")


@pytest.fixture(scope="module")
def store():
    from assistant.store import PgVectorStore

    try:
        container = pg.PostgresContainer("pgvector/pgvector:0.8.7-pg18-trixie", driver=None).start()
    except Exception as e:  # pragma: no cover
        pytest.skip(f"Docker unavailable: {e}")
    dsn = (f"host={container.get_container_host_ip()} port={container.get_exposed_port(5432)} "
           f"dbname={container.dbname} user={container.username} password={container.password}")
    import psycopg

    with psycopg.connect(dsn, autocommit=True) as conn:  # in compose the DB admin creates this
        conn.execute("CREATE EXTENSION IF NOT EXISTS vector")
    s = PgVectorStore(dsn)
    yield s
    s.close()
    container.stop()


def unit(i: int) -> np.ndarray:
    v = np.zeros(DIMENSIONS, dtype=np.float32)
    v[i] = 1.0
    return v


def test_search_returns_nearest_by_cosine(store):
    chunks = [Chunk(f"d#s{i}", "d", "Doc", f"S{i}", f"text {i}") for i in range(3)]
    store.replace_all(chunks, np.stack([unit(0), unit(1), unit(2)]), "h1")
    query = unit(1) * 0.9 + unit(2) * 0.1
    hits = store.search(query / np.linalg.norm(query), 2)
    assert [h.chunk.id for h in hits] == ["d#s1", "d#s2"]
    assert hits[0].score > 0.9


def test_replace_all_swaps_the_corpus_and_records_its_hash(store):
    store.replace_all([Chunk("x#y", "x", "X", "Y", "only")], np.stack([unit(5)]), "h2")
    assert store.corpus_hash() == "h2"
    assert [h.chunk.id for h in store.search(unit(5), 5)] == ["x#y"]
