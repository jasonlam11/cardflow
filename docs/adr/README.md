# Architecture decision records

Each ADR records one significant decision: the context, what we chose, the alternatives, and the consequences. They are never edited after acceptance. A changed decision gets a new ADR that supersedes the old one ([ADR 0001](0001-record-architecture-decisions.md)).

**Short on time?** Read these four first. They are the core of how money moves safely: **0005 → 0006 → 0003 → 0007**.

| # | Decision | Phase |
|---|---|---|
| [0001](0001-record-architecture-decisions.md) | Record architecture decisions | 0 |
| [0002](0002-monorepo.md) | Use a single monorepo for all services | 0 |
| [0003](0003-double-entry-ledger.md) | Double-entry ledger with derived balances | 1 |
| [0004](0004-database-per-service.md) | Database per service | 1 |
| [0005](0005-transactional-outbox.md) | Transactional outbox for publishing events | 2 |
| [0006](0006-idempotency.md) | Idempotency keys on the API, idempotent consumer on Kafka | 2 |
| [0007](0007-circuit-breaker-fallback.md) | Circuit breaker and rule-based fallback for fraud scoring | 3 |
| [0008](0008-shared-feature-code.md) | One feature implementation for training and serving | 3 |
| [0009](0009-dashboard-bff-and-admin-key.md) | Dashboard as a backend-for-frontend, with an admin API key | 4 |
| [0010](0010-human-review-audit-trail.md) | Human review as a single guarded state transition with an append-only audit trail | 4 |
| [0011](0011-rag-design.md) | Retrieval-augmented generation: local embeddings, pgvector, section chunks | 5 |
| [0012](0012-llm-interface-and-guardrails.md) | Provider-agnostic LLM interface with deterministic guardrails around it | 5 |
| [0013](0013-observability.md) | Prometheus metrics and structured JSON logs, with dashboards deferred | 6 |
