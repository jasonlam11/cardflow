#!/bin/bash
# Runs ON the EC2 instance (sent by .github/workflows/deploy.yml through SSM Run Command):
#   bash deploy.sh <git-sha>
# from the release directory the workflow synced from s3://<artifacts>/deploy/<sha>/.
# 1. secrets + config from SSM Parameter Store -> .env (root-only)
# 2. benefits docs from S3
# 3. pull the release's images from ECR and start/upgrade the stack, waiting for healthchecks
# 4. smoke test the dashboard locally (the security group blocks the runner from reaching it)
set -euo pipefail

SHA="${1:?usage: deploy.sh <git-sha>}"
[[ "$SHA" =~ ^[0-9a-f]{7,40}$ ]] || { echo "not a commit sha: $SHA" >&2; exit 2; }

RELEASE_DIR="$(cd "$(dirname "$0")" && pwd)"
APP_DIR=/opt/cardflow
# shellcheck source=/dev/null
source "$APP_DIR/bootstrap.env" # AWS_REGION, SSM_PREFIX (written by user_data)
export AWS_REGION

# 1. Parameters -> .env. Names are the last path segment (/cardflow/LEDGER_DB_PASSWORD -> LEDGER_DB_PASSWORD).
umask 077
aws ssm get-parameters-by-path --path "$SSM_PREFIX" --with-decryption \
  --query 'Parameters[].[Name,Value]' --output text |
  while IFS=$'\t' read -r name value; do printf '%s=%s\n' "${name##*/}" "$value"; done > "$APP_DIR/.env.new"
echo "IMAGE_TAG=$SHA" >> "$APP_DIR/.env.new"
mv "$APP_DIR/.env.new" "$APP_DIR/.env"
umask 022

set -a
# shellcheck source=/dev/null
source "$APP_DIR/.env"
set +a

# 2. Docs for the assistant
aws s3 sync "s3://$ARTIFACTS_BUCKET/docs/" "$APP_DIR/docs/" --delete --only-show-errors

# 3. Images and stack
aws ecr get-login-password | docker login --username AWS --password-stdin "$ECR_REGISTRY"
COMPOSE=(docker compose --project-name cardflow --project-directory "$RELEASE_DIR"
  -f "$RELEASE_DIR/docker-compose.yml" -f "$RELEASE_DIR/docker-compose.aws.yml" --env-file "$APP_DIR/.env")
"${COMPOSE[@]}" pull --quiet
"${COMPOSE[@]}" up -d --wait --wait-timeout 300 --remove-orphans

# 4. Smoke test, then tidy old images (disk is 30 GB)
for i in $(seq 1 30); do
  if curl -fsS "http://127.0.0.1:${DASHBOARD_PORT:-3000}/api/health" > /dev/null; then
    echo "deployed $SHA: dashboard healthy"
    docker image prune -af --filter "until=72h" > /dev/null
    exit 0
  fi
  sleep 2
done
echo "dashboard did not become healthy" >&2
"${COMPOSE[@]}" ps >&2
exit 1
