# Progress

## Current phase: 4, Dashboard + human review

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

### Next
- [ ] CI green on Phase 4 PR, then merge
- [ ] Explain-back questions for Phases 2–4 (YOUR TURN)
- [ ] Phase 5 plan: AI assistant (RAG over benefits docs + read-only ledger tools, guardrails, eval suite)
- [ ] Free more disk space before Phase 5 (Mac had ~7 GB free)
- [x] PR #1 merged (Phase 0 complete)
- [x] Java 25.0.4.1 LTS installed (Homebrew, native arm64) and set as default in ~/.zprofile

### Open issues
- ledger-service image is 567 MB; shrink in Phase 6 (jlink or a smaller base image)
- Balance reads SUM all entries; add snapshots if volume ever requires it (ADR 0003)
- Idempotency keys, processed_events and published outbox rows are never cleaned up (ADR 0005/0006)
- Simulator's `make simulate` rebuilds images on first run, which can take a while
- Fraud model weakest on amount spikes (46% offline, 23% live recall)
- fraud-service `card_activity` never pruned; fallback rules duplicated in Java and Python
- fraud-service image is 596 MB (numpy + xgboost); slim down in Phase 6
- Analyst identity is self-declared; the admin key authenticates the dashboard, not the person (ADR 0009)
- Review queue lists the 50 oldest; no search beyond deep links and transaction filters

## Metrics (real, measured numbers only)

| Metric | Value | Measured |
|---|---|---|
| Services | 4 (ledger, authorization, fraud, dashboard) + simulator | 2026-10-02 |
| Total tests | 175: ledger 46, authorization 55, fraud 27, simulator 16, dashboard unit 22, Playwright 6, e2e 4 (incl. 1 quality gate) | 2026-10-02 |
| p95 authorization latency | – (single requests ~15 ms incl. fraud call ~5 ms; proper load test in Phase 6) | 2026-10-02 |
| Throughput (req/s) | – (50/s simulated without errors; real load test in Phase 6) | |
| Duplicate postings under retry/failure tests | **0** (1,000-charge sim with 44 retries: 926 approved = 926 posted; e2e Kafka outage: 0 lost) | 2026-10-01 |
| Outbox publish lag | p50 284 ms, p95 531 ms (500 ms poll) | 2026-10-01 |
| Fraud model, offline test (days 77–89) | PR-AUC **0.918**; review-or-decline 72.3% precision / 89.8% recall; auto-decline 87.1% precision; rules fallback 16.6% recall | 2026-10-02 |
| Fraud model, live replay through the stack | 20,041 authorizations in 108.5 s; flagged 69.9% precision / 86.3% recall; auto-decline 87.3% precision; 0.15% of legit charges declined | 2026-10-02 |
| Human review: Approve click -> ledger posted | 0.45-1.4 s (Playwright, 4 runs) | 2026-10-02 |
| Fraud scoring latency | in-process p50 0.39 ms / p95 0.46 ms; HTTP call from authorization ~5 ms (was ~47 ms before the Nagle fix) | 2026-10-02 |
| Assistant accuracy / refusal / citation / injection blocked | – | |
| Deploy time push → live | – | |
| Monthly AWS cost | – | |
