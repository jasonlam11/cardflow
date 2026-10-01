.DEFAULT_GOAL := help
COMPOSE := docker compose

.PHONY: help env up down ps logs reset-db

help: ## Show available commands
	@grep -E '^[a-zA-Z_-]+:.*?## ' $(MAKEFILE_LIST) | awk 'BEGIN{FS=":.*?## "}{printf "  \033[36m%-10s\033[0m %s\n", $$1, $$2}'

env: ## Create .env from .env.example if missing
	@test -f .env || (cp .env.example .env && echo "Created .env, edit the passwords")

up: env ## Start the stack and wait until healthy
	$(COMPOSE) up -d --wait

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
