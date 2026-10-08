# CardFlow

**A card-payments platform built as microservices:** charges are authorized exactly once, scored by an explainable fraud model, posted to a double-entry ledger through Kafka, and borderline cases go to a human review queue. An AI assistant answers cardholder questions with cited, guarded answers.

Java 25 · Spring Boot 4 · PostgreSQL 18 · Kafka · Python 3.14 · FastAPI · XGBoost + SHAP · pgvector · Claude · Next.js 16 · Docker · GitHub Actions · Terraform (AWS)

![Demo: a flagged charge is reviewed and approved, then the assistant answers questions about the card](docs/images/demo.gif)

> **All data is synthetic.** No real card numbers, people or financial products. "CardFlow Card" benefits are fictional.

## Quick start

Needs Docker Desktop (or Docker Engine with Compose v2) and `make`.

```bash
git clone https://github.com/jasonlam11/cardflow.git && cd cardflow
make demo
```

`make demo` creates `.env` from [`.env.example`](.env.example), builds and starts all 7 services, fills them with ~60 cards of realistic synthetic traffic (fraud patterns included), and prints the URL. The first run builds 6 images (about 8–10 minutes on an M2 laptop); after that it's about a minute.

Then open **http://127.0.0.1:3000**:
- **Review queue:** charges the fraud model flagged, with the score and the top reasons. Approve or reject with a note.
- **Transactions / Overview:** live traffic, decline reasons, cards (always masked to the last 4 digits).
- **Assistant:** ask "How much did I spend last month?" or "Is a 7-hour flight delay covered?". Without an API key it runs in a clearly labelled demo mode; set `ANTHROPIC_API_KEY` in `.env` to use Claude.

`make help` lists everything else (tests, load tests, evals, retraining). `make down` stops the stack; data is kept.

## Results

