# 5. Transactional outbox for publishing events

- **Status:** Accepted
- **Date:** 2026-10-01

## Context
When authorization-service approves a charge it must (1) save the authorization in Postgres and (2) tell the ledger through Kafka. These are two different systems, so one can succeed while the other fails:
- Save, then publish: the app crashes or Kafka is down after the commit, and the event is lost. The ledger never records a real charge.
- Publish, then save: the save fails after the event went out, and the ledger records a charge that doesn't exist.

## Decision
Write the event to an `outbox_events` table **in the same database transaction** as the authorization. A separate relay polls unpublished rows (`FOR UPDATE SKIP LOCKED`), sends them to Kafka with `acks=all`, waits for confirmation, and then sets `published_at`. On failure it records the error and retries on the next poll, in order.

## Alternatives considered
- **Publish directly to Kafka** (dual write): loses or invents events on failure, as above.
- **Two-phase commit (XA)** across Postgres and Kafka: Kafka doesn't take part in XA, and 2PC is slow and fragile anyway.
- **Change data capture (Debezium)** reading the Postgres write-ahead log: the strongest option at scale with no polling, but it means running Kafka Connect and Debezium. Too much infrastructure for this project; the outbox table is the same pattern and could be read by Debezium later without changing the service.

## Consequences
- An event exists **if and only if** its authorization committed.
- Kafka outages don't affect authorizations; events queue in the table. Proven by an e2e test that stops Kafka.
- Delivery is **at-least-once**: a crash between Kafka's ack and marking the row sent causes a re-send. Consumers must deduplicate (ADR 0006).
- Adds publish latency: measured 284 ms at p50 and 531 ms at p95 with a 500 ms poll interval. Fine for a ledger, which doesn't need to update instantly.
- Published rows accumulate. A periodic cleanup of old published rows is a later improvement.
