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
