# 4. Database per service

- **Status:** Accepted
- **Date:** 2026-10-01

## Context
Several services need persistent data. If they share tables, they become coupled: a schema change in one breaks another, and one service's bug or breach exposes all the data.

## Decision
Each service owns its own database and login role (`ledger_svc` → `ledger`, and so on). `CONNECT` is revoked from `PUBLIC`, so a role can only reach its own database. Services share data only through REST APIs and Kafka events. Locally all the databases live in **one** Postgres server, to save resources.

## Consequences
- Schemas evolve independently, each through its own Flyway migrations.
- Cross-service queries (joins) are impossible by design. Data another service needs is sent to it in events, or fetched through an API.
- Consistency across services is eventual, not immediate. The outbox pattern (Phase 2) keeps it reliable.
- One shared server is a single point of failure locally. That's acceptable for a demo, and the separate logins mean the databases could move to separate servers without code changes.
