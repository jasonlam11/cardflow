#!/usr/bin/env bash
# Runs once, the first time the postgres volume is created.
# Re-run it with:  make reset-db   (destroys local data)
#
# Database-per-service: each service gets its own login role and its own
# database, and can CONNECT only to that database (PUBLIC access revoked).
#
#   role               database         password env var
#   ledger_svc         ledger           LEDGER_DB_PASSWORD
#   authorization_svc  authorization    AUTHORIZATION_DB_PASSWORD
#   assistant_svc      assistant        ASSISTANT_DB_PASSWORD
#
# Note: passwords are interpolated into SQL, so they must not contain a
# single quote. Fine for local dev; AWS uses SSM-managed values (Phase 7).
set -euo pipefail

# --- ledger-service -------------------------------------------------------
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres <<EOSQL
  CREATE ROLE ledger_svc LOGIN PASSWORD '${LEDGER_DB_PASSWORD}';
  CREATE DATABASE ledger OWNER ledger_svc;
  REVOKE CONNECT ON DATABASE ledger FROM PUBLIC;
  GRANT CONNECT ON DATABASE ledger TO ledger_svc;
EOSQL

# --- authorization-service ------------------------------------------------
# "authorization" is a reserved word in SQL, so it must be double-quoted
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres <<EOSQL
  CREATE ROLE authorization_svc LOGIN PASSWORD '${AUTHORIZATION_DB_PASSWORD}';
  CREATE DATABASE "authorization" OWNER authorization_svc;
  REVOKE CONNECT ON DATABASE "authorization" FROM PUBLIC;
  GRANT CONNECT ON DATABASE "authorization" TO authorization_svc;
EOSQL

# --- assistant-service ----------------------------------------------------
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres <<EOSQL
  CREATE ROLE assistant_svc LOGIN PASSWORD '${ASSISTANT_DB_PASSWORD}';
  CREATE DATABASE assistant OWNER assistant_svc;
  REVOKE CONNECT ON DATABASE assistant FROM PUBLIC;
  GRANT CONNECT ON DATABASE assistant TO assistant_svc;
EOSQL

# Extensions are per-database and need elevated privileges, so the admin
# installs pgvector inside "assistant" rather than granting that to assistant_svc
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname assistant <<EOSQL
  CREATE EXTENSION IF NOT EXISTS vector;
EOSQL
