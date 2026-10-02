# End-to-end tests

Black-box tests of the whole stack: simulator-style traffic → authorization-service → outbox → Kafka → ledger-service.

```bash
make up && make e2e                       # against your running stack
E2E_START_STACK=1 make e2e                # Testcontainers starts/stops the stack (CI)
```

| Test | Proves |
|---|---|
| `test_charges_flow_to_ledger_exactly_once` | Every approval posts exactly once (with client retries); balance matches |
| `test_kafka_outage_loses_no_transactions` | With Kafka stopped, charges still authorize and wait in the outbox; after restart the ledger catches up with nothing lost |
| `test_fraud_burst_is_held_or_declined_by_the_model` | After normal history, a burst of online gift-card purchases is held for review or declined, with reason codes |
| `test_authorizations_keep_working_when_fraud_service_is_down` | fraud-service stopped: charges use the rules fallback (never auto-decline); the model takes over again after restart |

Charges in the delivery tests are spaced hours apart via `occurredAt`, so the fraud model (correctly) doesn't treat them as a velocity burst.
