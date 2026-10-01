#!/usr/bin/env bash
# Runs once, the first time the postgres volume is created.
# Re-run it with:  make reset-db   (destroys local data)
#
# ============================== YOUR TURN ==============================
# Goal: database-per-service. Create three databases, each owned by its
# own login role, so that no service can connect to another's database.
#
#   role               database         password env var
#   ledger_svc         ledger           LEDGER_DB_PASSWORD
#   authorization_svc  authorization    AUTHORIZATION_DB_PASSWORD
#   assistant_svc      assistant        ASSISTANT_DB_PASSWORD
#
# Requirements:
#   1. Read passwords from the env vars above. Never hardcode them.
#   2. Each database is OWNED by its role.
#   3. By default every role can CONNECT to every database (via PUBLIC).
#      Take that away, then grant CONNECT back to only the owner.
#   4. The assistant database needs the pgvector extension (CREATE EXTENSION
#      vector) because extensions are per-database. Who has to run it?
#   5. Fail loudly on any error.
#
# Hints:
#   - `set -euo pipefail` at the top
#   - psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres <<-EOSQL ... EOSQL
#   - psql -c '\connect <db>' or --dbname <db> to switch databases
#   - Look up: CREATE ROLE ... LOGIN PASSWORD, CREATE DATABASE ... OWNER,
#     REVOKE CONNECT ON DATABASE ... FROM PUBLIC
#   - Test it: make reset-db, then try
#       docker exec -it cardflow-postgres psql -U ledger_svc -d assistant
#     It should be REJECTED.
# =======================================================================
set -euo pipefail
echo "01-create-databases.sh: not implemented yet (YOUR TURN task)"
