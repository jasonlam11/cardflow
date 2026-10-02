"""Vector store: pgvector in Postgres for the service, in-memory for tests and evals."""

from dataclasses import dataclass
from typing import Protocol

import numpy as np

from .embeddings import DIMENSIONS
from .knowledge import Chunk


@dataclass(frozen=True)
class Hit:
    chunk: Chunk
    score: float  # cosine similarity, 1 = identical


class VectorStore(Protocol):
    def replace_all(self, chunks: list[Chunk], vectors: np.ndarray, corpus_hash: str) -> None: ...

    def corpus_hash(self) -> str | None: ...

    def search(self, query: np.ndarray, k: int) -> list[Hit]: ...


class InMemoryStore:
    def __init__(self):
        self.chunks: list[Chunk] = []
        self.vectors = np.zeros((0, DIMENSIONS), dtype=np.float32)
        self._hash: str | None = None

    def replace_all(self, chunks, vectors, corpus_hash):
        self.chunks, self.vectors, self._hash = list(chunks), vectors, corpus_hash

    def corpus_hash(self):
        return self._hash

    def search(self, query, k):
        if not self.chunks:
            return []
        scores = self.vectors @ query  # vectors are normalized, so dot product = cosine
        top = np.argsort(-scores)[:k]
        return [Hit(self.chunks[i], float(scores[i])) for i in top]


SCHEMA = f"""
CREATE EXTENSION IF NOT EXISTS vector;
CREATE TABLE IF NOT EXISTS doc_chunks (
    id         VARCHAR(200) PRIMARY KEY,
    doc        VARCHAR(100) NOT NULL,
    title      VARCHAR(200) NOT NULL,
    section    VARCHAR(200) NOT NULL,
    text       TEXT         NOT NULL,
    embedding  vector({DIMENSIONS}) NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_doc_chunks_embedding ON doc_chunks USING hnsw (embedding vector_cosine_ops);
CREATE TABLE IF NOT EXISTS index_state (
    name   VARCHAR(50) PRIMARY KEY,
    value  VARCHAR(100) NOT NULL
);
"""


class PgVectorStore:
    """pgvector with an HNSW index. The extension is created by the DB admin (infra init script)."""

    def __init__(self, dsn: str):
        from pgvector.psycopg import register_vector
        from psycopg_pool import ConnectionPool

        self.pool = ConnectionPool(dsn, min_size=1, max_size=5, open=True, kwargs={"autocommit": True},
                                   configure=register_vector)
        with self.pool.connection() as conn:
            for statement in [s for s in SCHEMA.split(";") if s.strip() and "CREATE EXTENSION" not in s]:
                conn.execute(statement)

    def close(self):
        self.pool.close()

    def replace_all(self, chunks, vectors, corpus_hash):
        with self.pool.connection() as conn, conn.transaction():
            conn.execute("DELETE FROM doc_chunks")
            with conn.cursor() as cur:
                cur.executemany(
                    "INSERT INTO doc_chunks (id, doc, title, section, text, embedding) VALUES (%s, %s, %s, %s, %s, %s)",
                    [(c.id, c.doc, c.title, c.section, c.text, v) for c, v in zip(chunks, vectors)])
            conn.execute("""INSERT INTO index_state (name, value) VALUES ('corpus_hash', %s)
                            ON CONFLICT (name) DO UPDATE SET value = EXCLUDED.value""", (corpus_hash,))

    def corpus_hash(self):
        with self.pool.connection() as conn:
            row = conn.execute("SELECT value FROM index_state WHERE name = 'corpus_hash'").fetchone()
        return row[0] if row else None

    def search(self, query, k):
        with self.pool.connection() as conn:
            rows = conn.execute(
                """SELECT id, doc, title, section, text, 1 - (embedding <=> %s) AS score
                     FROM doc_chunks ORDER BY embedding <=> %s LIMIT %s""",
                (query, query, k)).fetchall()
        return [Hit(Chunk(r[0], r[1], r[2], r[3], r[4]), float(r[5])) for r in rows]

    def healthy(self) -> bool:
        try:
            with self.pool.connection(timeout=2) as conn:
                conn.execute("SELECT 1")
            return True
        except Exception:
            return False
