# CardFlow: Project Brief for Claude Code

Read this whole file before doing anything. It is the source of truth for this project. Save it in the repo root as `PLAN.md` and refer back to it every session.

---

## 1. Who I am and why this project exists

- I'm Jason, a CS student at Northeastern University graduating May 2027.
- I'm targeting the **American Express 2027 Software Engineer I (Enterprise Technology Services)** new grad role, and similar backend / fintech roles.
- My background: Python, Java (coursework), JavaScript/TypeScript, React, Next.js, Swift, SQL, PostgreSQL, Docker basics, Git. I've built a Next.js full-stack app, an XGBoost model served with FastAPI, and an on-device iOS app with retrieval and tool-calling.
- **I'm new to:** Spring Boot, Kafka, microservices, Testcontainers, Resilience4j, AWS, Terraform, and production-style backend patterns. I want to actually learn these, not just have code generated for me.

### What the Amex posting asks for (build to hit these)
- Java, Spring, REST APIs, microservices, SQL
- Cloud-native development, containerization, CI/CD, DevOps, automated testing, code reviews, version control
- Secure software development
- AI/ML: LLM integrations, prompt evaluation, embeddings, RAG, vector search, agents, human-in-the-loop review
- Responsible AI: reliability, explainability, privacy, guardrails, validation of AI outputs

### Goals for the finished project
1. Resume-worthy: strong bullets with real metrics covering backend, ML, AI, and cloud.
2. Interview-defensible: I can explain every design decision and tradeoff.
3. Runs locally with one command: `docker compose up`.
4. Deployed to AWS with Terraform and automated CI/CD.
5. Clean, professional repo that a recruiter or engineer can understand in 2 minutes.

---

## 2. What we're building

**CardFlow** is a simplified card payments platform built as microservices. A synthetic merchant sends a charge, the platform authorizes it, scores it for fraud, records it in a double-entry ledger, and lets a human analyst review flagged transactions. An AI assistant answers questions about spending and card benefits, with guardrails. The whole system is deployed to AWS.

### Services

| Service | Language / Stack | Responsibility |
|---|---|---|
| `authorization-service` | Java, Spring Boot, PostgreSQL | Accepts charge requests, validates account/limits, calls fraud-service, approves or declines, publishes events |
| `ledger-service` | Java, Spring Boot, PostgreSQL | Double-entry ledger, account balances, transaction history API, consumes Kafka events |
| `fraud-service` | Python, FastAPI, XGBoost, SHAP | Scores transactions, returns risk score plus human-readable reason codes |
| `assistant-service` | Python, FastAPI, pgvector | RAG over card benefits docs plus tool-calling against ledger data, with guardrails and an eval suite |
| `simulator` | Python | Generates realistic synthetic traffic, including injected fraud patterns |
| `dashboard` | Next.js, TypeScript, Tailwind | Transaction view, fraud review queue (approve/reject), assistant chat |

### Infrastructure
- **Local:** Docker Compose for everything
- PostgreSQL (separate database per service, to respect service boundaries)
- Kafka in KRaft mode (single broker, official `apache/kafka` image is fine)
- GitHub Actions CI
- **Cloud (Phase 7):** AWS with Terraform. EC2 running the Compose stack, ECR, S3, SSM Parameter Store or Secrets Manager, GitHub OIDC deploys, optional Amazon Bedrock

### Request flow (core path)
```
simulator -> authorization-service --(sync REST, timeout + circuit breaker)--> fraud-service
                    |
                    |  writes decision + outbox row in one DB transaction
                    v
              outbox relay -> Kafka topic "transactions.authorized"
                                      |
                                      v
                              ledger-service (consumes, posts double-entry)
```

---

## 3. Key engineering decisions (I need to understand all of these)

Explain each of these to me briefly the first time you implement it.

1. **Idempotency keys** on `POST /authorizations`. Same key = same response, never a double charge.
2. **Transactional outbox pattern** so a DB write and a Kafka publish can't get out of sync.
3. **Double-entry ledger**: every transaction creates balanced debit and credit entries. Balances are derived, never edited directly. Store money as integer minor units (cents), never floats.
4. **Resilience4j circuit breaker + timeout** on the fraud-service call, with a simple rule-based fallback if fraud-service is down.
5. **Idempotent Kafka consumer** in ledger-service (dedupe by event ID) since Kafka is at-least-once.
6. **Database per service**, communicating only via APIs and events.
7. **Flyway migrations** for all Java service schemas.
8. **Correlation IDs** passed through every request and event, included in all logs.
9. **Infrastructure as code** with Terraform: nothing in AWS gets created by clicking in the console.

