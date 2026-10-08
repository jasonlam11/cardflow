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
10. [Fraud scoring (Phase 3)](#10-fraud-scoring-phase-3)
11. [Dashboard and human review (Phase 4)](#11-dashboard-and-human-review-phase-4)
12. [AI assistant with guardrails (Phase 5)](#12-ai-assistant-with-guardrails-phase-5)
13. [Performance, observability and polish (Phase 6)](#13-performance-observability-and-polish-phase-6)
14. [AWS infrastructure as code (Phase 7)](#14-aws-infrastructure-as-code-phase-7)

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
| 32 | Circuit breaker + timeouts + rules fallback on the fraud call | A sick fraud-service can't take down payments, and fraud checks never switch off entirely | Fail open, fail closed, retries | [0007](adr/0007-circuit-breaker-fallback.md) |
| 33 | Fallback never declines, only routes to review | Without history the rules can't tell fraud from a big legit purchase | Rules that decline | [0007](adr/0007-circuit-breaker-fallback.md) |
| 34 | Score fraud before the DB transaction | Never hold the card row lock during a network call | Score inside the transaction | [0007](adr/0007-circuit-breaker-fallback.md) |
| 35 | One `compute_features()` for training and serving | No training/serving skew | Separate SQL features; a feature store | [0008](adr/0008-shared-feature-code.md) |
| 36 | Time-based train/validation/test split | Evaluation must match "train on the past, predict the future" | Random split (leaks) | [model card](model-card.md) |
| 37 | PR-AUC, precision, recall (not accuracy) | 1.5% fraud: "always legit" is 98.5% accurate | Accuracy, ROC-AUC alone | [model card](model-card.md) |
| 38 | Thresholds picked on validation: review at 90% recall, decline at 90% precision | Separates "a human looks" from "we're confident enough to decline" | One threshold | [model card](model-card.md) |
| 39 | `PENDING_REVIEW` status that holds credit, no ledger event until approved | The model never has the final word on borderline cases | Approve and flag | – |
| 40 | XGBoost JSON model format, not pickle | Loading a pickle can execute code | pickle / joblib | – |
| 41 | XGBoost's built-in TreeSHAP at runtime; `shap` library only in training | Same exact values, far fewer runtime dependencies | `shap` in the API image | – |
| 42 | Optional `occurredAt` on charges + replay mode | Realistic timing for features; lets us measure the model through the live system | Server time only | – |
| 43 | fraud-service: one uvicorn process per container | Measured: `--workers` mode added a ~40 ms Nagle stall per request | Multiple workers per container | – |
| 44 | `xgboost-cpu` in images and CI | ~6 MB instead of ~250 MB of GPU libraries we don't use | Full `xgboost` | – |
| 45 | Dashboard is a backend-for-frontend; the browser never calls services directly | One public entry point; secrets stay server-side | Browser → services with CORS | [0009](adr/0009-dashboard-bff-and-admin-key.md) |
| 46 | Admin API key for dashboard endpoints (not user login) | Proves calls come from the dashboard; login is a stretch goal | OIDC/JWT now; no auth | [0009](adr/0009-dashboard-bff-and-admin-key.md) |
| 47 | Review = one guarded transition, DB trigger + row lock + 409 | Two analysts can't both decide; history can't be edited | Optimistic locking; app-only checks | [0010](adr/0010-human-review-audit-trail.md) |
| 48 | Approval writes the ledger event in the same transaction | Approved ⇔ posted, never one without the other | Separate step after approval | [0010](adr/0010-human-review-audit-trail.md) |
| 49 | Polling every 3 s (TanStack Query) instead of WebSockets/SSE | Simple, stateless, good enough for an ops screen | Server-sent events; WebSockets | – |
| 50 | Zod validation of every BFF response in the browser | API drift fails loudly instead of rendering wrong data | Trust the types | – |
| 51 | Client-rendered pages calling `/api` (no Cache Components) | Live data; nothing should be cached or prerendered | Server Components with `use cache` | – |
| 52 | System font stack, no build-time Google Fonts | Builds don't depend on an external service | `next/font/google` | – |
| 53 | Chunk benefits docs by section; cite as `doc#section` | One topic per chunk; meaningful citations | Fixed-size token windows | [0011](adr/0011-rag-design.md) |
| 54 | Local embeddings (fastembed, bge-small), baked into the image | Free, offline, deterministic, testable in CI | Embedding API (Voyage) | [0011](adr/0011-rag-design.md) |
| 55 | pgvector in the assistant's own DB, HNSW cosine index | Reuses Postgres; no extra service | Qdrant / Pinecone | [0011](adr/0011-rag-design.md) |
| 56 | Loose retrieval cutoff; refusals decided by model + citation guardrail | Measured: answerable vs unanswerable similarity bands overlap | Strict threshold | [0011](adr/0011-rag-design.md) |
| 57 | Provider-agnostic `LLMClient`; Claude via the official SDK; demo + scripted fakes | Swap providers (Bedrock in Phase 7); run free without a key; test misbehaviour | Calling the SDK directly everywhere | [0012](adr/0012-llm-interface-and-guardrails.md) |
| 58 | Default model Claude Haiku 4.5 | Owner's choice (lowest cost); one env var to change, eval to compare | Opus 5.5 / Sonnet 5.5 | [0012](adr/0012-llm-interface-and-guardrails.md) |
| 59 | Guardrails in code: redaction, citation check, amount check, round cap | Don't rely on prompt wording for safety properties | Prompt-only rules | [0012](adr/0012-llm-interface-and-guardrails.md) |
| 60 | Only read-only tools; card id bound from the session | Model can't move money or read other accounts, whatever it's told | Tools with a card parameter | [0012](adr/0012-llm-interface-and-guardrails.md) |
| 61 | Real-model eval on demand only, behind `--confirm-cost` | Never spend money by accident; CI stays free | Eval on every push | [0012](adr/0012-llm-interface-and-guardrails.md) |
| 62 | Ledger stores merchant + MCC (V3) | Spending-by-category belongs to the system of record | Ask authorization-service | – |
| 63 | Load tests with k6, constant arrival rate, plus a DB pipeline report | Measures what users and the ledger actually see; can't hide slowness | JMeter; Locust; HTTP-only metrics | – |
| 64 | Outbox relay drains full batches (≤ 50 per tick), 500-row batches | Measured cap of 200 events/s; drained backlog at 600 req/s | Shorter poll interval; Debezium CDC | [0005](adr/0005-transactional-outbox.md) |
| 65 | Ledger consumer `concurrency: 3` (one per partition) | Ledger was 5.5 s behind; per-card order preserved by key | More partitions; batch listener | – |
| 66 | fraud-service: 3 uvicorn workers on httptools + uvloop (**revises #43**) | httptools sets TCP_NODELAY, so no Nagle stall (0.5 ms median); 100% ML-scored at 200 req/s | One process; a compiled model server | – |
| 67 | Prometheus-format metrics + ECS JSON logs; no Grafana yet | Standard, portable; dashboards come with deployment | OpenTelemetry tracing now; Grafana in Compose | [0013](adr/0013-observability.md) |
| 68 | Alpine JRE base images | 611/619 → 469 MB; less to scan and ship | jlink custom runtime; distroless | – |
| 69 | npm audit + pip-audit on PRs, Trivy on main/weekly | Fast checks where they block; image scans where images exist | Trivy on every PR (slow builds) | – |
| 70 | `make demo` one-command populated stack | A stranger sees real flagged reviews in minutes | Committed DB snapshot | – |
| 71 | Write and test the AWS infrastructure, don't deploy it | Project must cost $0 (account lost its free credits) | Deploy briefly (<$1); new account | [0014](adr/0014-ec2-compose-not-ecs.md) |
| 72 | One t4g.large running Compose; no ECS/EKS/MSK/RDS/NAT/ALB | ~$56/month 24/7 vs $150+ managed; fits the 3.8 GB measured | Fargate; EKS; managed Kafka/Postgres | [0014](adr/0014-ec2-compose-not-ecs.md) |
| 73 | GitHub OIDC deploy role trusted only for `main` | No long-lived keys; forks/PRs can't deploy | Access keys in GitHub secrets | [0015](adr/0015-iam-and-oidc.md) |
| 74 | SSM Run Command for deploys and shell; no SSH | No open port 22, no SSH key to leak, every command logged | SSH with a deploy key | [0015](adr/0015-iam-and-oidc.md) |
| 75 | Secrets generated by Terraform into SSM SecureString | Free, nothing typed or committed; state bucket protects them | Secrets Manager ($0.40/secret); manual secrets | [0015](adr/0015-iam-and-oidc.md) |
| 76 | `terraform test` with a mocked AWS provider | Proves security properties in CI with no account or cost | Plan against a real account; LocalStack | – |
| 77 | Policies via `jsonencode`, not `aws_iam_policy_document` | Tests can parse and assert on the exact JSON | The data source | – |
| 78 | Bedrock via the SDK's `AnthropicBedrock`, boto3 only in the AWS image | Reuses the Claude client; instance role auth; small local images | boto3 Converse API; boto3 everywhere | [0012](adr/0012-llm-interface-and-guardrails.md) |
| 79 | Trivy exceptions in a scoped, reasoned ignore file | Cost-driven trade-offs stay visible; anything new still fails | Lower the severity threshold; inline ignores | [0014](adr/0014-ec2-compose-not-ecs.md) |
| 80 | npm audit blocks on runtime deps, warns on dev-only | Dev tooling never ships (standalone image) | Block on everything; ignore advisories | – |

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
| **Feature** | A number computed from raw data that the model learns from, e.g. "charges in the last hour" | fraud-service |
| **Training/serving skew** | Features computed differently in training vs production, so the model sees unfamiliar inputs | Avoided by shared code |
| **Data leakage** | Information from the future (or the label) sneaking into training, inflating metrics | Avoided by time split |
| **Class imbalance** | One class is rare (fraud ~1.5%) | `scale_pos_weight`, PR-AUC |
| **Precision / recall** | Of what we flagged, how much was fraud / of all fraud, how much we flagged | Model card |
| **PR-AUC** | Area under the precision-recall curve; random ≈ the fraud rate, perfect = 1 | Headline model metric |
| **Gradient-boosted trees (XGBoost)** | Many small decision trees, each correcting the previous ones | The model |
| **SHAP value** | How much one feature pushed one prediction up or down | Reason codes |
| **Reason code** | Human-readable explanation of a score, e.g. `HIGH_VELOCITY` | API response, analyst UI |
| **Circuit breaker** | Stops calling a failing dependency for a while so failures are instant, then probes recovery | Fraud call |
| **Fallback** | What to do when the dependency can't answer | Rule-based scorer |
| **Cascading failure** | One slow service making its callers slow, and so on up the chain | What the breaker prevents |
| **Nagle's algorithm / delayed ACK** | TCP batching tricks that, combined, can add ~40 ms to small request/response exchanges | The fraud latency bug |
| **Backend-for-frontend (BFF)** | A server layer owned by the UI that calls backend services on the browser's behalf | Dashboard `/api` routes |
| **Route handler** | A Next.js server endpoint (`app/api/.../route.ts`) | The BFF |
| **Server vs client component** | React components that run only on the server vs. ship JavaScript to the browser | Pages are client components |
| **TanStack Query** | Library that fetches, caches, polls and refetches server data in React | Every page |
| **Zod** | Runtime schema validation for TypeScript | Response checks |
| **Optimistic vs pessimistic locking** | Detect conflicts at write time (version check) vs. prevent them by locking first | Review uses pessimistic (`FOR UPDATE`) |
| **409 Conflict** | HTTP status for "valid request, but it conflicts with the current state" | Second analyst's decision |
| **Playwright** | Drives a real browser for end-to-end tests | `dashboard/tests/e2e` |
| **Fail closed** | When something's missing or wrong, deny rather than allow | Admin key unset → admin endpoints closed |
| **RAG (retrieval-augmented generation)** | Find relevant passages first, then have the model answer only from them | Assistant |
| **Embedding** | A vector of numbers representing a text's meaning; similar texts get similar vectors | Retrieval |
| **Cosine similarity** | How closely two vectors point the same way (1 = same meaning) | Search scores |
| **HNSW** | A graph index that finds nearest vectors fast without comparing against all of them | pgvector index |
| **Chunk** | A piece of a document indexed on its own; here, one section | Knowledge base |
| **Recall@k** | Share of questions whose right passage is among the top k results | Retrieval metric |
| **Tool use / function calling** | The model asks your code to run a named function with JSON arguments, then reads the result | Ledger tools |
| **Grounding** | Every claim traceable to a provided source | Citation guardrail |
| **Hallucination** | A fluent claim with no basis in the sources | What the amount check catches |
| **Prompt injection** | Text (from a user or from data) that tries to override the system's instructions | Eval category |
| **Prompt caching** | The API reuses an unchanged prompt prefix across requests, cheaper and faster | Stable system prompt |
| **Eval** | A fixed question set with automatic scoring, run before and after changes | `evals/` |
| **p50 / p95 / p99** | The latency that 50% / 95% / 99% of requests beat | Load test results |
| **Constant arrival rate** | Send N requests/s regardless of response time, so a slow system can't reduce its own load | `perf/steady.js` |
| **Coordinated omission** | The measurement mistake where a slowing system makes the load generator send less, hiding the slowness | Why we use arrival rate |
| **Saturation** | A resource is fully busy, so queues and latency grow | fraud-service above ~200 req/s |
| **Breakpoint test** | Ramp load until a threshold breaks to find the limit | `perf/breakpoint.js` |
| **Counter / gauge / histogram** | Metric types: only-up count / current value / distribution of values | Prometheus metrics |
| **Cardinality** | How many distinct label combinations a metric has; ids as labels blow it up | Metric label rules |
| **Scrape** | Prometheus pulling `/metrics` on an interval | Observability |
| **Structured logging** | Logs as JSON fields instead of free text, so they can be searched and filtered | ECS logs |
| **CVE** | A publicly catalogued security vulnerability in a package | Trivy, pip-audit |
| **Cold start / warm-up** | First requests are slow while models and caches load; warm-up does that work at startup | fraud + assistant |
| **Infrastructure as code (IaC)** | Infrastructure described in files, reviewed and versioned like code, applied by a tool | `infra/terraform/` |
| **Terraform state** | Terraform's record of what it created (ids, attributes, secrets) | S3 state bucket |
| **IAM role** | An AWS identity with permissions; whoever may assume it gets temporary credentials | Instance + deploy roles |
| **Trust policy** | The part of a role that says who may assume it | OIDC `sub` = main |
| **Least privilege** | Grant only the exact actions on the exact resources needed | Both IAM policies |
| **OIDC (federation)** | Trading a signed identity token (from GitHub) for temporary cloud credentials | Deploy workflow |
| **Security group** | Instance firewall; everything inbound is denied unless a rule allows it | Dashboard port only |
| **IMDSv2** | Instance metadata service with session tokens; blocks SSRF credential theft | `http_tokens = required` |
| **SSM Run Command / Session Manager** | Run commands or open a shell via the SSM agent, no inbound port or SSH key | Deploys |
| **Inference profile** | A Bedrock model id that routes requests across regions | `global.anthropic.claude-haiku-4-5…` |
| **Mock provider** | A fake cloud API for `terraform test`, inventing ids so tests run offline | Terraform tests |

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
| fraud-service won't start: password authentication failed for `fraud_svc` | Your Postgres volume predates the `fraud` database | `make reset-db` (local data only) |
| Every response has `"scoredBy": "RULES_FALLBACK"` | fraud-service down or the circuit is open | `make logs s=fraud-service`; the circuit retries after 10 s |
| Retraining changed nothing | Training is deterministic for a given dataset and seed | Change the data/seed or parameters on purpose |
| Dashboard shows "Missing or invalid admin API key" | `ADMIN_API_KEY` differs (or is missing) between dashboard and authorization-service | Set it once in `.env`, then `docker compose up -d` |
| Docker Desktop "unable to start", or `EROFS: read-only file system` in a build | The Mac's disk is full, so Docker's disk image can't grow | Free disk space; `docker builder prune`; restart Docker Desktop |
| `npm` crashes with `reading 'edgesOut'` | Old npm (Node 20) hitting a peer-dependency conflict | Use Node 24 (`nvm use` in `dashboard/`) |
| `make perf` fails its thresholds right after `make up` | Cold JVMs and fraud workers | Let it run ~30 s or run once to warm up, then measure |
| Every authorization `RULES_FALLBACK` under heavy load | fraud-service saturated (~200 req/s on a laptop) | Expected; see `cardflow_authorizations_total{scored_by=...}` |
| `make demo` takes many minutes the first time | Building 6 images from scratch | Later runs reuse the build cache (~1 min) |
| Review queue empty after `make up` | No traffic yet | `make demo` (or `make simulate`) |
| `terraform init` fails: no route to host / slow | The AWS provider is a ~170 MB download | Retry; set `TF_PLUGIN_CACHE_DIR=~/.terraform.d/plugin-cache` to download once |
| `terraform init` tries to reach S3 | The app stack has an S3 backend | For offline work use `terraform init -backend=false` (what `make tf-test` does) |
| Deploy workflow shows every job "skipped" | Deploys are off (`DEPLOY_ENABLED` unset) | Expected; see README "Deploying to AWS" |
| Assistant on AWS answers 503 | Instance role can't reach Bedrock (model access, IMDS hop limit, region) | `docker logs cardflow-assistant`; check the role and Bedrock model access |

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

---

## 10. Fraud scoring (Phase 3)

### The flow, now with fraud
```
POST /authorizations
  ├─ idempotency check (replay if seen)
  ├─ unlocked card check ── unknown / inactive? → NOT_SCORED (skip fraud)
  ├─ fraud score ───────────────▶ fraud-service POST /score   (timeouts 200/300 ms)
  │      via circuit breaker          ├─ load card's last 30 days (≤100) from Postgres
  │      └─ on failure: rules         ├─ compute_features(history, current)   ← same code as training
  │                                   ├─ XGBoost score + TreeSHAP contributions
  │                                   ├─ band: LOW / REVIEW / HIGH  (thresholds from metadata)
  │                                   └─ record this charge in the card's history (once per requestId)
  └─ ONE DB transaction: lock card → rules → save (status, score, band, reasons, scored_by)
         HIGH   → DECLINED / FRAUD_SUSPECTED   (200)
         REVIEW → PENDING_REVIEW, credit held, no ledger event yet  (202)
         LOW    → normal rules → APPROVED (201) + outbox event
```

### How the model was built
1. **Data:** simulator v2 generates 90 days for 1,500 synthetic cardholders (255,003 transactions, 1.47% fraud), with realistic legit "noise" so fraud isn't trivial.
2. **Features:** 15, computed by replaying the data in time order with the same function the API uses.
3. **Split by time:** train days 0–62, validation 63–76, test 77–89. Never random.
4. **Train:** XGBoost, early stopping on validation PR-AUC, `scale_pos_weight` for imbalance. 101 trees; training is deterministic.
5. **Thresholds** chosen on validation only; **metrics** measured once on test. See [model-card.md](model-card.md).

| Test period (offline) | Precision | Recall |
|---|---|---|
| Flag for review or decline | 72.3% | 89.8% |
| Auto-decline (HIGH) | 87.1% | 81.9% |
| Rule-based fallback | 30.7% | 16.6% |

PR-AUC **0.918** (random ≈ 0.013). Weakest pattern: amount spikes (46% recall).

**Live replay (the real stack, end to end).** `python -m cardflow_sim replay` sent the held-out days through authorization → fraud-service → decision, with original timestamps: 400 cardholders, 14 warm-up days (not scored), then days 77–89 measured. **20,041 real authorizations in 108.5 s.**

| Live, days 77–89 (9,614 charges, 124 fraud) | Result | Offline test |
|---|---|---|
| Flagged (review or decline): precision / recall | **69.9% / 86.3%** | 72.3% / 89.8% |
| Auto-declined that were fraud | **87.3%** (96 of 110) | 87.1% |
| Legitimate charges wrongly declined | **0.15%** (14 of 9,490) | – |
| Recall by pattern | velocity 100%, unusual category 90%, impossible travel 76%, amount spike 23% | 98% / 87% / 80% / 46% |

Live numbers track offline within a few points, which is the evidence there's no training/serving skew. The small gap is expected: each card only had 14 days of history in the replay versus up to 90 in training.

### Proof (automated)
| Claim | Test |
|---|---|
| Feature math (velocity windows, travel speed, first-time category, NaN handling, caps) | `fraud-service/tests/test_features.py` |
| Training split never overlaps in time; model beats rules; quality gate | `test_training.py` (CI retrains from scratch) |
| Burst and impossible travel get flagged with reasons | `test_api.py` |
| HIGH declines, REVIEW holds credit without a ledger event | `FraudIntegrationTest` |
| fraud-service error or slow → rules fallback within the timeout | `FraudIntegrationTest` |
| Circuit opens after 10 failures; the next calls never reach fraud-service | `FraudIntegrationTest` |
| Authorization keeps working with fraud-service stopped | e2e `test_authorizations_keep_working_when_fraud_service_is_down` |

### Gotchas we hit
1. **`PENDING_REVIEW` didn't fit `VARCHAR(10)`.** The column from Phase 2 was too short for the new status; the integration test caught the 500. Fixed in Flyway V2 (`ALTER COLUMN ... TYPE VARCHAR(16)`).
2. **`REAL` is a 32-bit float.** A score of 0.02 came back as 0.0199999. Scores are `DOUBLE PRECISION`.
3. **Boot 4 split out `RestClient.Builder`.** Used Spring Framework's `RestClient.builder()` instead of adding another starter.
4. **A 40 ms stall on every fraud call** (the big one). Authorization took ~57 ms, fraud-service said it took ~2 ms. How it was found, step by step:
   - Added timings to the authorization log: the fraud call was ~47 ms, the DB work ~5 ms.
   - Fresh connections were fast; kept-alive ones always ~45 ms. A fixed ~40 ms on reuse is the signature of **Nagle's algorithm + TCP delayed ACK**.
   - Tried client fixes (HTTP/1.1, a different HTTP client, `TCP_NODELAY` on the client): no change, so the client wasn't it.
   - Probed with Python from another container: same 45 ms. Probed even `GET /model` (no DB, no body): same. So it was the server.
   - Isolated in a throwaway container: one uvicorn worker 1.2 ms, `--workers 2` **42 ms**.
   - Fix: one uvicorn process per container. Fraud call ~5 ms, authorization ~15 ms.
   - Lesson: **measure each hop before changing things**, and change one variable at a time. Two plausible-sounding fixes did nothing.
5. **A stopped container's hostname doesn't resolve**, so with fraud-service down each call spent ~200 ms on DNS before falling back. The circuit breaker is what makes this cheap: once open, calls skip the network entirely.

### Known limitations
- Synthetic data; real fraud is messier and adversarial. Monitoring for drift and retraining from analyst decisions and chargebacks would be next.
- Cold start: new cards have no history-based signal.
- fraud-service's `card_activity` grows forever; production would expire rows older than the 30-day window.
- The fallback rules are duplicated in Java (serving) and Python (measurement); a shared spec or test fixture would keep them in sync.

---

## 11. Dashboard and human review (Phase 4)

### How the pieces connect
```
browser ──▶ dashboard :3000 (Next.js)
              pages (client components, poll every 3 s)
                 │  fetch /api/...           ← Zod-validates every response
                 ▼
              route handlers (server only)   ← adds X-Admin-Api-Key, whitelists filters/ids
                 ├──▶ authorization-service  /authorizations, /reviews, /stats, /cards
                 └──▶ ledger-service         /accounts?externalRef=..., /balance
```
The admin key is a server env var, so it never reaches the browser (verified: 0 occurrences in the client bundles).

### What happens when an analyst clicks Approve
```
POST /api/reviews/{id}/decision  {decision, analyst, note}
  → POST /authorizations/{id}/review   (authorization-service, one DB transaction)
       SELECT ... FOR UPDATE                   lock the authorization
       still PENDING_REVIEW?  no → 409         another analyst got there first
       status = APPROVED                       DB trigger allows only this transition
       INSERT review_decisions                 append-only audit row
       INSERT outbox_events                    transaction.authorized
     COMMIT
  → outbox relay → Kafka → ledger consumer → DEBIT card / CREDIT merchant
```
Reject is the same, except the status becomes `DECLINED` / `ANALYST_REJECTED`, no event is written, and the held credit is released (available credit is computed from approved and pending charges only).

### Pages
| Page | Shows |
|---|---|
| Overview | 24h counts by status and decline reason, review queue size and oldest item, a warning if the rules fallback was used, live transactions |
| Transactions | Filter by status and risk band, paging; cards masked to the last 4 digits |
| Review queue | Oldest first; amount, merchant, category, location or online, score, band, model version, top reasons as bars sized by SHAP contribution, the card's other recent charges; Approve or Reject (note required) |
| Decisions | The audit trail: who, what, when, note |
| Card | Limit, available credit (after holds), ledger balance, recent authorizations |

### Proof (automated)
| Claim | Test |
|---|---|
| Approve → `APPROVED`, outbox event, decision row | `ReviewApiTest` |
| Reject → `DECLINED`, credit released, no event | `ReviewApiTest` |
| Two analysts at once → one 200, one 409, one decision row | `ReviewApiTest` |
| DB refuses any other change or delete | `ReviewApiTest` (raw SQL) |
| Admin endpoints need the key; merchant endpoints don't | `ReviewApiTest` |
| BFF whitelists filters, adds the key server-side, fails closed without it | `dashboard/tests/unit/bff.test.ts` |
| Review panel validation, request body, conflict message | `ReviewPanel.test.tsx` |
| Browser: flagged charge → approve → **ledger posts it**; reject → credit released; both in audit trail | Playwright `review.spec.ts` |

**Measured** (Playwright, real browser against the compose stack): all 6 browser tests pass in ~9 s. From the analyst clicking **Approve** to the ledger showing the posted balance took **0.45–1.4 s** across four runs (the outbox relay polls every 500 ms, so most of that is the relay interval plus Kafka delivery).

### Gotchas we hit
1. **Next.js 16 has new APIs.** The scaffold ships `AGENTS.md` warning about this; route types like `RouteContext` and `LayoutProps` are generated by `next typegen`, so `npm run typecheck` runs it first.
2. **vitest 5 needs Node ≥ 22.12**, and old npm crashed (`edgesOut`) instead of reporting the peer conflict. Fix: Node 24 everywhere (`.nvmrc`, CI, Docker).
3. **React 19 lint: no `setState` inside `useEffect`** to read `localStorage`. External state belongs in `useSyncExternalStore`.
4. **Testing Library cleanup** only runs automatically with vitest globals; added a setup file.
5. **Long queue, missing item.** The queue lists the 50 oldest charges, so after a replay left 100+ pending, a new charge wasn't on screen. Fix: deep links (`/reviews?id=…`) and links from the transactions table. Found by the Playwright test.
6. **Reasons on low-risk charges were misleading.** The model returns its top positive SHAP contributions for every charge; on a low-risk charge those didn't make it risky. The table now shows reasons only for flagged charges. Found by looking at the screenshot.
7. **Another app on port 3000.** A different project's dev server was listening on IPv6 `::1:3000`, so `localhost:3000` went there, while Docker listens on IPv4 `127.0.0.1:3000`. Tests use `127.0.0.1`. If the dashboard looks wrong, check `lsof -iTCP:3000 -sTCP:LISTEN`.
8. **The Mac's disk filled up** (2 GB free) and Docker Desktop went read-only, then wouldn't start. Cleared download caches (~5 GB) and Docker build cache (~4 GB). Lesson: image rebuilds accumulate build cache; prune it periodically.

---

## 12. AI assistant with guardrails (Phase 5)

### What one question goes through
```
dashboard /assistant ──▶ BFF /api/assistant/chat (validates card id, length) ──▶ assistant-service POST /chat
   1. redact()            card numbers (Luhn), SSNs, emails, phones → [REDACTED_*]
   2. retrieve()          top-4 sections from pgvector (bge-small embeddings, cosine)
   3. tool loop (≤ 5)     model ⇄ read-only tools bound to THIS card
                            get_balance · spending_by_category · largest_transactions · recent_transactions
                            (tool output is redacted too; bad inputs come back as tool errors)
   4. guardrails          cited?  citations provided in THIS request?  every $ amount in a source?
                            no → "I don't know…" (and the reason is shown)
   5. response            answer · sources · tools used · tokens · cost · latency
```

### The model, and running without a key
- `LLMClient` is a tiny provider-neutral interface. **Claude** goes through the official `anthropic` SDK (default **Claude Haiku 4.5**, `ASSISTANT_MODEL` to change).
- With no credentials the service runs **`DemoLLM`**: deterministic rules that call tools and cite sources. Retrieval, tools and guardrails are real; the answers are not a model's. The API and UI both say "demo mode".
- **Haiku 4.5 and caching:** Haiku only caches prompt prefixes of 4,096+ tokens; ours is about 2k, so cache reads will be zero on Haiku (bigger models cache from 512–1,024 tokens). The eval reports cache reads so this is visible, not assumed.

### Eval suite (36 questions)
| Category | n | Passes when |
|---|---|---|
| Benefits | 12 | Not refused, required fact present (e.g. "$500"), expected section retrieved |
| Account | 8 | Exact amount from the fixture ledger (e.g. "$659.00"), right tool used |
| Should refuse | 8 | "I don't know" / "I can't" (credit score, investing, rental-car cover, move money, someone else's account) |
| Injection | 7 | No forbidden output (system prompt, "$50,000" fake coverage, "$1,000,000" planted in a merchant name) |
| PII | 1 | Answers, and never echoes the card number |

Run it: `make eval` (free, demo) or `make eval-claude` (needs a key **and** confirms the cost first).

**Results so far (demo mode, no API key):** retrieval recall@4 **100%** (every benefits question's section was retrieved), citation rate **100%**, injection/PII resistance **100%**, cost **$0**. Demo-mode accuracy and refusal numbers (25/36 passed) describe the rule-based stand-in, not a model: e.g. it answers "what's my credit score?" from the nearest section instead of refusing, which is exactly the judgment a real model has to supply. **Real-model results:** not yet measured (no API key); `make eval-claude ARGS=--confirm-cost` produces them (estimate ~$0.50 per run on Haiku 4.5).

### Proof (automated)
| Claim | Test |
|---|---|
| Chunking, stable ids, re-index only on change | `test_knowledge.py` |
| Redaction, citation parsing/validation, amount grounding | `test_guardrails.py` |
| No money-moving tool; no card parameter; other cards see nothing; input validation | `test_tools.py` |
| Uncited / wrongly cited / made-up-amount answers are replaced; round cap; refusal stop reason; PII never sent | `test_chat.py` (scripted misbehaving model) |
| API validation, clean 503 on LLM outage | `test_api.py` |
| pgvector search and corpus replacement | `test_store_postgres.py` (Testcontainers) |
| Retrieval recall@4 = 100% | CI demo eval (`--min-recall 1.0`) |
| Browser: balance answer from the real ledger with a tool source; benefits answer with a doc source | Playwright `assistant.spec.ts` |

### Gotchas we hit
1. **A regex ate a space.** `(\d[ -]?){13,19}` matched the space after a card number, so redaction glued words together. Patterns that consume separators should start and end on the thing itself.
2. **Python name shadowing.** `create_app(info=...)` later defined a route function also called `info`, so the startup code stored the function instead of the dict.
3. **Similarity is not answerability** (measured; see ADR 0011).
4. **Wrong expected totals in my own tests** ($1,050.89 vs the actual $989.89). Compute expected values from the fixture, don't type them.

---

## 13. Performance, observability and polish (Phase 6)

### What "fast" means here, and how we measured it
- **Load tests with k6.** `perf/steady.js` uses a **constant arrival rate**: k6 starts N requests per second no matter how slowly the system responds. (A fixed number of users each waiting for their reply would quietly send *less* traffic as the system slowed down, which hides problems. This is called *coordinated omission*.)
- **Realistic traffic, not a hammer on one card:** 500 cards, each card's charges spaced 6 hours apart, so the fraud model sees normal spending. 10% of requests are **retried with the same idempotency key** and must return the identical response.
- **Measure the whole pipeline.** `perf/report.py` queries both databases afterwards: outbox publish delay, **approval → ledger** delay, events left unpublished, and duplicate postings. HTTP latency alone said everything was fine while events were a minute behind.
- **Percentiles, not averages.** p95 = 95% of requests were faster than this. The average hides the slow tail that real users feel.

Headline (one M2 laptop, all services sharing 8 CPUs): **200 req/s for 2 min: p50 4.4 ms, p95 28.9 ms, 0 errors, 0 duplicates, approval → ledger p95 509 ms.** Full tables: [performance.md](performance.md).

### Three bottlenecks the measurements found
| What we saw | Why | Fix |
|---|---|---|
| At 600 req/s, 24,490 events stuck in the outbox; ledger 57 s behind | Relay sent one 100-row batch per 500 ms tick, a hard cap of 200 events/s | Keep draining full batches (bounded at 50 per tick), 500-row batches |
| Ledger still 5.5 s behind | One consumer thread, three partitions | `concurrency: 3`. Order still holds per card because events are keyed by card id |
| ~80% of high-load charges scored by rules, not the model | One Python process in fraud-service; cold workers timed out after restarts | 3 uvicorn workers on httptools + uvloop, and a warm-up at startup |

The fraud-service fix revisits a Phase 3 decision (#43). `--workers` had caused a 40 ms stall because the default HTTP parser (h11) didn't set `TCP_NODELAY`, so Nagle's algorithm held back small packets. httptools sets it, so multiple workers are now safe. We re-measured: 0.5 ms median, no stall. **Decisions are revisited when the facts change, and the measurement is what makes that safe.**

### Observability
- **Metrics:** `/actuator/prometheus` (Java, Micrometer) and `/metrics` (Python). Three kinds:
  - **counter** = only goes up (authorizations by status / band / `scored_by`)
  - **gauge** = a current value (outbox backlog, circuit open)
  - **histogram/timer** = distribution of durations (fraud scoring, HTTP latency), from which p95 is computed
- Labels are small fixed sets (status, band). Never card ids or amounts: each distinct label value creates a new time series (**cardinality**), and ids would also leak data.
- **Logs:** JSON (ECS format) in Compose, each line with `correlationId`. To follow one charge: `docker logs cardflow-authorization | grep <id>`, then the same in ledger.
- No Grafana yet (owner's choice); the endpoints are ready for Phase 7. [ADR 0013](adr/0013-observability.md).

### Security checks
- **On every PR:** `npm audit --audit-level=high` (dashboard) and `pip-audit` (the three Python projects).
- **On main, weekly and on demand:** Trivy scans every built image for CRITICAL CVEs that have a fix.
- Dependabot keeps versions moving; baseline at the end of Phase 6: **0 known vulnerabilities**.

### Smaller images
- Java runtime switched to `eclipse-temurin:25-jre-alpine`: ledger and authorization images **611 / 619 MB → 469 MB** each. The non-root user is now created with Alpine's `addgroup` / `adduser`, and healthchecks use `wget`, since there's no `curl` in Alpine.
- Still large: assistant 828 MB (the embedding model and ONNX runtime) and fraud 597 MB (numpy, xgboost). They're big because of what they need, not because of waste.

### One-command demo
`make demo` = create `.env` → build and start everything → replay 5 days of synthetic traffic for 60 cards (after 6 warm-up days of history) with fraud episodes. About 40 s after the images are built, the dashboard shows 1,348 charges with 20 awaiting review. The summary it prints covers only the 5 replayed days: 437 approved, 64 fraud declines, 10 pending review. The data is deterministic (fixed seed), so every run gives the same numbers.

**Stranger test** (fresh clone into an empty directory, empty Docker volumes, following the README literally): it found two snags, both fixed. (1) A temporary PyPI failure broke the cold assistant build; pip now retries. (2) A second `make demo` replayed nothing, because idempotency keys came from the dataset and collided with the previous run's keys. Keys are now unique per run, with a regression test. After the fixes: clone → populated dashboard in one command; the cold build is about 8–10 minutes. The README's GIF was captured from that state by `dashboard/scripts/demo-capture.mjs`.

### Gotchas we hit
1. **My first backlog measurement was wrong.** It drained during startup, before the load started, so the number meant nothing. I threw it out and used the breakpoint run. Check that you're measuring what you think you are.
2. **Zero errors can hide a degraded system.** At 400 req/s there were no errors, but only 34% of charges were scored by the model. The `scored_by` breakdown exposed it.
3. **Cold start shows up in demos.** The first assistant question took 2.6 s (loading the embedding model). A warm-up at startup brought it to 336 ms. The same applied to fraud-service after restarts.
4. **Node resolves modules from the script's location**, not your working directory. The capture script had to live inside `dashboard/` to find Playwright.
5. **"Works on my machine" because of leftover state.** The demo bug only appeared on a second run; my machine always had the first run's data. And my first "fresh" test silently reused my volumes, because the compose file fixes the project name. Test on genuinely empty state.
6. **Version tags drift.** The k6 image and the GitHub Actions versions in my head were outdated. Look them up instead of trusting memory.

---

## 14. AWS infrastructure as code (Phase 7)

### The decision: write it all, deploy nothing
The plan was to deploy to AWS. While setting up the account, enabling IAM Identity Center quietly created an AWS Organization, and that automatically moved the account from the Free plan to the Paid plan with **$0 credits**. (Lesson: on the Free plan, use a plain IAM user. Creating or joining an Organization upgrades the account.) The project has to stay free, so Phase 7 became: **write the infrastructure exactly as for a real deploy, prove its security properties offline, and never apply it.** Deploying later is `terraform apply` plus four repository variables.

### What would run, and how a deploy would flow
```
git push main
  └─ GitHub Actions (deploy.yml, skipped unless DEPLOY_ENABLED)
       1. OIDC: GitHub signs a short-lived token → AWS STS swaps it for 1-hour credentials
          for the deploy role (trusted ONLY for repo jasonlam11/cardflow, branch main)
       2. build 6 ARM64 images on GitHub's ARM runner → push to ECR, tagged <commit sha>
       3. upload docker-compose.yml + docker-compose.aws.yml + deploy.sh → s3://artifacts/deploy/<sha>/
       4. SSM Run Command on the instance (no SSH):  bash deploy.sh <sha>
            ├─ SSM Parameter Store → root-only .env (generated secrets + config)
            ├─ S3 docs/ → /opt/cardflow/docs (assistant knowledge base)
            ├─ docker compose pull && up -d --wait (healthchecks)
            └─ curl http://127.0.0.1:3000/api/health   (the runner can't reach it: firewall)
```

### Four AWS concepts, in plain language
**IAM roles.** A role is an identity with a list of allowed actions on specific resources. It has no password. Whoever is allowed to *assume* it gets temporary credentials, typically valid for an hour.
- The EC2 instance assumes `cardflow-instance` automatically, and containers fetch its credentials from the instance metadata service.
- Each role has two policies:
  - **trust policy:** who can assume it
  - **permissions policy:** what it can do

  Least privilege means naming exact resources: "pull from *these 6* repositories", not "use ECR".

**OIDC (OpenID Connect) for GitHub.** Instead of storing an AWS access key in GitHub (which never expires and works from anywhere if leaked):
- Each workflow run gets a token signed by GitHub, saying "I am repo X, branch Y".
- AWS checks the signature and compares the claims to the deploy role's trust policy: `aud = sts.amazonaws.com`, `sub = repo:jasonlam11/cardflow:ref:refs/heads/main`.
- A pull request from a fork has a different `sub`, so AWS refuses.

**Security groups.** A firewall around the instance. AWS **denies all inbound traffic** unless a rule allows it, so the rules are the complete list:
- **Inbound:** TCP 3000 from your IP only.
- **Outbound:** HTTPS (443) only.

Postgres, Kafka and the APIs have no rule, so nothing outside can reach them; they talk over the Docker network inside the box. There's no SSH rule, because SSM gives a shell over an outbound connection the SSM agent makes, authenticated by IAM and logged.

**Terraform state.** Terraform remembers what it created (resource ids, attributes, *and the generated secrets*) in a state file.
- **Where it lives:** an S3 bucket that is private, encrypted, versioned and TLS-only.
- **Locking:** `use_lockfile` writes a `.tflock` object, so two people (or two CI jobs) can't apply at once. Terraform 1.11+ does this natively, with no DynamoDB table needed.
- **The chicken-and-egg:** the bucket itself is created by a tiny **bootstrap** stack that keeps its own state locally.

### How you test infrastructure without deploying it
- **`terraform test` with `mock_provider "aws"`:** Terraform runs a real plan/apply against a fake AWS that invents ids and ARNs. Assertions then check the *finished* resources:
  - only port 3000 inbound
  - `0.0.0.0/0` and `10.0.0.0/8` rejected for `allowed_cidr`
  - IMDSv2 required
  - `cpu_credits = "standard"`
  - no `Resource: "*"` except for actions AWS can't scope
  - no wildcard actions
  - OIDC `sub` pinned to `main`
  - private encrypted buckets
  - immutable, scanned images

  14 runs, and no credentials exist in CI.
- **Mutation check:** I broke the security group (opened it to `0.0.0.0/0`) and the IAM policy (`ecr:*`) on purpose. Both were caught, which proves the tests can fail.
- **tflint** (with the AWS ruleset) catches invalid instance types and deprecated arguments. **Trivy** scans for known misconfigurations. It found 2, both accepted with reasons in `infra/terraform/.trivyignore.yaml`: outbound HTTPS to anywhere, because a NAT gateway is ~$33/month, and S3-managed encryption keys, because KMS is $1/key/month.
- **Compose tests** render `docker-compose.yml` + `docker-compose.aws.yml` and assert only the dashboard publishes a port.

**What the tests can't prove:** that AWS accepts every value, that the AMI boots and installs Docker, real Bedrock calls, or deploy timing. Those need a real `apply`.

### Bedrock
`BedrockLLM` is ~40 lines on top of the Claude client, because the Anthropic SDK's `AnthropicBedrock` speaks the same Messages API.
- **Credentials:** they come from the AWS default chain, which on EC2 means the instance role, so there's no key.
- **Model ID and endpoint:** Haiku 4.5 is on Bedrock's older InvokeModel path with ID `global.anthropic.claude-haiku-4-5-20251001-v1:0`. The `global.` prefix means cross-region routing with no regional price premium.
- **No automatic caching:** Bedrock rejects top-level automatic `cache_control`, so it's switched off for Bedrock.
- **AWS errors:** failures such as missing credentials become a clean 503.
- **Image size:** boto3 (~90 MB) goes only into the AWS image, via `WITH_BEDROCK=true`.

### Cost guards written into the code
- $10 budget alert in the bootstrap stack, created before anything that costs money.
- `cpu_credits = "standard"`: T4g defaults to "unlimited", which bills for CPU beyond earned credits.
- ECR keeps 5 images, release files expire after 30 days, container logs are capped at 3 × 10 MB.
- No NAT gateway, no load balancer, no RDS, no MSK.

### Gotchas we hit
1. **Creating an AWS Organization upgrades a Free-plan account to Paid**, and the credits disappear. It's irreversible. Use a plain IAM user on the Free plan. (I gave the wrong setup steps; the lesson is to check AWS's current plan rules before clicking.)
2. **The AWS provider is ~170 MB compressed and ~750 MB unpacked,** even just for offline tests. A shared `TF_PLUGIN_CACHE_DIR` keeps one copy for both stacks.
3. **Policy JSON via `jsonencode`, not `aws_iam_policy_document`.** Under a mock provider that data source returns fake JSON, so the tests couldn't inspect it.
4. **Containers need IMDS hop limit 2.** With IMDSv2 and the default limit of 1, the token can't make the extra network hop into a container, so Bedrock calls would fail without credentials.
5. **`aws ssm wait command-executed` gives up after about 100 s.** Rollouts take minutes, so the workflow polls instead.
6. **New advisories appear without any code change.** A `braces` advisory with no fixed version reached us only through a dev-only linter. Runtime dependencies still block CI; dev tooling now warns.
