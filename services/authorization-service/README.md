# authorization-service

Approves or declines card charges (Java 25, Spring Boot 4.1, PostgreSQL 18, Kafka).

## Run
```bash
make up                    # from repo root
open http://localhost:8082/swagger-ui.html
```

## API
| Method | Path | Description |
|---|---|---|
| POST | `/cards` | Create a synthetic card (random token + last 4 only) |
| GET | `/cards/{id}` | Card, limit and available credit |
| PUT | `/cards/{id}/status` | `ACTIVE` / `FROZEN` / `CLOSED` |
| POST | `/authorizations` | Charge a card. **Requires `Idempotency-Key`.** 201 approved, 200 declined (`CARD_NOT_FOUND`, `CARD_NOT_ACTIVE`, `CURRENCY_MISMATCH`, `INSUFFICIENT_CREDIT`), 422 if a key is reused with a different body |
| GET | `/authorizations/{id}` | One authorization |

Send `X-Correlation-Id` to trace a request through logs and events.

## How a charge is processed
One DB transaction: lock the card row (`SELECT ... FOR UPDATE`) → apply `AuthorizationRules` → insert the authorization → if approved, insert a `transaction.authorized` event into `outbox_events`. `OutboxRelay` publishes those rows to Kafka every 500 ms. See ADR 0005 and ADR 0006.

## Test
```bash
./mvnw verify              # unit + Testcontainers (Postgres + Kafka); needs Docker
```
