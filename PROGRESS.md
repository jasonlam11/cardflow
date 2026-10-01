# Progress

## Current phase: 1, Ledger service

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

### Next
- [ ] CI green on Phase 1 PR, then merge
- [ ] Phase 2 plan: authorization-service + outbox + Kafka
- [x] PR #1 merged (Phase 0 complete)
- [x] Java 25.0.4.1 LTS installed (Homebrew, native arm64) and set as default in ~/.zprofile

### Open issues
- ledger-service image is 567 MB; shrink in Phase 6 (jlink or a smaller base image)
- Balance reads SUM all entries; add snapshots if volume ever requires it (ADR 0003)

## Metrics (real, measured numbers only)

| Metric | Value | Measured |
|---|---|---|
| Services | 1 (ledger-service) | 2026-10-01 |
| Total tests | 40 (ledger-service) | 2026-10-01 |
| p95 authorization latency | – | |
| Throughput (req/s) | – | |
| Duplicate postings under retry/failure tests | – | |
| Fraud model precision / recall / PR-AUC | – | |
| Assistant accuracy / refusal / citation / injection blocked | – | |
| Deploy time push → live | – | |
| Monthly AWS cost | – | |
