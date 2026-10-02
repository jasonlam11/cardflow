# 11. Retrieval-augmented generation: local embeddings, pgvector, section chunks

- **Status:** Accepted
- **Date:** 2026-10-02

## Context
The assistant must answer benefits questions **only** from the card's own documents, with citations, and say "I don't know" otherwise. It also has to run with no API key (the project has none yet) and be testable in CI for free.

## Decision
- **Chunk by section** (`##` heading): one chunk per topic, with a stable citation id `doc#section` (e.g. `travel-insurance#trip-delay`). The document title and heading are prepended when embedding, so short sections still match questions.
- **Local embeddings:** `fastembed` with `BAAI/bge-small-en-v1.5` (384-d, ONNX, ~130 MB, no PyTorch). Free, deterministic, offline. The model is baked into the Docker image, so containers need no network. Questions get bge's query prefix; documents don't.
- **pgvector** in the service's own `assistant` database, HNSW index on cosine distance. The index is rebuilt only when a hash of the corpus changes.
- **Top-4 retrieval**, a loose similarity cutoff (0.5) that only drops clearly irrelevant sections.
- Retrieved sections go in the **user turn** (after the cacheable system prompt), wrapped as `<source id="doc:...">`.

## Measured, and why the cutoff is loose
On the 36-question eval set: every benefits question's expected section is in the top 4 (**recall@4 = 100%**; three were at rank 3, so top-1 or top-2 would not do). But similarity can't separate answerable from unanswerable questions: expected sections scored **0.685–0.882**, while unanswerable questions (credit score, rental-car insurance, cash-advance APR) still retrieved sections scoring **up to 0.677**. A threshold strict enough to drop those would start losing real answers. So "is this answerable from these sources?" is the model's job, enforced by the citation guardrail (ADR 0012), not a retrieval threshold.

## Alternatives considered
- **API embeddings** (e.g. Voyage): better quality, but a second API key, a cost per call, and network in CI. Swappable later behind the `Embedder` interface.
- **A dedicated vector database** (Qdrant, Pinecone): another service for 36 vectors; pgvector reuses the Postgres already running (see ADR-level decision #5 in NOTES).
- **Fixed-size token chunks with overlap**: better for long unstructured text; these documents are short and already organized by topic, and section ids make citations meaningful.
- **Hybrid search (BM25 + vectors)**: worth adding if recall drops as the corpus grows.

## Consequences
- Retrieval is fully testable without an LLM: CI fails if recall@4 drops below 100%.
- Adding a document = add a Markdown file; the service re-indexes on startup.
- bge-small is English-only and small; a larger corpus or other languages would need a bigger model and re-measurement.
