# 7. Circuit breaker and rule-based fallback for fraud scoring

- **Status:** Accepted
- **Date:** 2026-10-02

## Context
Every authorization asks fraud-service for a score. If fraud-service is slow or down, a naive call blocks each authorization for the full timeout, threads pile up, and one sick dependency takes down authorization too (a **cascading failure**). But skipping fraud checks entirely would approve fraud.

## Decision
- **Strict timeouts:** 200 ms to connect, 300 ms to read. The worst case adds about 0.5 s, never more.
- **Circuit breaker (Resilience4j):** count-based window of the last 20 calls; once at least 10 calls have been made and 50% or more failed, the circuit **opens** for 10 s and calls fail instantly, without touching the network. Then 3 trial calls decide whether to close it again.
- **Fallback (`RuleBasedFraudScorer`):** simple rules using only the request: route charges of $2,000 or more, or $500 or more in a high-risk category, to **human review**. The fallback **never auto-declines**: without history it can't tell fraud from a big legitimate purchase, so a human decides.
- **Score before the transaction:** the fraud call happens before the DB transaction, so the card's row lock is never held during a network call.
- Every authorization records `scored_by` (`MODEL`, `RULES_FALLBACK`, `NOT_SCORED`), so fallback use is visible and auditable.
- authorization-service has **no startup dependency** on fraud-service in Compose.

## Alternatives considered
- **Fail closed** (decline everything when fraud is down): safe against fraud, but a fraud-service outage becomes a total payments outage.
- **Fail open** (approve everything): no customer impact, but fraudsters only need to wait for an outage.
- **Retries:** add load to a struggling service and multiply latency; the breaker is the better tool for a dependency that's down.
- **Asynchronous scoring** (approve, then score later): too late to stop the money moving.
- **Spring Cloud Circuit Breaker or Resilience4j annotations:** less code, but more indirection to learn; the programmatic API shows exactly what happens.

## Consequences
- Proven by tests: errors and slow responses fall back within the timeout; after 10 failures the circuit opens and the next calls never reach fraud-service; e2e shows authorizations keep working with fraud-service stopped.
- The fallback catches far less fraud than the model (see the model card), and pushes more work to analysts, so outages must be short and visible. Phase 6 adds metrics and alerting on `scored_by`.
