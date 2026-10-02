# assistant-service

Answers cardholder questions about **card benefits** (RAG over `docs/benefits/`) and **their own spending** (read-only ledger tools), with citations and guardrails. Python 3.14, FastAPI, pgvector, local embeddings, Claude via the official Anthropic SDK.

## API
| Method | Path | Description |
|---|---|---|
| POST | `/chat` | `{cardId, message}` → `{answer, citations, toolsUsed, refused, guardrail, model, demoMode, usage, latencyMs}` |
| GET | `/info` | Provider, model, demo mode, documents indexed |
| GET | `/health` | Liveness + DB |

## Model
`ASSISTANT_PROVIDER=auto` (default) uses Claude when `ANTHROPIC_API_KEY` is set, otherwise the free rule-based **demo model** (clearly labelled). `ASSISTANT_MODEL` defaults to `claude-haiku-4-5`.

## Guardrails
Read-only tools bound to the session's card · personal data redacted before the LLM · answers must cite sources given in the request · every dollar amount must appear in those sources · at most 5 model rounds. See ADR 0012.

## Eval
```bash
make eval                                   # free (demo model): pipeline + retrieval recall gate
make eval-claude                            # prints the cost estimate
make eval-claude ARGS=--confirm-cost        # runs against Claude (costs money)
```
Results: [docs/eval-results.md](../../docs/eval-results.md).

## Test
```bash
make test-assistant
```
