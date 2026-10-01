# Progress

## Current phase: 0, Repo and infrastructure setup

### Session log

#### 2026-10-01
- Created repo `jasonlam11/cardflow` (private) with PLAN.md
- Phase 0 branch `phase-0-setup`: folder skeleton, Postgres 18 + pgvector, Kafka 4.3.1 (KRaft), Makefile, CI workflow, Dependabot, PR template, README diagram, ADRs 0001–0002
- Installed Docker Desktop
- Verified locally: `make up` → postgres + kafka healthy; Kafka produce/consume round trip OK; PostgreSQL 18.6
- CI green on PR #1 (stack boots healthy in 27s)
- Per-service databases and roles (init script, written together). Verified 3×3 access matrix: each role connects only to its own DB; pgvector 0.8.6 enabled in `assistant`

### Next
- [ ] Merge PR #1
- [ ] Install Java 25 LTS before Phase 1

### Open issues
- None yet

## Metrics (real, measured numbers only)

| Metric | Value | Measured |
|---|---|---|
| Services | 0 | |
| Total tests | 0 | |
| p95 authorization latency | – | |
| Throughput (req/s) | – | |
| Duplicate postings under retry/failure tests | – | |
| Fraud model precision / recall / PR-AUC | – | |
| Assistant accuracy / refusal / citation / injection blocked | – | |
| Deploy time push → live | – | |
| Monthly AWS cost | – | |
