# Progress

## Current phase: 2, Authorization service + events

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

### Next
- [ ] CI green on Phase 2 PR, then merge
- [ ] Phase 2 explain-back questions (YOUR TURN)
- [ ] Phase 3 plan: fraud-service (XGBoost + SHAP) and Resilience4j
- [x] PR #1 merged (Phase 0 complete)
- [x] Java 25.0.4.1 LTS installed (Homebrew, native arm64) and set as default in ~/.zprofile

### Open issues
- ledger-service image is 567 MB; shrink in Phase 6 (jlink or a smaller base image)
- Balance reads SUM all entries; add snapshots if volume ever requires it (ADR 0003)
- Idempotency keys, processed_events and published outbox rows are never cleaned up (ADR 0005/0006)
- Simulator's `make simulate` rebuilds images on first run, which can take a while

## Metrics (real, measured numbers only)

| Metric | Value | Measured |
|---|---|---|
| Services | 2 (ledger, authorization) + simulator | 2026-10-01 |
| Total tests | 88: ledger 46, authorization 32, simulator 8, e2e 2 | 2026-10-01 |
| p95 authorization latency | – | |
| Throughput (req/s) | – (50/s simulated without errors; real load test in Phase 6) | |
| Duplicate postings under retry/failure tests | **0** (1,000-charge sim with 44 retries: 926 approved = 926 posted; e2e Kafka outage: 0 lost) | 2026-10-01 |
| Outbox publish lag | p50 284 ms, p95 531 ms (500 ms poll) | 2026-10-01 |
| Fraud model precision / recall / PR-AUC | – | |
| Assistant accuracy / refusal / citation / injection blocked | – | |
| Deploy time push → live | – | |
| Monthly AWS cost | – | |
