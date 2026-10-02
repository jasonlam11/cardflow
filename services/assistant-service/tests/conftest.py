import pytest

from assistant.embeddings import FastEmbedder
from assistant.knowledge import load_chunks
from assistant.retrieval import Retriever
from assistant.settings import Settings
from assistant.store import InMemoryStore


@pytest.fixture(scope="session")
def chunks():
    return load_chunks(Settings().docs_dir)


@pytest.fixture(scope="session")
def retriever(chunks):
    r = Retriever(InMemoryStore(), FastEmbedder())
    r.index(chunks)
    return r
