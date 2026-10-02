# 13. Prometheus metrics and structured JSON logs, with dashboards deferred

- **Status:** Accepted
- **Date:** 2026-10-02

## Context
Before Phase 6 the only ways to see what the system was doing were reading logs and querying databases by hand. Load testing showed this wasn't enough. The authorization API stayed fast while events fell a minute behind in the outbox, and the fraud model was quietly bypassed for most charges once the circuit breaker opened. Neither problem shows up in HTTP latency. They show up only if you measure the **pipeline** and the **fallback**.

## Decision
- **Metrics in the Prometheus format** on every service. The Java services use Micrometer at `/actuator/prometheus`; the Python services use `prometheus-client` at `/metrics`. Prometheus is the de facto standard, every monitoring stack can read it, and AWS has a managed version.
- **Business metrics, not just technical ones:**
  - `cardflow_authorizations_total{status,fraud_band,scored_by}`: the `scored_by` label is how you spot a silent fallback.
  - `cardflow_fraud_score` timer `{outcome=model|fallback|circuit_open}` and `cardflow_fraud_circuit_open` gauge.
  - `cardflow_outbox_backlog` and `cardflow_outbox_oldest_age_seconds`, read from the database at scrape time, so they are accurate even across restarts.
  - `cardflow_ledger_events_total{result=posted|duplicate}` and `cardflow_ledger_events_dead_lettered_total`.
  - `cardflow_reviews_total{decision}`.
  - fraud-service score latency and band counts; assistant chats by outcome, LLM rounds, tokens and estimated cost.
- **HTTP latency histograms** (`percentiles-histogram`), so p95/p99 can be computed across instances, not just per process.
- **Structured JSON logs** (Spring Boot's built-in ECS format, switched on with `LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs` in Compose). Each line carries the `correlationId` from the HTTP request through the Kafka header to the ledger's log line, so one charge can be followed across services with a single search. Plain-text logs stay the default when running a service on its own, where they're easier to read.
- **No PII in logs or metric labels:** labels hold low-cardinality enums only. There are no card IDs, amounts or request bodies in labels.
- **No Grafana or Prometheus server in Compose (for now).** The endpoints exist and are tested; the k6 reports and `perf/report.py` covered Phase 6. A hosted dashboard comes with deployment (Phase 7), where CloudWatch or Amazon Managed Prometheus can scrape them.

## Alternatives considered
- **OpenTelemetry end to end (traces + metrics):** distributed tracing would replace the hand-rolled correlation ID and show per-hop timing. It means more moving parts (a collector and a trace backend), so it is the next step if a request path gets hard to debug.
- **Grafana + Prometheus in Compose now:** nice screenshots, but two more containers competing for RAM on an 8 GB laptop already running 7 services, for dashboards nobody watches locally.
- **Logging metrics and parsing them later:** brittle, and it can't give gauges like outbox backlog.

## Consequences
- The core metrics (authorization counts, replays, outbox backlog, fraud-score outcomes, ledger posted/duplicate, and the Python `/metrics` endpoints) are tested by reading the `MeterRegistry` or `/metrics` text before and after an action.
- The metrics that found the Phase 6 bottlenecks (fallback share, outbox backlog, ledger lag) are exactly the ones an alert should watch in production.
- Correlation IDs are only as good as their propagation: a new hop that forgets to forward the header breaks the trail. Tracing would make that automatic.
