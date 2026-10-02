# Performance

Load tests with [k6](https://k6.io) against the full Docker Compose stack. Raw k6 summaries and pipeline reports are in [`perf/results/`](../perf/results/).

> **Machine:** one Apple M2 laptop (8 cores, 8 GB RAM). Docker Desktop gets 8 CPUs and 3.8 GB, shared by **all 7 services, Postgres, Kafka and k6 itself**. These are a baseline for this setup, not production capacity: every service competes for the same CPU, and there is no horizontal scaling.

## How the tests work
- `perf/steady.js`: a **constant arrival rate** (k6 sends N requests per second whatever the response time, so slow responses can't hide by lowering the load) across 500 synthetic cards. Each card's charges are timestamped 6 hours apart, so the fraud model sees normal spending rather than an artificial burst. **10% of requests are retried with the same idempotency key** and must return the identical result.
- `perf/breakpoint.js`: ramps 50 → 600 req/s and stops when p95 > 200 ms or errors > 1%.
- `perf/report.py`: reads both databases afterwards: outbox publish delay, **approval → ledger posting** delay, anything left unpublished, and **duplicate ledger postings**.
- Fraud scoring is part of every authorization, so each run also records **what share of charges the ML model scored** versus the circuit-breaker fallback.

Run them: `make perf RATE=200 DURATION=2m`, `make perf-breakpoint`.

## Headline results

| Scenario | p50 | p95 | p99 | Errors | Notes |
|---|---|---|---|---|---|
| **200 req/s for 2 min** | **4.4 ms** | **28.9 ms** | 141.9 ms | **0** | 23,986 authorizations; 91.6% ML-scored |
| 100 req/s, right after a restart | 4.7 ms | 15.3 ms | 47.8 ms | 0 | **99.9% ML-scored** |
| 100 req/s, **fraud-service stopped** | 2.2 ms | 6.4 ms | 24.0 ms | 0 | 100% rules fallback; no outage |
| Ramp to 600 req/s | 3.7 ms | 203.5 ms* | 495 ms | 0 | *crossed the 200 ms limit 13 s into the 600 req/s hold |

**Event pipeline at 200 req/s:** every approval reached the ledger: **0 unpublished, 0 duplicates**. Outbox publish p50 261 ms / p95 492 ms; **approval → ledger p50 321 ms / p95 509 ms** (most of that is the relay's 500 ms polling interval).

**Idempotency under load:** across every run, all client retries returned the original result, and **duplicate ledger postings were 0**.

## Capacity with the fraud model in the loop

| Rate (1 min each) | p50 | p95 | p99 | ML-scored |
|---|---|---|---|---|
| 100 req/s | 4.7 ms | 15.3 ms | 47.8 ms | 99.9% |
| 200 req/s | 4.4 ms | 39.3 ms | 111.2 ms | 100% |
| 300 req/s | 4.3 ms | 104.0 ms | 317.7 ms | 62% |
| 400 req/s | 3.7 ms | 142.5 ms | 522.8 ms | 34% |

Above ~200 req/s, fraud-service saturates on this machine. Authorization doesn't fail: calls that exceed the 300 ms timeout open the circuit breaker, and those charges are scored by the conservative rules (which never auto-decline). That's the designed behaviour (ADR 0007), but it means **"authorizations per second with ML scoring" on this laptop is ~200**, not the 400–600 the HTTP layer alone can carry. The `cardflow_authorizations_total{scored_by=...}` metric makes this visible in production.

## Bottlenecks found and fixed

The first runs looked fine on HTTP latency but hid three problems. Each was found from the measurements, fixed, and re-measured on the same test:

| # | Symptom | Cause | Fix | Before → after |
|---|---|---|---|---|
| 1 | At 600 req/s, 24,490 events left in the outbox; approval → ledger p95 **57.2 s** | Relay published one 100-row batch per 500 ms tick: a hard cap of **200 events/s** | Drain full batches back to back (bounded), 500-row batches | 0 left over; p95 **5.5 s** |
| 2 | Ledger still behind: p95 5.5 s | One consumer thread for a 3-partition topic | `concurrency: 3` (events keyed by card, so per-card order holds) | p95 **0.85 s** |
| 3 | ~80% of high-load charges scored by rules; even 100 req/s had 18% fallback | fraud-service ran one Python process (a Phase 3 fix for a Nagle stall) and cold workers timed out after restarts | 3 uvicorn workers on httptools + uvloop (they set `TCP_NODELAY`, so the stall doesn't return: 0.5 ms median), plus per-worker warm-up | 200 req/s **100%** ML-scored; post-restart 100 req/s **99.9%** (was 82%) |

Lessons:
- **Measure the whole pipeline, not just the HTTP response.** The authorization API stayed fast while events fell a minute behind.
- **Watch what the fallback hides.** Zero errors at 400 req/s looked great until the `scored_by` breakdown showed the model was mostly bypassed.
- **Fix, then re-run the identical test.** Every number above comes from the same script and parameters before and after.

## What would scale it further
- More fraud-service replicas (or a compiled model server); it's the first thing to saturate.
- Separate machines per service, so Kafka, Postgres and the JVMs stop competing for the same 8 cores.
- Event-driven publishing (Debezium reading the write-ahead log) instead of polling, removing the ~250 ms average relay delay.
- Connection-pool and JVM tuning, done with these tests and the Prometheus metrics, not by guesswork.
