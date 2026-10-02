.DEFAULT_GOAL := help
COMPOSE := docker compose

.PHONY: help env up build down ps logs reset-db test-ledger test-auth test-sim test-fraud train simulate e2e

help: ## Show available commands
	@grep -E '^[a-zA-Z_-]+:.*?## ' $(MAKEFILE_LIST) | awk 'BEGIN{FS=":.*?## "}{printf "  \033[36m%-10s\033[0m %s\n", $$1, $$2}'

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

simulate: ## Send synthetic traffic (make simulate ARGS="--cards 20 --charges 500 --rate 20")
	$(COMPOSE) --profile sim run --rm --build simulator $(ARGS)

e2e: ## End-to-end tests against the stack (E2E_START_STACK=1 to start it via Testcontainers)
	cd tests/e2e && python3 -m venv .venv && .venv/bin/pip install -q -r requirements.txt && .venv/bin/pytest -v

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
