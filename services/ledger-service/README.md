# ledger-service

Double-entry ledger for CardFlow (Java 25, Spring Boot 4.1, PostgreSQL 18, Flyway).

## Run
```bash
make up                     # from repo root: Postgres, Kafka and this service
open http://localhost:8081/swagger-ui.html
```
Or run it from your IDE against Compose Postgres, with the password from your `.env`:
`DB_PASSWORD=... ./mvnw spring-boot:run`

## API
| Method | Path | Description |
|---|---|---|
| POST | `/accounts` | Create an account (`ASSET`, `LIABILITY`, `REVENUE`, `EXPENSE`) |
| GET | `/accounts/{id}` | Account details |
| GET | `/accounts/{id}/balance` | Derived balance plus debit/credit totals |
| GET | `/accounts/{id}/transactions?page=0&size=20` | This account's entries, newest first (max size 100) |
| POST | `/transactions` | Post a balanced transaction |
| GET | `/transactions/{id}` | A transaction with all its entries |

Errors are RFC 9457 `application/problem+json`, with no stack traces.

## Test
```bash
./mvnw verify               # unit + Testcontainers integration tests (needs Docker)
```

## Layout
```
account/       Account entity, API, service
transaction/   posting, validation, balances, history
common/        error handling, pagination
db/migration/  Flyway SQL (schema + ledger safety triggers)
```
