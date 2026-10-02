"""Builds (or reuses) the index and retrieves the most relevant sections for a question."""

import logging

from .embeddings import Embedder
from .knowledge import Chunk, corpus_hash
from .store import Hit, VectorStore

log = logging.getLogger("assistant.retrieval")


class Retriever:
    def __init__(self, store: VectorStore, embedder: Embedder, k: int = 4, min_score: float = 0.5):
        self.store = store
        self.embedder = embedder
        self.k = k
        # Only drops CLEARLY irrelevant sections. Measured on the eval set (bge-small): expected
        # sections score 0.685-0.882, but unanswerable questions still pull in sections up to 0.677.
        # The bands nearly touch, so "is this answerable?" is decided by the model + citation
        # guardrail, not by a similarity threshold (see ADR 0011).
        self.min_score = min_score

    def index(self, chunks: list[Chunk]) -> bool:
        """Re-embeds only when the documents changed. Returns True if it rebuilt."""
        h = corpus_hash(chunks)
        if self.store.corpus_hash() == h:
            return False
        vectors = self.embedder.embed_documents([c.embedding_text() for c in chunks])
        self.store.replace_all(chunks, vectors, h)
        log.info("indexed %d chunks (corpus %s)", len(chunks), h)
        return True

    def retrieve(self, question: str) -> list[Hit]:
        hits = self.store.search(self.embedder.embed_query(question), self.k)
        return [h for h in hits if h.score >= self.min_score]