---

## 4. Security and responsible AI rules (non-negotiable)

- **Synthetic data only.** No real card numbers, names, or personal data anywhere.
- Never store a full card number. Store a fake token plus last 4 digits only. Mask in logs and UI.
- Validate all input (Bean Validation in Java, Pydantic in Python). Return clean error responses, no stack traces.
- Secrets via environment variables and a `.env.example` locally, SSM Parameter Store or Secrets Manager in AWS. Never commit real keys.
- No PII or full request bodies in logs.
- Enable Dependabot.
- AWS: least-privilege IAM roles, GitHub OIDC instead of long-lived access keys, only the dashboard port exposed publicly.
- Assistant guardrails:
  - Redact PII before anything is sent to the LLM
  - Answers must be grounded in retrieved docs or tool results, with citations
  - Refuse or say "I don't know" when it can't ground an answer
  - Never let the LLM take actions that move money; it is read-only
- Fraud model must return explainable reason codes (from SHAP), not just a score.
- Flagged transactions go to a **human review queue**, the model never has the final word on borderline cases.

---

## 5. Phased plan

Work one phase at a time. Each phase must be fully working, tested, and committed before moving on. **Phases 0 to 2 are the priority**: they alone make a complete resume project. Everything after builds on it.

### Phase 0: Repo and infrastructure setup
- Monorepo layout:
  ```
  cardflow/
    services/
      authorization-service/
      ledger-service/
      fraud-service/
      assistant-service/
    simulator/
    dashboard/
    infra/
      terraform/    (used in Phase 7)
    docs/
      adr/          (architecture decision records)
    docker-compose.yml
    .github/workflows/
    PLAN.md
    PROGRESS.md
    README.md
  ```
- Docker Compose with PostgreSQL and Kafka running and healthchecked
- GitHub Actions skeleton that runs on every PR
- README with project summary and a Mermaid architecture diagram
- **Done when:** `docker compose up` starts Postgres and Kafka cleanly and CI passes on an empty PR.

### Phase 1: Ledger service
- Use the latest stable Java LTS and latest stable Spring Boot (check current versions before pinning). Maven.
- Entities: accounts, transactions, ledger entries
- Endpoints: create account, get balance, list transactions (paginated)
- Double-entry posting logic with a DB constraint or check that entries always balance
- springdoc OpenAPI docs
- Tests: unit tests (JUnit 5, Mockito) and integration tests with Testcontainers Postgres
- **Done when:** I can create accounts, post a transaction, see correct balances, and all tests pass in CI.

### Phase 2: Authorization service + events
- `POST /authorizations` with idempotency key header
- Checks: account exists, card active, amount within available credit
- Outbox table plus a relay that publishes to Kafka
- Ledger-service consumes events idempotently and posts entries
- Integration test covering the full flow with Testcontainers (Postgres + Kafka)
- Simulator v1: sends a steady stream of normal charges
- **Done when:** simulator traffic flows end to end, duplicate requests never double-post, and killing Kafka briefly loses no transactions.

### Phase 3: Fraud service
- Simulator v2 injects fraud patterns (velocity bursts, unusual merchant categories, geographic jumps, amount spikes)
- Feature engineering and XGBoost model
- **Time-based train/test split** to avoid leakage
- Evaluate with precision, recall, and PR-AUC (not accuracy, the classes are imbalanced). Document results in `docs/model-card.md`
- FastAPI `/score` endpoint returning score plus top 3 SHAP reason codes
- Wire into authorization-service with Resilience4j timeout, circuit breaker, and rule-based fallback
- pytest suite
- **Done when:** authorization uses the model, a test proves the fallback works when fraud-service is down, and the model card has real metrics.

### Phase 4: Dashboard + human review queue
- Next.js + TypeScript + Tailwind
- Transactions table with filters, masked card numbers
- Review queue: transactions in a "needs review" score band show reason codes, analyst can approve or reject, decision is recorded with an audit trail
- **Done when:** I can watch live simulator traffic and resolve flagged transactions from the UI.

### Phase 5: AI assistant with guardrails
- Write 5 to 10 synthetic card benefits docs (travel insurance, purchase protection, rewards categories, etc.) in `services/assistant-service/docs/`
- Chunking, embeddings, pgvector storage, retrieval
- Tool-calling: tools that query ledger-service (spending by category, by date range, largest transactions)
- Provider-agnostic LLM client interface so the model provider can be swapped (Amazon Bedrock gets added as a provider in Phase 7)
- Guardrails from section 4
- **Eval suite**: 30+ test questions with expected answers, covering grounded questions, ungroundable questions (should refuse), and prompt injection attempts. Script outputs accuracy, refusal correctness, and citation rate. Run it in CI or as a make target.
- Chat panel in the dashboard
- **Done when:** eval suite runs with reported scores and injection attempts are handled.