| Area | Result | Source |
|---|---|---|
| **Throughput / latency** | 200 req/s sustained for 2 min: **p50 4.4 ms, p95 28.9 ms, 0 errors**, every charge fraud-scored and posted to the ledger (one laptop, all services sharing 8 CPUs) | [performance](docs/performance.md) |
| **Exactly-once** | 0 duplicate ledger postings across every load test and retry test; Kafka stopped mid-traffic: **0 lost** | [performance](docs/performance.md), [e2e](tests/e2e/) |
| **Event pipeline** | Approval → ledger posting p95 **509 ms** at 200 req/s (was 57 s at peak load before 3 bottleneck fixes) | [performance](docs/performance.md#bottlenecks-found-and-fixed) |
| **Fraud model** | PR-AUC **0.918** on held-out days; flags **89.8%** of fraud; auto-declines at **87.1%** precision; live replay through the stack within 3 points | [model card](docs/model-card.md) |
| **Resilience** | fraud-service stopped under load: p95 6.4 ms, 0 errors (circuit breaker + rules fallback) | [ADR 0007](docs/adr/0007-circuit-breaker-fallback.md) |
| **Human review** | Approve → ledger posted in ~0.5–1.4 s; two analysts deciding at once → exactly one wins | [ADR 0010](docs/adr/0010-human-review-audit-trail.md) |
| **Assistant** | Retrieval recall@4 **100%** on a 36-question eval; every answer cited or replaced by "I don't know"; 0 injection leaks (demo mode; real-model eval on demand) | [eval results](docs/eval-results.md) |
| **Infrastructure as code** | AWS stack in Terraform (EC2, ECR, S3, SSM, least-privilege IAM, GitHub OIDC): 14 policy tests against a mocked provider, tflint and Trivy in CI. Written and tested, **not deployed** ($0) | [Deploying to AWS](#deploying-to-aws-not-currently-deployed) |
| **Tests** | **265** across unit, integration (Testcontainers), browser (Playwright), end-to-end and infrastructure suites, all in CI | [Testing](#testing) |

## Architecture

```mermaid
flowchart LR
    sim[simulator<br/><i>Python</i>] -->|POST /authorizations<br/>Idempotency-Key| auth
    analyst((Analyst)) --> dash

    subgraph core [Payment path]
        auth[authorization-service<br/><i>Spring Boot</i>]
        fraud[fraud-service<br/><i>FastAPI · XGBoost · SHAP</i>]
        ledger[ledger-service<br/><i>Spring Boot</i>]
    end

    auth -->|score, 300 ms timeout,<br/>circuit breaker| fraud
    auth -->|decision + outbox row<br/>in one transaction| authdb[(authorization DB)]
    authdb -.->|outbox relay| kafka{{Kafka<br/>transactions.authorized}}
    kafka -->|idempotent consumer| ledger
    ledger --> ledgerdb[(ledger DB)]
    fraud --> frauddb[(fraud DB)]

    dash[dashboard<br/><i>Next.js BFF</i>] -->|admin API key,<br/>server-side only| auth
    dash --> ledger
    dash -->|chat| assist[assistant-service<br/><i>FastAPI · pgvector · Claude</i>]
    assist -->|read-only tools| ledger
    assist --> assistdb[(assistant DB<br/>pgvector)]
```

Every service owns its database and talks to the others only through HTTP APIs and Kafka events. Only the dashboard faces users.

### How one charge flows
1. **authorization-service** gets `POST /authorizations` with an `Idempotency-Key`. A retry with the same key gets the stored result back, never a second charge.
2. It asks **fraud-service** for a score. That service computes features from the card's history, runs XGBoost, and returns a band plus SHAP reason codes. If it's slow or down, a circuit breaker switches to conservative rules.
3. In **one database transaction**, it locks the card row, applies the rules (status, currency, available credit, fraud band), saves the decision and, if approved, writes an event to the **outbox** table.
4. HIGH risk is declined. REVIEW goes to `PENDING_REVIEW`, where credit is held and **a human decides** in the dashboard. LOW proceeds normally.
5. The outbox relay publishes to **Kafka**. **ledger-service** consumes each event once (deduplicated by event ID) and posts a balanced **double-entry** transaction.

## Design decisions

The reasoning behind each choice is recorded as an ADR ([index](docs/adr/README.md)). The ones that matter most:

| Decision | Why | ADR |
|---|---|---|
| Transactional outbox | The decision and its event commit together, so Kafka outages lose nothing | [0005](docs/adr/0005-transactional-outbox.md) |
| Idempotency keys + idempotent consumer | Client retries and Kafka redelivery can't double-charge | [0006](docs/adr/0006-idempotency.md) |
| Double-entry ledger, DB-enforced | Balances derived, never stored; Postgres rejects unbalanced or edited history | [0003](docs/adr/0003-double-entry-ledger.md) |
| Circuit breaker + rules fallback | A sick fraud-service can't take payments down | [0007](docs/adr/0007-circuit-breaker-fallback.md) |
| One feature implementation for training and serving | No training/serving skew | [0008](docs/adr/0008-shared-feature-code.md) |
| Human review with an append-only audit trail | The model never has the final word on borderline cases | [0010](docs/adr/0010-human-review-audit-trail.md) |
| RAG + guardrails in code | Citations and amounts are verified, not trusted; tools are read-only | [0011](docs/adr/0011-rag-design.md), [0012](docs/adr/0012-llm-interface-and-guardrails.md) |
| Prometheus metrics + JSON logs with correlation IDs | Measure the pipeline, not just the API | [0013](docs/adr/0013-observability.md) |
| One EC2 instance running Compose, not ECS/EKS/MSK/RDS | ~$56/month on 24/7 (~$3–4 stopped) vs $150+ for managed equivalents | [0014](docs/adr/0014-ec2-compose-not-ecs.md) |
| GitHub OIDC + least-privilege IAM + SSM secrets | No long-lived AWS keys anywhere; only `main` of this repo can deploy | [0015](docs/adr/0015-iam-and-oidc.md) |

[docs/NOTES.md](docs/NOTES.md) explains how everything works in plain language, with every gotcha we hit along the way.

## Deploying to AWS (not currently deployed)

The AWS infrastructure is complete as code but **deliberately not running**, because running it costs money. Everything below is checked in CI without an AWS account.

```mermaid
flowchart LR
    gh[GitHub Actions<br/><i>push to main</i>] -->|OIDC: short-lived token,<br/>no AWS keys| sts[AWS STS]
    gh -->|ARM64 images,<br/>tagged with commit SHA| ecr[(ECR)]
    gh -->|compose files + deploy.sh| s3[(S3 artifacts)]
    gh -->|SSM Run Command<br/>no SSH| ec2
    subgraph vpc [Default VPC]
        ec2[EC2 t4g.large<br/>Docker Compose stack]
    end
    ec2 -->|instance role| ssm[(SSM Parameter Store<br/>generated secrets)]
    ec2 --> ecr
    ec2 --> s3
    ec2 -->|assistant| bedrock[Bedrock<br/>Claude Haiku 4.5]
    you((You)) -->|dashboard :3000<br/>your IP only| ec2
```

- **[`infra/terraform/bootstrap`](infra/terraform/bootstrap):** encrypted, versioned state bucket (native S3 locking), a $10/month budget alert, the GitHub OIDC provider.
- **[`infra/terraform/app`](infra/terraform/app):**
  - **Compute:** EC2 running the stack.
  - **Firewall:** inbound only on the dashboard port, from one network (validated to /24 or narrower); no SSH.
  - **Storage:** ECR repos (immutable tags, scan on push, lifecycle) and a private artifacts bucket.
  - **Secrets:** generated SecureString secrets.
  - **Access:** least-privilege roles for the instance and for deploys.
- **[`docker-compose.aws.yml`](docker-compose.aws.yml):** images from ECR, only the dashboard published, logs capped, assistant on **Bedrock** via the instance role (no API key).
- **[`.github/workflows/deploy.yml`](.github/workflows/deploy.yml):** build → ECR → SSM rollout with a health check. Every job is skipped unless `DEPLOY_ENABLED` is set.

**Checked on every PR:**
- `terraform fmt`, `validate` and 14 `terraform test` runs against a mocked AWS provider. They check only the dashboard port is open, least privilege, OIDC trust limited to `main`, private encrypted buckets, IMDSv2 and no surprise CPU-credit billing.
- tflint and a Trivy misconfiguration scan, with two accepted, documented exceptions.
- Tests that render the merged compose config.

Run them locally with `make tf-test` (free, no account).

**What it would cost** (us-east-1): about **$0.07/hour while running** plus ~$3–4/month for the disk and storage while stopped, or ~$56/month left on 24/7 ([ADR 0014](docs/adr/0014-ec2-compose-not-ecs.md)).

<details><summary>How to deploy it (costs money)</summary>

1. AWS account with CLI access; Terraform ≥ 1.11.
2. `cd infra/terraform/bootstrap && cp terraform.tfvars.example terraform.tfvars` (set your email), then `terraform init && terraform apply`.
3. `cd ../app && cp terraform.tfvars.example terraform.tfvars` (set `allowed_cidr` to your IP/32), then `terraform init -backend-config="bucket=<state_bucket output>" && terraform apply`.
4. In the repo settings, set variables `DEPLOY_ENABLED=true`, `AWS_DEPLOY_ROLE_ARN`, `ARTIFACTS_BUCKET`, `INSTANCE_ID` (from `terraform output`). Push to `main` → deploys.
5. Stop between demos: `aws ec2 stop-instances --instance-ids <id>`. **Tear down:** unset `DEPLOY_ENABLED`, then `terraform destroy` in `app/`. The bootstrap stack's state bucket is protected from accidental deletion; empty it and remove `prevent_destroy` to delete it too.
</details>

## Testing

| Suite | What it proves | Run | Tests |
|---|---|---|---|
| ledger-service | Double-entry rules (incl. raw-SQL DB constraint tests), consumer dedupe, DLT | `make test-ledger` | 51 |
| authorization-service | Idempotency, row locking under concurrency, outbox under Kafka outage, circuit breaker, review conflicts | `make test-auth` | 59 |
| fraud-service | Feature math, API, Postgres history, **model quality gate** (retrains from scratch) | `make test-fraud` | 28 |
| assistant-service | Guardrails against a scripted misbehaving model, tool scope, retrieval, pgvector, Bedrock client (faked) | `make test-assistant` | 52 |
| simulator | Dataset realism and determinism | `make test-sim` | 17 |
| dashboard | Components, BFF input handling (Vitest); review + chat flows in a real browser (Playwright) | `npm test`, `npm run test:e2e` | 26 + 9 |
| end-to-end | Exactly-once under retries, Kafka outage, fraud-service outage | `make e2e` | 4 |
| infrastructure | Terraform security properties (mocked AWS), AWS compose override | `make tf-test`, `pytest tests/infra` | 14 + 5 |

CI runs every suite on each pull request, plus a free assistant eval, dependency audits, Terraform lint and misconfiguration scans, and weekly container image scans.

## Repository map

```
services/
  authorization-service/   Java: authorizations, cards, review, outbox
  ledger-service/          Java: double-entry ledger, Kafka consumer
  fraud-service/           Python: features, XGBoost model, training pipeline
  assistant-service/       Python: RAG, tools, guardrails, eval suite
dashboard/                 Next.js ops UI (BFF route handlers in src/app/api)
simulator/                 synthetic traffic, labeled datasets, replay
perf/                      k6 load tests + pipeline report
tests/e2e/                 end-to-end tests against the compose stack
infra/                     Postgres init, Terraform (AWS), deploy script
tests/infra/               AWS compose override tests
docs/                      ADRs, NOTES, model card, eval results, performance
```

## More documentation
- [docs/NOTES.md](docs/NOTES.md): how it all works, decision log, glossary, troubleshooting
- [docs/performance.md](docs/performance.md): load-test method, results, bottlenecks fixed
- [docs/model-card.md](docs/model-card.md): fraud model data, metrics, limitations
- [docs/eval-results.md](docs/eval-results.md): assistant eval
- [PLAN.md](PLAN.md) and [PROGRESS.md](PROGRESS.md): the roadmap and status. **All 8 phases are done.** Phase 7's AWS infrastructure is written and tested but not deployed, to keep the project free.

<details><summary>Service ports (local)</summary>

| Component | Address | Notes |
|---|---|---|
| dashboard | [127.0.0.1:3000](http://127.0.0.1:3000) | The only user-facing service |
| ledger-service | 127.0.0.1:8081 | [/swagger-ui.html](http://127.0.0.1:8081/swagger-ui.html), /actuator/prometheus |
| authorization-service | 127.0.0.1:8082 | [/swagger-ui.html](http://127.0.0.1:8082/swagger-ui.html), /actuator/prometheus |
| fraud-service | 127.0.0.1:8083 | [/docs](http://127.0.0.1:8083/docs), /model, /metrics |
| assistant-service | 127.0.0.1:8084 | /info, /metrics |
| PostgreSQL 18 + pgvector | 127.0.0.1:5432 | One database and login per service |
| Kafka 4.3 (KRaft) | 127.0.0.1:9092 | Containers use `kafka:29092` |

All ports bind to `127.0.0.1` only. If `localhost:3000` shows a different app, another local server is using that port; use `127.0.0.1:3000`.
</details>

<details><summary>Screenshots</summary>

![Review queue](docs/images/dashboard-review-queue.png)
![Overview](docs/images/dashboard-overview.png)
![Transactions](docs/images/dashboard-transactions.png)
</details>
