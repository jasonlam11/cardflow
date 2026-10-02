# Progress

## Current phase: 6, polish (performance, observability, docs)

### Session log

#### 2026-10-01
- Created repo `jasonlam11/cardflow` (private) with PLAN.md
- Phase 0 branch `phase-0-setup`: folder skeleton, Postgres 18 + pgvector, Kafka 4.3.1 (KRaft), Makefile, CI workflow, Dependabot, PR template, README diagram, ADRs 0001–0002
- Installed Docker Desktop
- Verified locally: `make up` → postgres + kafka healthy; Kafka produce/consume round trip OK; PostgreSQL 18.6
- CI green on PR #1 (stack boots healthy in 27s)
- Per-service databases and roles (init script, written together). Verified 3×3 access matrix: each role connects only to its own DB; pgvector 0.8.6 enabled in `assistant`

- **Phase 1** (branch `phase-1-ledger-service`): Spring Boot 4.1.1 / Java 25 ledger-service
  - Flyway V1 schema; Postgres constraint triggers enforce balanced, single-currency, >= 2-entry transactions and append-only history
  - Endpoints: create/get account, post/get transaction, derived balance, paginated history; RFC 9457 errors
  - 40 tests (unit + Testcontainers Postgres 18.6), all passing locally
  - Dockerized (multi-stage, non-root) and running in Compose on :8081; end-to-end smoke test passed
  - CI job for ledger-service; Dependabot for Maven
  - ADR 0003 (double-entry), ADR 0004 (database per service)

