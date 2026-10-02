.DEFAULT_GOAL := help
COMPOSE := docker compose

.PHONY: help demo env up build down ps logs reset-db test-ledger test-auth test-sim test-fraud test-assistant train eval eval-claude simulate e2e perf perf-breakpoint

help: ## Show available commands
	@grep -E '^[a-zA-Z_-]+:.*?## ' $(MAKEFILE_LIST) | awk 'BEGIN{FS=":.*?## "}{printf "  \033[36m%-10s\033[0m %s\n", $$1, $$2}'

demo: env ## One command: build and start everything, fill it with realistic traffic, print the URL
	$(COMPOSE) up -d --build --wait
	$(COMPOSE) --profile sim run --rm --build simulator demo
	@echo ""
	@echo "  CardFlow is running:  http://127.0.0.1:3000"
	@echo "  (review queue, transactions, assistant chat; API docs on :8081-:8084)"

env: ## Create .env from .env.example if missing
	@test -f .env || (cp .env.example .env && echo "Created .env, edit the passwords")

up: env ## Start the stack and wait until healthy
	$(COMPOSE) up -d --wait

build: env ## Rebuild service images and restart (after code changes)
	$(COMPOSE) up -d --build --wait

test-ledger: ## Run ledger-service unit + integration tests (needs Docker)
	cd services/ledger-service && ./mvnw -B verify

test-auth: ## Run authorization-service unit + integration tests (needs Docker)
	cd services/authorization-service && ./mvnw -B verify

test-sim: ## Run simulator unit tests
	cd simulator && python3 -m venv .venv && .venv/bin/pip install -q -r requirements-dev.txt && .venv/bin/pytest -q

test-fraud: ## Run fraud-service tests (incl. model quality gate)
	cd simulator && python3 -m venv .venv && .venv/bin/pip install -q -r requirements.txt && .venv/bin/python -m cardflow_sim dataset --out data/transactions.csv.gz
	cd services/fraud-service && python3 -m venv .venv && .venv/bin/pip install -q -r requirements-dev.txt && FRAUD_TRAINING_DATA=$(CURDIR)/simulator/data/transactions.csv.gz .venv/bin/pytest -q

train: ## Regenerate the synthetic dataset, retrain the fraud model, rewrite the model card
	cd simulator && python3 -m venv .venv && .venv/bin/pip install -q -r requirements.txt && .venv/bin/python -m cardflow_sim dataset --out data/transactions.csv.gz
	cd services/fraud-service && python3 -m venv .venv && .venv/bin/pip install -q -r requirements-train.txt && .venv/bin/python -m training.train

test-assistant: ## Run assistant-service tests (needs Docker for the pgvector test)
	cd services/assistant-service && python3 -m venv .venv && .venv/bin/pip install -q -r requirements-dev.txt && .venv/bin/pytest -q

eval: ## Assistant eval with the free demo model (pipeline + retrieval); writes docs/eval-results.md
	cd services/assistant-service && python3 -m venv .venv && .venv/bin/pip install -q -r requirements.txt && .venv/bin/python -m evals.run --min-recall 1.0

eval-claude: ## Assistant eval against Claude: prints the cost estimate; add ARGS=--confirm-cost to actually run (COSTS MONEY)
	cd services/assistant-service && .venv/bin/python -m evals.run --provider anthropic --model $(or $(MODEL),claude-haiku-4-5) $(ARGS)

simulate: ## Send synthetic traffic (make simulate ARGS="--cards 20 --charges 500 --rate 20")
	$(COMPOSE) --profile sim run --rm --build simulator $(ARGS)

e2e: ## End-to-end tests against the stack (E2E_START_STACK=1 to start it via Testcontainers)
	cd tests/e2e && python3 -m venv .venv && .venv/bin/pip install -q -r requirements.txt && .venv/bin/pytest -v

perf: ## k6 steady load against the running stack (RATE=100 DURATION=2m), then the pipeline report
	@START=$$(date -u +"%Y-%m-%d %H:%M:%S"); \
	docker run --rm -i --network cardflow_default -v "$(CURDIR)/perf:/perf" -e RATE=$(or $(RATE),100) -e DURATION=$(or $(DURATION),2m) \
	  grafana/k6:2.3.0 run --summary-export /perf/results/steady.json /perf/steady.js; \
	sleep 10; python3 perf/report.py --since "$$START" | tee perf/results/pipeline.json

perf-breakpoint: ## k6 ramp until p95 > 200 ms or errors > 1% (finds max sustainable req/s)
	docker run --rm -i --network cardflow_default -v "$(CURDIR)/perf:/perf" -e MAX_RATE=$(or $(MAX_RATE),600) \
	  grafana/k6:2.3.0 run --summary-export /perf/results/breakpoint.json /perf/breakpoint.js

down: ## Stop the stack (keeps data)
	$(COMPOSE) down

ps: ## Show container status and health
	$(COMPOSE) ps

logs: ## Tail logs (make logs s=kafka for one service)
	$(COMPOSE) logs -f $(s)

reset-db: ## DESTROY local postgres data and re-run init scripts
	$(COMPOSE) rm -sf postgres
	docker volume rm cardflow_postgres-data || true
	$(COMPOSE) up -d --wait postgres
