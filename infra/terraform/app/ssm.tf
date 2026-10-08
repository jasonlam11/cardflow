# Configuration and secrets in SSM Parameter Store (standard tier: free).
# Secrets are generated here, stored encrypted (SecureString), and only ever read by the
# instance at deploy time. Nothing secret is committed. They do live in Terraform state,
# which is why the state bucket is private, encrypted and TLS-only (ADR 0015).

locals {
  secret_names = toset([
    "POSTGRES_ADMIN_PASSWORD", "LEDGER_DB_PASSWORD", "AUTHORIZATION_DB_PASSWORD",
    "ASSISTANT_DB_PASSWORD", "FRAUD_DB_PASSWORD", "ADMIN_API_KEY",
  ])

  config = {
    POSTGRES_ADMIN_USER = "cardflow_admin"
    ECR_REGISTRY        = local.registry
    AWS_REGION          = var.region
    ASSISTANT_PROVIDER  = "bedrock"
    BEDROCK_MODEL_ID    = var.bedrock_model_id
    ARTIFACTS_BUCKET    = aws_s3_bucket.artifacts.bucket
  }
}

resource "random_password" "secret" {
  for_each = local.secret_names
  length   = 32
  special  = false # safe inside .env files and Postgres connection strings
}

resource "aws_ssm_parameter" "secret" {
  for_each = local.secret_names

  name  = "${local.ssm_prefix}/${each.key}"
  type  = "SecureString"
  value = random_password.secret[each.key].result
}

resource "aws_ssm_parameter" "config" {
  for_each = local.config

  name  = "${local.ssm_prefix}/${each.key}"
  type  = "String"
  value = each.value
}
