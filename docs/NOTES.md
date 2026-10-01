# CardFlow Notes

A running guide to how CardFlow is built and **why**. It's updated as each phase lands. Read it before an interview, or when you come back to the project after a break.

- **PLAN.md**: what we're building (the goal).
- **PROGRESS.md**: what's done and the measured numbers (the status).
- **docs/adr/**: formal records of the big decisions (the official reasons).
- **This file**: how it all fits together, in plain language (the understanding).

---

## Contents
1. [The big picture](#1-the-big-picture)
2. [Repository structure](#2-repository-structure)
3. [How local infrastructure works](#3-how-local-infrastructure-works)
4. [How CI works](#4-how-ci-works)
5. [Decision log](#5-decision-log)
6. [Concepts glossary](#6-concepts-glossary)
7. [Common commands & troubleshooting](#7-common-commands--troubleshooting)
8. [ledger-service (Phase 1)](#8-ledger-service-phase-1)
9. [Authorization and events (Phase 2)](#9-authorization-and-events-phase-2)

---

## 1. The big picture

CardFlow imitates, in simplified form, what happens when you tap a credit card:

1. A **merchant** (our `simulator`) asks: "Can card X be charged $42.50?"
2. **authorization-service** checks that the account exists, the card is active, and there's enough available credit. It asks **fraud-service** for a risk score, then approves or declines.
3. The decision is published as an **event** on **Kafka**.
4. **ledger-service** reads that event and records the money movement using **double-entry bookkeeping**.
5. Suspicious-but-unclear transactions go to a **human review queue** in the **dashboard**.
6. An **AI assistant** answers questions such as "how much did I spend on travel?" using only real data and documents, with guardrails.

Why microservices? Each part has a different job, scales differently, and could be owned by a different team. That's how large payment companies are organized. The cost is extra complexity: networks fail, and data is spread out. Much of this project is about handling that well.

---

## 2. Repository structure

```
cardflow/
├── PLAN.md                    the project brief (source of truth for scope)
├── PROGRESS.md                status, session log, measured metrics
├── README.md                  2-minute overview for recruiters/engineers
├── Makefile                   short commands: make up / down / ps / logs / reset-db
├── docker-compose.yml         defines every container that runs locally
├── .env.example               template for local secrets (copied to .env, which is git-ignored)
├── .gitignore                 keeps secrets, build output, Terraform state out of git
├── .github/
│   ├── workflows/ci.yml       CI pipeline, runs on every PR and push to main
│   ├── dependabot.yml         weekly automated dependency-update PRs
│   └── pull_request_template.md
├── services/                  one folder per backend service, each with its own build
│   ├── authorization-service/ Java/Spring (Phase 2)
│   ├── ledger-service/        Java/Spring (Phase 1)
│   ├── fraud-service/         Python/FastAPI (Phase 3)
│   └── assistant-service/     Python/FastAPI (Phase 5)
├── simulator/                 Python traffic generator (Phase 2–3)
├── dashboard/                 Next.js UI (Phase 4)
├── infra/
│   ├── postgres/init/         SQL/shell run once when the DB is first created
│   └── terraform/             AWS infrastructure as code (Phase 7)
└── docs/
    ├── NOTES.md               ← you are here
    └── adr/                   Architecture Decision Records
```

**Rule:** services never import each other's code. They talk only through HTTP APIs and Kafka events. That's what keeps them independent.

---

## 3. How local infrastructure works

### Docker and Docker Compose
- A **container** is a lightweight, isolated process that ships with everything it needs (OS libraries, binaries). "Works on my machine" becomes "works on every machine."
- An **image** is the template a container is started from (for example `apache/kafka:4.3.1`). We **pin exact versions** so everyone gets the same thing. `latest` can change under you overnight.
- **Docker Compose** reads `docker-compose.yml` and starts several containers together on a private network, where they reach each other by name (`postgres`, `kafka`).
- A **volume** (`postgres-data`, `kafka-data`) is storage that survives container restarts. `make down` keeps your data; `docker compose down -v` deletes it.

### What `make up` does, step by step
1. If `.env` doesn't exist, it's copied from `.env.example`.
2. `docker compose up -d --wait` starts the containers in the background (`-d`) and **waits** until each one's healthcheck passes.
3. **Postgres**, on the very first start only (when its volume is empty), runs every script in `infra/postgres/init/`. Later starts skip this, which is why `make reset-db` exists.

### Healthchecks
A container that's "running" isn't necessarily "ready." Postgres takes a few seconds to accept connections, and Kafka longer. Each service defines a test command that Docker runs on a schedule:
- Postgres: `pg_isready`
- Kafka: `kafka-broker-api-versions.sh`, which asks the broker to respond

Only when the test passes is the container marked `healthy`. CI and (later) our own services wait for that.

### PostgreSQL: one server, separate databases per service
We run **one** Postgres container, which keeps things cheap and simple, but give each service **its own database and its own login**:

| Login role | Database | Can connect to |
|---|---|---|
| `ledger_svc` | `ledger` | only `ledger` |
| `authorization_svc` | `authorization` | only `authorization` |
| `assistant_svc` | `assistant` | only `assistant` |

How the lockout works (`infra/postgres/init/01-create-databases.sh`):
1. `CREATE ROLE ... LOGIN PASSWORD ...` creates a login. Postgres calls users "roles."
2. `CREATE DATABASE ... OWNER ...` creates the database and makes that role its owner.
3. `REVOKE CONNECT ... FROM PUBLIC` takes away the default permission. `PUBLIC` means "every role," and by default every role can connect to every database.
4. `GRANT CONNECT ... TO <owner>` gives access back to only the owner.

We tested every login against every database: each reaches only its own.

Other details:
- `"authorization"` is in double quotes because it's a **reserved word** in SQL.
- **pgvector** is installed by the *admin* inside the `assistant` database. Extensions are per-database and need high privileges, and we don't want a service login to have them (**least privilege**).
- The image is `pgvector/pgvector`, which is official Postgres with the vector extension added, so we don't need a second database server for AI search in Phase 5.
- **Postgres 18 detail:** data now lives under `/var/lib/postgresql`, not `/var/lib/postgresql/data`. Mounting the old path means losing data on every restart.
- **Known limitation:** passwords are pasted straight into SQL, so a password containing `'` would break the script (and is a mini SQL-injection lesson). That's fine for local dev values; in AWS, secrets come from SSM (Phase 7).

### Kafka in KRaft mode
- **Kafka** is a durable, ordered log of events. Producers append messages to a **topic**; consumers read them at their own pace and remember their position (the **offset**). If a consumer is down, messages wait for it.
- **KRaft** lets Kafka manage its own cluster metadata. Older Kafka needed a separate ZooKeeper cluster; with KRaft we run one container that is both **broker** (stores messages) and **controller** (manages the cluster).
- **Listeners** are the addresses Kafka accepts connections on:
  - `INTERNAL` = `kafka:29092`, for other containers on the Compose network
  - `EXTERNAL` = `localhost:9092`, for tools running on your Mac
  - `CONTROLLER` = `9093`, Kafka's own cluster coordination

  Kafka also tells clients which address to reconnect to (the "advertised" listener), so each kind of client needs the right one.
- **Auto topic creation is off.** Otherwise a typo like `transactons.authorized` would silently create a new, empty topic, and the bug would be very hard to find. We'll create topics explicitly in Phase 2.
- Replication factors are 1 because there's only one broker. In production this would be 3.

### Security defaults already in place
- Ports bind to `127.0.0.1`, so other devices on your Wi-Fi can't reach your database.
- Real secrets live in `.env`, which is git-ignored. Only `.env.example` with placeholder values is committed.

---

## 4. How CI works

`.github/workflows/ci.yml` runs on GitHub's servers for every PR and every push to `main`:
1. Checks out the code.
2. Copies `.env.example` to `.env`.
3. `docker compose config --quiet` checks that the compose file is valid.
4. `docker compose up --wait` boots the stack and **fails if any container doesn't become healthy** within 180 seconds.
5. Prints logs if anything failed, then tears everything down.

It runs with `permissions: contents: read`, the least privilege a CI job can have. `concurrency` cancels outdated runs when you push again quickly.

`.github/workflows/ledger-service.yml` (Phase 1) sets up Java 25 and runs `./mvnw verify`, which compiles the service and runs all unit and Testcontainers tests. It only runs when files under `services/ledger-service/` change (a **path filter**), so a docs-only PR doesn't wait for a Java build.

**Dependabot** opens weekly PRs when a GitHub Action or Docker image has a newer version. We review and merge those PRs ourselves; nothing updates on its own.

---

## 5. Decision log

Short version of every decision, newest at the bottom. The big ones also get a formal ADR.

| # | Decision | Why | Alternative considered | ADR |
|---|---|---|---|---|
| 1 | Record decisions as ADRs | Remember the *why* for interviews and reviews | Rely on memory and commit messages | [0001](adr/0001-record-architecture-decisions.md) |
| 2 | Monorepo | One `docker compose up`; changes that span services land in one PR | One repo per service (polyrepo) | [0002](adr/0002-monorepo.md) |
| 3 | Pin exact image versions (Postgres 18.6/pgvector 0.8.6, Kafka 4.3.1) | Reproducible builds; Dependabot handles upgrades | `latest` tags | – |
| 4 | One Postgres server, separate database and login per service | Enforces service boundaries at low cost | One container per DB (heavier); one shared DB (breaks isolation) | planned |
| 5 | pgvector inside Postgres for AI search | No extra infrastructure; SQL and vectors in one place | A dedicated vector DB (Pinecone, Qdrant) | planned (Phase 5) |
| 6 | Kafka in KRaft mode, single broker | Modern Kafka with no ZooKeeper; enough for local dev | ZooKeeper-based Kafka; RabbitMQ | – |
| 7 | Auto topic creation disabled | Typos fail loudly instead of creating ghost topics | Kafka default (on) | – |
| 8 | Bind ports to localhost only | Don't expose the DB or Kafka on your network | Docker default (0.0.0.0) | – |
| 9 | Merge commits (not squash) for phase PRs | Keeps the small commits visible in history | Squash merge | – |
| 10 | Java 25 LTS, native arm64 (Homebrew) | Latest LTS per PLAN.md; native build is faster than the Rosetta-translated JDK 21 | Java 21 LTS | – |
| 11 | Double-entry with balances derived, never stored | Balances can't drift; every number can be audited from history | A `balance` column updated on each posting | [0003](adr/0003-double-entry-ledger.md) |
| 12 | Enforce ledger rules in Postgres too (triggers), not just Java | Defense in depth: a bug or a manual SQL session still can't corrupt the ledger | Java-only validation | [0003](adr/0003-double-entry-ledger.md) |
| 13 | Append-only history; fix mistakes with offsetting transactions | That's how real ledgers keep an audit trail | Allow UPDATE/DELETE | [0003](adr/0003-double-entry-ledger.md) |
| 14 | Database per service | Independent schemas and blast radius | Shared database | [0004](adr/0004-database-per-service.md) |
| 15 | Spring Boot 4.1.1 + Java 25, Maven wrapper | Latest stable; the wrapper pins the Maven version for everyone | Gradle | – |
| 16 | Group code by feature (`account/`, `transaction/`) | Related code lives together | By layer (`controllers/`, `services/`) | – |
| 17 | UUIDs generated in Java, entities implement `Persistable` | The ID is known before insert; `Persistable` avoids a wasted SELECT and a merge bug | Database-generated IDs | – |
| 18 | No Lombok | No hidden generated code while learning; records cover most of it | Lombok | – |
| 19 | RFC 9457 Problem Details for errors | Standard, machine-readable, no stack traces | Custom error JSON | – |
| 20 | Account history returns this account's lines, not whole transactions | That's what a statement shows; avoids paginating over a join fetch | Return full transactions | – |
| 21 | Multi-stage Docker image, JRE runtime, non-root | Smaller attack surface; no build tools in production | Single-stage JDK image running as root | – |
| 22 | Transactional outbox + polling relay | Event exists iff the authorization committed; survives Kafka outages | Direct publish, 2PC, Debezium CDC | [0005](adr/0005-transactional-outbox.md) |
| 23 | Idempotency-Key header + request hash, unique constraint | Retries can't double-charge; concurrent duplicates resolved by the DB | Client-side dedupe only; no key | [0006](adr/0006-idempotency.md) |
| 24 | Idempotent consumer via `processed_events` in the same transaction | Kafka is at-least-once | Kafka exactly-once transactions (don't cover the DB write) | [0006](adr/0006-idempotency.md) |
| 25 | `SELECT ... FOR UPDATE` on the card row | Concurrent charges can't overspend a limit | Optimistic locking with retries; a stored balance | – |
| 26 | Declines return 200, approvals 201 | The request was valid; "no" is a normal business answer, not an error | 402/4xx for declines | – |
| 27 | Events keyed by card id, 3 partitions | Per-card ordering, parallelism across cards | Random key | – |
| 28 | Topics created by a `kafka-init` container | Infrastructure owns topics; services can't create them by typo | Auto-create; NewTopic beans in services | – |
| 29 | Dead-letter topic after 3 retries; poison messages skip retries | One bad event never blocks a partition, and nothing is silently dropped | Retry forever; log and skip | – |
| 30 | Services share the JSON event contract, not Java classes | No compile-time coupling between services | A shared "events" library | – |
| 31 | Correlation IDs via header → MDC → Kafka header | One ID traces a charge across both services' logs | No tracing until OpenTelemetry | – |

---

## 6. Concepts glossary

Plain-English definitions, added as each concept shows up.

| Term | Meaning | Where we use it |
|---|---|---|
| **Container / image** | An isolated packaged process / the template it starts from | Every service |
| **Docker Compose** | Starts multiple containers together from one YAML file | `docker-compose.yml` |
| **Volume** | Storage that outlives a container | Postgres and Kafka data |
| **Healthcheck** | A command that tells Docker whether a container is *ready*, not just running | Postgres, Kafka |
| **Role (Postgres)** | A user/login, or a group of permissions | One per service |
| **PUBLIC (Postgres)** | A pseudo-role meaning "everyone" | We revoke CONNECT from it |
| **Least privilege** | Give each component only the access it needs | DB logins, CI permissions, AWS IAM later |
| **Extension (Postgres)** | A plugin that adds features to a database | pgvector |
| **Kafka topic / offset** | A named event log / a consumer's position in it | Phase 2 |
| **KRaft** | Kafka's built-in cluster management (replaced ZooKeeper) | Kafka container |
| **Listener / advertised listener** | Where Kafka accepts connections / the address it tells clients to use | Kafka config |
| **CI** | Automated checks that run on every change | GitHub Actions |
| **ADR** | Architecture Decision Record: a short "we chose X because Y" doc | `docs/adr/` |
| **Heredoc** | Bash syntax (`<<EOSQL ... EOSQL`) that feeds a block of text into a command | Init script |
| **Double-entry** | Every transaction has equal debits and credits across at least two accounts | ledger-service |
| **Debit / credit** | The two sides of an entry. Not "plus/minus": which one increases a balance depends on the account type | ledger-service |
| **Normal side** | The side (debit or credit) that increases an account type's balance | `BalanceCalculator` |
| **Minor units** | The smallest currency unit (cents), stored as an integer | All amounts |
| **Spring Boot** | Framework that sets up a Java web app from sensible defaults | Java services |
| **Dependency injection (DI)** | Spring creates objects and passes them into constructors, instead of classes creating their own dependencies | Every controller/service |
| **Bean** | An object Spring creates and manages (`@Service`, `@Component`, `@RestController`) | Everywhere in Spring |
| **JPA / Hibernate** | Maps Java classes (`@Entity`) to tables; Hibernate is the implementation | Entities |
| **Repository** | Interface that Spring Data turns into database queries automatically | `AccountRepository` etc. |
| **@Transactional** | Runs a method in one database transaction: everything commits, or nothing does | `TransactionService.post` |
| **Flyway** | Runs versioned SQL migrations (`V1__...sql`) in order, once each | `db/migration/` |
| **Constraint trigger (deferred)** | A trigger that runs at COMMIT, so it can check rules spanning many rows | Balance check |
| **Bean Validation** | Annotations like `@NotNull`, `@Positive` that check input automatically | DTOs |
| **DTO** | Data Transfer Object: the shape of a request/response, kept separate from entities | `*Dtos.java` |
| **Testcontainers** | Starts real services (Postgres) in Docker for tests, then throws them away | Integration tests |
| **MockMvc** | Sends fake HTTP requests through the full Spring stack, without a real network | API tests |
| **Mockito** | Creates fake objects for unit tests | `AccountServiceTest` |
| **Problem Details (RFC 9457)** | Standard JSON format for API errors | `ApiExceptionHandler` |
| **Multi-stage build** | A Dockerfile that builds in one image and ships only the result in another | Dockerfile |
| **Idempotent** | Doing it twice has the same effect as doing it once | API and consumer |
| **Idempotency key** | A unique ID the client sends with a request so retries can be recognized | `POST /authorizations` |
| **Dual write** | Writing to two systems (DB + Kafka) separately; one can fail, leaving them inconsistent | What the outbox avoids |
| **Transactional outbox** | Save the event in the same DB transaction as the change, publish it afterwards | authorization-service |
| **At-least-once delivery** | Every message arrives, possibly more than once | Kafka, outbox relay |
| **Consumer group** | Consumers sharing a group split a topic's partitions; each message goes to one of them | `ledger-service` group |
| **Partition / key** | A topic is split into ordered partitions; messages with the same key go to the same one | Keyed by card id |
| **Offset commit** | A consumer recording "I've processed up to here" | After the DB commit |
| **Dead-letter topic (DLT)** | Where messages go after they can't be processed | `transactions.authorized.DLT` |
| **Pessimistic lock** | `SELECT ... FOR UPDATE`: lock the row so others wait | Card row during authorization |
| **SKIP LOCKED** | Skip rows another transaction has locked instead of waiting | Outbox relay |
| **MDC** | Mapped Diagnostic Context: per-thread values added to every log line | Correlation ID |
| **Persistence context / flush** | Hibernate holds changes in memory and sends them to the DB at flush | The saveAndFlush bug |

---

## 7. Common commands & troubleshooting

```bash
make up          # start everything, wait for healthy
make ps          # container status
make logs        # all logs; make logs s=kafka for one service
make down        # stop (keeps data)
make reset-db    # wipe Postgres and re-run init scripts
```

Connect to a database as a service user:
```bash
docker exec -it cardflow-postgres psql -U ledger_svc -d ledger
```

| Symptom | Cause | Fix |
|---|---|---|
| `unknown shorthand flag: 'd'` / `docker: unknown command: docker compose` | Docker Desktop not running or not finished first-time setup | Open Docker Desktop, wait for "Engine running" |
| `failed to connect to the docker API` | Docker engine stopped | Start Docker Desktop |
| Changed the init script but nothing happened | Init scripts only run on an empty volume | `make reset-db` |
| `permission denied for database` | Working as intended: wrong service login for that DB | Use the matching `*_svc` user |
| `java -version` still shows 21 | Terminal opened before `~/.zprofile` was updated | Open a new terminal or `source ~/.zprofile` |
| Code change not visible in the running service | The Compose image wasn't rebuilt | `make build` |
| Tests fail with "Could not find a valid Docker environment" | Testcontainers needs Docker running | Start Docker Desktop |
| `ledger-service` unhealthy in Compose | Usually a DB login issue | `make logs s=ledger-service` and check `LEDGER_DB_PASSWORD` in `.env` |
| Services never start; `kafka-init` keeps waiting | Kafka unhealthy | `make logs s=kafka` |
| Ledger balances lag behind approvals | Kafka down, or the outbox relay is failing | `SELECT COUNT(*) FROM outbox_events WHERE published_at IS NULL` in the `authorization` DB; check `last_error` |
| Image builds take forever | First Maven download inside Docker | Later builds reuse the `~/.m2` cache mount |
| A charge was declined unexpectedly | Check `declineReason` | Common: `INSUFFICIENT_CREDIT` once a card's limit is used up |

---

## 8. ledger-service (Phase 1)

### What it does
It records money movements using **double-entry bookkeeping**, and calculates balances from those records. Later (Phase 2), it will receive "transaction authorized" events from Kafka and post them automatically.

### Double-entry in one example
Alice buys a $42.50 coffee. From the card issuer's point of view:

| Account | Type | Debit | Credit | Meaning |
|---|---|---|---|---|
| Alice receivable | ASSET | 4250 | | Alice now owes us $42.50 |
| Coffee Shop payable | LIABILITY | | 4250 | We now owe the shop $42.50 |

Debits (4250) = credits (4250), so the transaction balances. If Alice later pays back $10:

| Account | Type | Debit | Credit |
|---|---|---|---|
| Settlement cash | ASSET | 1000 | |
| Alice receivable | ASSET | | 1000 |

Alice's balance = debits − credits = 4250 − 1000 = **3250** (she still owes $32.50).

**"Debit" doesn't mean "minus."** Whether a debit increases or decreases a balance depends on the account type's **normal side**:

| Type | Increases with | Balance formula |
|---|---|---|
| ASSET, EXPENSE | Debits | debits − credits |
| LIABILITY, REVENUE | Credits | credits − debits |

That's all `BalanceCalculator` does.

### How a POST /transactions request flows
```
HTTP JSON
  → TransactionController        @Valid runs Bean Validation (fields present, amounts > 0, 2–100 entries)
  → TransactionService.post      @Transactional: everything below commits together or not at all
      → AccountRepository.findAllById   load every referenced account in ONE query
      → PostingValidator                accounts exist? same currency? debits == credits? (clear errors)
      → LedgerTransaction + entries     build entities
      → repository.save                 INSERT transaction + entries
  → COMMIT → Postgres deferred triggers re-check balance, entry count, currency (safety net)
  → 201 Created + Location header + JSON body
```
If anything fails, `ApiExceptionHandler` turns it into a clean `application/problem+json` response: 400 for bad input, 404 for unknown IDs, 409 if a database constraint catches something, and 500 with a generic message for anything else. Details go to the logs, never to the client.

### Why the rules live in the database too
Java validation gives friendly errors, but it's only one path into the data. A future bug, another code path, or someone running SQL by hand could bypass it. The Postgres triggers in `V1__init.sql` make it **impossible to commit** an unbalanced, single-entry, mixed-currency, or edited transaction. `LedgerSchemaConstraintsTest` proves this with raw SQL that skips Java entirely.

### Why balances are derived, not stored
A stored `balance` column has to be updated on every posting. If any update is missed, doubled, or races with another, the balance is silently wrong and nothing can tell you why. Deriving it (`SUM` of entries) means the balance is *by definition* consistent with the history, and the API returns the debit and credit totals so anyone can check it. The tradeoff is a query on every read. That's fast with an index; at huge scale you'd add snapshots (ADR 0003).

### Gotchas we hit (good interview stories)
1. **plpgsql and `NEW`**: one trigger function was shared by two tables, and `NEW.transaction_id` failed on the table that doesn't have that column, even inside an unused `CASE` branch. Fixed with `IF/ELSE`.
2. **Spring Data `save()` with app-assigned UUIDs**: Spring Data decides "new or existing?" by checking whether the ID is null. Ours never is, so it ran `merge` (SELECT then UPDATE) instead of INSERT, and failed on the new entries. Fixed by implementing `Persistable.isNew()`.
3. **Spring Initializr's `4.1.1.RELEASE`**: the Maven artifact is just `4.1.1`.
4. **Healthcheck without curl**: the JRE image has no curl or wget, and `/bin/sh` is dash, so the healthcheck uses `bash`'s built-in `/dev/tcp`.

### Tests (40)
| Kind | Class | What it proves |
|---|---|---|
| DB rules | `LedgerSchemaConstraintsTest` | Postgres rejects bad data even when Java is bypassed |
| Unit | `PostingValidatorTest`, `BalanceCalculatorTest`, `AccountServiceTest` | Business rules, fast, no database |
| API | `AccountApiTest`, `TransactionApiTest`, `AccountHistoryApiTest`, `BalanceApiTest` | Full HTTP → DB → HTTP behavior, validation, error format |

Integration tests use **Testcontainers**: Docker starts a throwaway Postgres 18.6, Flyway migrates it, and the tests run against the real database. That catches problems that an in-memory fake database (like H2) would hide, such as our triggers.

---

## 9. Authorization and events (Phase 2)

### The full path of one charge
```
simulator ── POST /authorizations ─────────────────────────────▶ authorization-service
             Idempotency-Key: 9f1c…                                │
             X-Correlation-Id: abc                                 │ (key seen before? → return stored result)
                                                                   ▼
                                     ┌────────── ONE database transaction ──────────┐
                                     │ SELECT card FOR UPDATE      (lock the card)   │
                                     │ rules: exists? active? currency? ≤ available? │
                                     │ INSERT authorizations       (unique key)      │
                                     │ INSERT outbox_events        (approved only)   │
                                     └──────────────────────────────── COMMIT ──────┘
                                                                   │
                                OutboxRelay, every 500 ms:         ▼
                                FOR UPDATE SKIP LOCKED → send (acks=all) → published_at = now()
                                                                   │
                                          Kafka: transactions.authorized (3 partitions, key = card id)
                                                                   │
                                                                   ▼
                                     ┌──────── ledger-service, ONE transaction ─────┐
                                     │ INSERT processed_events(event_id)  dup → skip │
                                     │ find/create card:<id> (ASSET)                 │
                                     │ find/create merchant:<id> (LIABILITY)         │
                                     │ post DEBIT card / CREDIT merchant             │
                                     └──────────────────────────────── COMMIT ──────┘
                                                                   │
                                                          commit Kafka offset
```

### Why each piece exists: what breaks without it
| Without… | What goes wrong |
|---|---|
| Idempotency key | The client times out, retries, and the customer is charged twice |
| Request hash | A buggy client reuses a key for a different purchase and silently gets the old answer |
| Unique constraint on the key | Two identical requests arriving at once both pass the "seen before?" check |
| `FOR UPDATE` on the card | 20 simultaneous $10 charges on a $100 card all see $100 available; you approve $200 |
| Outbox | Commit, then crash before publishing: a real charge never reaches the ledger |
| Relay waits for `acks=all` | Kafka "accepted" a message it hadn't safely stored; it's lost if the broker dies |
| `processed_events` | Kafka redelivers after a rebalance and the ledger posts the charge twice |
| Offset commit after DB commit | Offset committed, then the DB write fails: the event is skipped forever |
| Dead-letter topic | One malformed message is retried forever and blocks every message behind it |
| Correlation ID | A support ticket says "my charge is missing" and there's no way to follow it across services |

### Proof (all automated)
| Claim | Test |
|---|---|
| 20 parallel charges on a $100 card approve exactly $100 | `AuthorizationConcurrencyTest` |
| 10 simultaneous duplicates create 1 authorization and 1 event | `AuthorizationConcurrencyTest` |
| Same key + different body is rejected | `AuthorizationApiTest` |
| Events survive Kafka being unreachable | `OutboxRelayTest`, and e2e `test_kafka_outage_loses_no_transactions` |
| Same event delivered 3× posts once | `TransactionAuthorizedConsumerTest` |
| Poison message goes to the DLT | `TransactionAuthorizedConsumerTest` |
| Every approval reaches the ledger exactly once | e2e `test_charges_flow_to_ledger_exactly_once` |

**Measured (1,000-charge simulation, 25 cards, 50/s):** 926 approved, 74 declined, 44 retries all replayed; 926 ledger postings, 0 duplicates, totals equal to the cent ($113,453.36); publish lag p50 284 ms / p95 531 ms; 0 messages in the DLT.

### Gotchas we hit
1. **Hibernate holds inserts until flush.** The consumer called `save()` (JPA), then ran plain JDBC that referenced the new row. JDBC goes straight to the database, which hadn't received the INSERT yet, so the foreign key failed. Every event failed and was retried. Fix: `saveAndFlush()`. Lesson: don't mix JPA and JDBC in one transaction without flushing.
2. **`@ServiceConnection` bypasses properties.** Testcontainers gave Spring the broker address directly, so a test reading `spring.kafka.bootstrap-servers` got the default `localhost:9092`, which was the *Compose* Kafka. Fix: read `KafkaConnectionDetails`.
3. **Consumers don't notice new topics quickly.** Subscribing before a topic exists can mean waiting minutes for a metadata refresh. Fix: create topics up front (`kafka-init` in Compose, `NewTopic` beans in tests).
4. **`@Validated` on a controller changes the exception type.** It switched header validation to the AOP path, which threw an unmapped exception (a 500). Removing it uses Spring MVC's built-in validation, which returns a 400.
5. **Postgres `jsonb` reformats JSON** (adds spaces, reorders keys), so tests must parse it, not string-match.
6. **Clock precision differs by OS.** Linux `Instant.now()` has nanoseconds; Postgres stores microseconds. The first response used the in-memory value and the idempotent replay used the stored one, so they differed in the last 3 digits. It passed on macOS, whose clock only reports microseconds, and failed in Linux CI. Fix: `DbTime.now()` truncates every timestamp to microseconds when it's created. Lesson: make values match what the database will store *before* you return them.
7. **Unused test dependencies cost real time.** `spring-boot-starter-kafka-test` pulled in an embedded Kafka broker plus a 60 MB native library that timed out inside Docker. We use Testcontainers, so it was removed.

### Known limitations (deliberately out of scope)
- Idempotency keys and `processed_events` are kept forever; production would expire them.
- Published outbox rows aren't cleaned up.
- No reversals, refunds or settlement yet: an authorization is treated as final.
- Available credit sums all approvals; with millions per card you'd keep a running total updated under the same row lock.
