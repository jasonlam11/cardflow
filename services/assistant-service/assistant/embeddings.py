"""Local text embeddings (no API key, no cost, deterministic)."""

from typing import Protocol

import numpy as np

MODEL_NAME = "BAAI/bge-small-en-v1.5"
DIMENSIONS = 384
# bge models retrieve better when the question (not the documents) carries this prefix
QUERY_PREFIX = "Represent this sentence for searching relevant passages: "


class Embedder(Protocol):
    def embed_documents(self, texts: list[str]) -> np.ndarray: ...

    def embed_query(self, text: str) -> np.ndarray: ...


class FastEmbedder:
    def __init__(self, model_name: str = MODEL_NAME):
        from fastembed import TextEmbedding  # imported lazily: loading the model takes a moment

        self.model = TextEmbedding(model_name=model_name)

    def embed_documents(self, texts: list[str]) -> np.ndarray:
        return _normalize(np.array(list(self.model.embed(texts)), dtype=np.float32))

    def embed_query(self, text: str) -> np.ndarray:
        return _normalize(np.array(list(self.model.embed([QUERY_PREFIX + text])), dtype=np.float32))[0]


def _normalize(m: np.ndarray) -> np.ndarray:
    return m / np.linalg.norm(m, axis=-1, keepdims=True)