- Phase 1 merged (PR #4)
- **Phase 2** (branch `phase-2-authorization`):
  - authorization-service: synthetic cards, idempotent `POST /authorizations` (key + request hash), `FOR UPDATE` row locking, decline reasons, transactional outbox + relay (`SKIP LOCKED`, `acks=all`)
  - ledger-service: Flyway V2, idempotent Kafka consumer (`processed_events`), find-or-create accounts by `external_ref`, retries + DLT
  - Correlation IDs across HTTP, logs and Kafka headers
  - `kafka-init` topic creation; authorization-service and simulator in Compose
  - Simulator v1 (Python, httpx), 8 tests
  - e2e tests (pytest): exactly-once posting under retries; Kafka outage loses nothing
  - CI: authorization-service, simulator and e2e workflows
  - ADR 0005 (outbox), ADR 0006 (idempotency); NOTES §9

- Phase 2 merged (PR #6) after two CI-only fixes: timestamp precision on Linux, and a race on the shared Maven build cache
- **Phase 3** (branch `phase-3-fraud`):
  - Simulator v2: cardholder profiles, legit noise, four injected fraud patterns; `dataset` and `replay` modes
  - fraud-service (Python 3.14, FastAPI, XGBoost): shared `compute_features()`, time-split training, model card, TreeSHAP reason codes, per-card history in its own Postgres DB
  - authorization-service: fraud scored before the transaction, Resilience4j circuit breaker + timeouts, conservative rules fallback, `PENDING_REVIEW` (holds credit, no ledger event), `FRAUD_SUSPECTED` declines
  - Found and fixed a ~40 ms per-call stall (uvicorn `--workers` + Nagle/delayed ACK)
  - ADR 0007 (circuit breaker), ADR 0008 (shared features); NOTES §10

- Phase 3 merged (PR #8); Dependabot PRs #5, #7 merged
- **Phase 4** (branch `phase-4-dashboard`):
  - authorization-service: review decisions (row lock, 409 on conflict, approve -> outbox event, reject -> credit released), append-only `review_decisions`, DB trigger allowing only PENDING_REVIEW -> APPROVED/DECLINED; admin listing/queue/stats endpoints behind `X-Admin-Api-Key`
  - ledger-service: account lookup by external reference
  - dashboard (Next.js 16): BFF route handlers, overview, transactions, review queue with SHAP reason bars and deep links, decisions audit trail, card page
  - Docker image (322 MB), compose service, CI (lint/typecheck/Vitest/build), Playwright in e2e
  - Freed disk space after the Mac filled up and Docker crashed (caches + Docker build cache)
  - ADR 0009 (BFF + admin key), ADR 0010 (review transition + audit trail); NOTES §11

- Phase 4 merged (PR #9)
- **Phase 5** (branch `phase-5-assistant`):
  - assistant-service (Python 3.14, FastAPI): 8 fictional benefits docs (36 sections), local embeddings (bge-small), pgvector; 4 read-only ledger tools bound to the session card; guardrails (redaction, citation and amount checks, round cap); provider-agnostic LLM layer: Claude via the official SDK (Haiku 4.5 default), DemoLLM when no key
  - ledger-service V3: merchant + MCC on transactions; spending-by-category and filtered history endpoints
  - 36-question eval (benefits, account, refuse, injection, PII) with a free demo run in CI and a paid real-model run behind --confirm-cost
  - Dashboard /assistant chat page with source chips and guardrail notes; Playwright chat tests
  - ADR 0011 (RAG), ADR 0012 (LLM interface + guardrails); NOTES §12

- Phase 5 merged (PR #14); Dependabot #10 merged, #11–#13 closed
- **Phase 6** (branch `phase-6-polish`):
  - Prometheus metrics in all services (business metrics incl. `scored_by`, outbox backlog/age, ledger posted/duplicate/dead-lettered, LLM tokens/cost); ECS JSON logs with correlation IDs in Compose
  - k6 steady (constant arrival rate, 10% idempotent retries) and breakpoint tests; `perf/report.py` pipeline report
  - Found and fixed 3 bottlenecks: outbox relay capped at 200 events/s (drain loop), single ledger consumer (concurrency 3), fraud-service saturation/cold workers (3 httptools+uvloop workers, warm-up)
  - Security CI: npm audit + pip-audit on PRs; Trivy image scan on main/weekly; baseline 0 known vulnerabilities
  - Alpine JRE images (611/619 → 469 MB)
  - `make demo` one-command populated stack; demo GIF; README rewrite; ADR index; ADR 0013 (observability); NOTES §13
  - Fresh-clone stranger test: fixed a repeat-`make demo` idempotency-key collision and a flaky cold pip install
  - Freed 6.6 GB (Docker build cache) when disk hit 1.3 GB free

### Next
- [ ] CI green on Phase 6 PR, then merge
- [ ] Phase 7 plan: AWS with Terraform (cost estimate and approval before creating anything)
- [ ] Add an Anthropic API key and run `make eval-claude ARGS=--confirm-cost` for real-model metrics (~$0.50 on Haiku 4.5)
- [ ] Explain-back questions for Phases 2–5 (YOUR TURN)
- [x] PR #1 merged (Phase 0 complete)
- [x] Java 25.0.4.1 LTS installed (Homebrew, native arm64) and set as default in ~/.zprofile

### Open issues
- Balance reads SUM all entries; add snapshots if volume ever requires it (ADR 0003)
- Idempotency keys, processed_events and published outbox rows are never cleaned up (ADR 0005/0006)
- Simulator's `make simulate` rebuilds images on first run, which can take a while
- Fraud model weakest on amount spikes (46% offline, 23% live recall)
- fraud-service `card_activity` never pruned; fallback rules duplicated in Java and Python
- fraud-service image is ~600 MB (numpy + xgboost); assistant 828 MB (embedding model + ONNX runtime)
- Analyst identity is self-declared; the admin key authenticates the dashboard, not the person (ADR 0009)
- Review queue lists the 50 oldest; no search beyond deep links and transaction filters
- Assistant model-quality metrics not measured yet (no API key); demo-mode numbers measure the pipeline only
- Haiku 4.5 won't prompt-cache our ~2k-token prefix (needs 4,096+)
- One Python e2e failure right after a stack restart (not reproduced in 3 runs; likely cold fraud workers, now warmed up at startup)
- Disk is tight (~7 GB free after pruning); Docker build cache regrows with every build
- Above ~200 req/s on the laptop, fraud-service saturates and charges fall back to rules (by design; visible in metrics)
- No Grafana/Prometheus server yet (deferred to Phase 7)

## Metrics (real, measured numbers only)

| Metric | Value | Measured |
|---|---|---|
| Services | 5 (ledger, authorization, fraud, assistant, dashboard) + simulator | 2026-10-02 |
| Total tests | **232**, all passing: ledger 51, authorization 59, fraud 28, assistant 38, simulator 17, dashboard unit 26, Playwright 9, e2e 4 | 2026-10-02 |
| p95 authorization latency | **28.9 ms** at 200 req/s for 2 min (p50 4.4 ms, p99 141.9 ms, 0 errors, 23,986 authorizations, 91.6% ML-scored); 15.3 ms at 100 req/s after a restart (99.9% ML-scored) | 2026-10-02 |
| Throughput (req/s) | **200 req/s** with ML scoring (100% model-scored); HTTP layer to ~600 req/s before p95 > 200 ms (rules fallback above ~200) | 2026-10-02 |
| Approval → ledger posting at 200 req/s | p50 321 ms / p95 509 ms; 0 unpublished, 0 duplicates | 2026-10-02 |
| Bottleneck fixes at 600 req/s | outbox left unpublished 24,490 → 0; approval → ledger p95 57.2 s → 0.85 s | 2026-10-02 |
| fraud-service stopped at 100 req/s | p95 6.4 ms, 0 errors (rules fallback) | 2026-10-02 |
| Java image size | 611 / 619 MB → 469 MB (Alpine JRE) | 2026-10-02 |
| Known vulnerabilities (npm audit, pip-audit, Trivy CRITICAL) | 0 | 2026-10-02 |
| `make demo` populate time (built images) | 38 s: 1,348 charges, 20 awaiting review (replayed days: 437 approved, 64 fraud declines, 10 review) | 2026-10-02 |
| Fresh clone → running demo (cold build) | ~8–10 min on M2 laptop; stranger test found and fixed 2 snags | 2026-10-02 |
| Duplicate postings under retry/failure tests | **0** (1,000-charge sim with 44 retries: 926 approved = 926 posted; e2e Kafka outage: 0 lost) | 2026-10-01 |
| Outbox publish lag | p50 284 ms, p95 531 ms (500 ms poll) | 2026-10-01 |
| Fraud model, offline test (days 77–89) | PR-AUC **0.918**; review-or-decline 72.3% precision / 89.8% recall; auto-decline 87.1% precision; rules fallback 16.6% recall | 2026-10-02 |
| Fraud model, live replay through the stack | 20,041 authorizations in 108.5 s; flagged 69.9% precision / 86.3% recall; auto-decline 87.3% precision; 0.15% of legit charges declined | 2026-10-02 |
| Human review: Approve click -> ledger posted | 0.45-1.4 s (Playwright, 4 runs) | 2026-10-02 |
| Fraud scoring latency | in-process p50 0.39 ms / p95 0.46 ms; HTTP call from authorization ~5 ms (was ~47 ms before the Nagle fix) | 2026-10-02 |
| Assistant eval (36 questions) | Demo mode: retrieval recall@4 100%, citation rate 100%, injection/PII resistance 100%, $0. Real-model metrics pending an API key | 2026-10-02 |
| Deploy time push → live | – | |
| Monthly AWS cost | – | |
