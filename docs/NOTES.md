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

Phase 1 adds a job that builds and tests ledger-service.

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
