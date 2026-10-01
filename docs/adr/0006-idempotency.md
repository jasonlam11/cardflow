# 6. Idempotency keys on the API, idempotent consumer on Kafka

- **Status:** Accepted
- **Date:** 2026-10-01

## Context
Networks fail in ambiguous ways. A client that times out can't know whether its charge went through, so it retries. Kafka delivers at least once, so consumers see duplicates. In payments, processing a duplicate means charging someone twice.

## Decision
**API side (authorization-service):**
- `POST /authorizations` requires an `Idempotency-Key` header. The key is stored with a SHA-256 hash of the request's fields, under a `UNIQUE` constraint.
- Same key and same request: return the stored result (same status, same body, `Idempotent-Replayed: true`). Declines are replayed too.
- Same key, different request: `422`. That's a client bug, and guessing would be worse.
- Two identical requests arriving at once: both try to insert; the unique constraint lets exactly one win, and the loser returns the winner's result.
- The response is rebuilt from the stored row rather than stored separately, so it can't drift.

**Consumer side (ledger-service):**
- Each event carries an `eventId`. In the same DB transaction as the posting, the consumer inserts it into `processed_events` (primary key). A duplicate inserts nothing and is skipped.
- Kafka offsets are committed only after the DB commit.

## Consequences
- Verified: 10 simultaneous copies of one request create 1 authorization; the same event delivered 3 times posts once; a 1,000-charge simulation with 44 client retries produced 926 approvals, 926 ledger postings and 0 duplicates.
- Keys are kept forever for now. Real systems expire them (e.g. after 24 h); a cleanup job is a later improvement.
- `processed_events` grows with every event; it could be pruned to a retention window, as long as Kafka's own retention is shorter than that window.