### Phase 6: Polish and proof
- Load test with k6, record p95 authorization latency and throughput in README
- Structured JSON logs, Spring Actuator health and metrics
- 3 to 5 ADRs in `docs/adr/` (why outbox, why database per service, why double-entry, why circuit breaker, why provider-agnostic LLM)
- README: architecture diagram, how to run, design decisions, metrics, demo GIF
- **Done when:** a stranger can clone, run, and understand the project in under 5 minutes.

### Phase 7: AWS deployment
Only start after Phases 0 to 6 work locally.
- **Before creating any resources:** set up an AWS Budget alert (around $10/month)
- Terraform for all infrastructure in `infra/terraform/`, with remote state in S3
- ECR repositories for each service image
- One EC2 instance running the Docker Compose stack (Kafka and Postgres stay in containers, no MSK or RDS to keep costs low). Size it to actually fit Kafka plus the JVM services
- S3 bucket for assistant benefits docs
- SSM Parameter Store or Secrets Manager for secrets, nothing hardcoded
- IAM role on the EC2 instance with least-privilege access to ECR, S3, and secrets
- Security group allowing only the dashboard port publicly, everything else private
- GitHub Actions workflow: build images, push to ECR, deploy to EC2, authenticated with GitHub OIDC (no long-lived AWS access keys)
- Optional: add Amazon Bedrock as an LLM provider behind the existing provider-agnostic interface, and run the eval suite against it
- Document monthly cost estimate, how to stop the instance when not demoing, and teardown steps (`terraform destroy`) in README
- Add an ADR explaining why EC2 + Compose instead of ECS/EKS/MSK (cost vs. scale tradeoff)
- Explain IAM roles, OIDC, security groups, and Terraform state to me as we go
- **Done when:** a push to main deploys automatically, the live demo works, and I can tear everything down and rebuild it with Terraform.

### Stretch (only if everything above is done)
- OpenTelemetry distributed tracing across services
- Move from EC2 + Compose to ECS Fargate
- Run on a local Kubernetes cluster with kind
- JWT auth between services

---

## 6. How I want you to work with me

1. **Start every phase by proposing a short plan** (files, endpoints, schema, tests, AWS resources) and wait for my approval before writing code.
2. **Teach as you go.** When you introduce a new concept (Spring dependency injection, JPA, outbox, Kafka consumer groups, Testcontainers, SHAP, embeddings, IAM, Terraform), give me a 2 to 4 sentence explanation of what it is and why we're using it.
3. **Leave me one "YOUR TURN" task per phase**: a small, meaningful piece for me to implement myself, with hints but not the full answer. Review my code honestly when I'm done.
4. **Tests are written alongside code**, not after.
5. **Small commits** with conventional commit messages (`feat:`, `fix:`, `test:`, `docs:`, `chore:`). Use a branch and PR per phase so CI runs.
6. **Don't add a dependency without telling me why.** Prefer standard, widely used libraries.
7. **Ask before big architectural changes** or deviating from this plan.
8. **Never run anything that creates AWS resources or costs money without telling me first** and giving a rough cost.
9. **Keep `PROGRESS.md` updated** at the end of every session: what's done, what's next, open issues, and any metrics we've measured.
10. **At the end of each phase**, give me:
    - A summary of what we built
    - 3 interview questions someone could ask about it, with short model answers
    - A draft resume bullet using real numbers we measured (no made-up metrics)

---

## 7. Metrics to track for the resume

Record real numbers in `PROGRESS.md` as we get them:
- Number of services and total tests
- p95 authorization latency and requests per second under load
- Zero duplicate postings under retry / failure tests
- Fraud model precision, recall, PR-AUC
- Assistant eval: answer accuracy, correct refusal rate, citation rate, injection attempts blocked
- Deploy time from push to live, and monthly AWS cost

---

## 8. Non-goals

- No real payment processing or real card data
- No user signup/login system beyond what's needed for the demo
- No expensive managed AWS services (MSK, RDS, NAT gateways, load balancers) unless I explicitly approve
- No over-engineering: no Kubernetes, service mesh, or multiple Kafka brokers unless we reach the stretch phase

---

## First task

Read this plan, then:
1. Ask me any clarifying questions you have.
2. Propose the Phase 0 plan and wait for my approval.
