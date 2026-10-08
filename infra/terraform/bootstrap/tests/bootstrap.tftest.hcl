# Offline: a mocked AWS provider, no credentials, nothing is created.
# `command = apply` against the mock gives every computed value (ARNs, ids) a fake value,
# so assertions can check the finished policies, not just the plan.

mock_provider "aws" {
  mock_data "aws_caller_identity" {
    defaults = { account_id = "123456789012" }
  }
}

variables {
  budget_email = "alerts@example.com"
}

run "state_bucket_is_private_encrypted_and_versioned" {
  command = apply

  assert {
    condition     = aws_s3_bucket.state.bucket == "cardflow-tfstate-123456789012"
    error_message = "State bucket name must include the account id (bucket names are global)."
  }
  assert {
    condition     = aws_s3_bucket_versioning.state.versioning_configuration[0].status == "Enabled"
    error_message = "State bucket must be versioned so a bad apply can be rolled back."
  }
  assert {
    condition     = one(aws_s3_bucket_server_side_encryption_configuration.state.rule).apply_server_side_encryption_by_default[0].sse_algorithm == "AES256"
    error_message = "State bucket must be encrypted at rest."
  }
  assert {
    condition = alltrue([
      aws_s3_bucket_public_access_block.state.block_public_acls,
      aws_s3_bucket_public_access_block.state.block_public_policy,
      aws_s3_bucket_public_access_block.state.ignore_public_acls,
      aws_s3_bucket_public_access_block.state.restrict_public_buckets,
    ])
    error_message = "All four public-access blocks must be on."
  }
  assert {
    condition     = jsondecode(aws_s3_bucket_policy.state_tls_only.policy).Statement[0].Condition.Bool["aws:SecureTransport"] == "false"
    error_message = "State bucket policy must deny non-TLS requests."
  }
}

run "budget_alerts_at_the_configured_limit" {
  command = apply

  assert {
    condition     = aws_budgets_budget.monthly.limit_amount == "10" && aws_budgets_budget.monthly.limit_unit == "USD"
    error_message = "Default budget must be $10/month."
  }
  assert {
    condition     = length(aws_budgets_budget.monthly.notification) == 3
    error_message = "Expected alerts at 50% and 100% actual, and 100% forecast."
  }
}

run "github_oidc_audience_is_sts" {
  command = apply

  assert {
    condition     = aws_iam_openid_connect_provider.github.client_id_list == toset(["sts.amazonaws.com"])
    error_message = "GitHub OIDC tokens must be issued for sts.amazonaws.com."
  }
}

run "rejects_a_bad_email" {
  command = plan
  variables {
    budget_email = "not-an-email"
  }
  expect_failures = [var.budget_email]
}
