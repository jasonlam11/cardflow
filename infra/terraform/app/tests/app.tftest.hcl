# Offline security tests for the app stack: mocked AWS, no credentials, nothing is created.
# Run: terraform init -backend=false && terraform test

mock_provider "aws" {
  mock_data "aws_caller_identity" {
    defaults = { account_id = "123456789012" }
  }
  mock_data "aws_vpc" {
    defaults = { id = "vpc-0abc", cidr_block = "172.31.0.0/16" }
  }
  mock_data "aws_subnets" {
    defaults = { ids = ["subnet-0b", "subnet-0a"] }
  }
  mock_data "aws_ssm_parameter" {
    defaults = { insecure_value = "ami-0123456789abcdef0" }
  }
}

mock_provider "random" {}

variables {
  allowed_cidr = "203.0.113.7/32"
}

run "dashboard_is_the_only_inbound_port_and_only_from_one_network" {
  command = apply

  assert {
    condition = (
      aws_vpc_security_group_ingress_rule.dashboard.from_port == 3000 &&
      aws_vpc_security_group_ingress_rule.dashboard.to_port == 3000 &&
      aws_vpc_security_group_ingress_rule.dashboard.ip_protocol == "tcp"
    )
    error_message = "Inbound must be exactly the dashboard port, TCP."
  }
  assert {
    condition     = aws_vpc_security_group_ingress_rule.dashboard.cidr_ipv4 == "203.0.113.7/32"
    error_message = "Dashboard must be reachable only from allowed_cidr."
  }
  assert {
    condition     = aws_vpc_security_group_egress_rule.https.from_port == 443 && aws_vpc_security_group_egress_rule.https.to_port == 443
    error_message = "Outbound should be HTTPS only."
  }
}

run "rejects_opening_the_dashboard_to_the_internet" {
  command = plan
  variables {
    allowed_cidr = "0.0.0.0/0"
  }
  expect_failures = [var.allowed_cidr]
}

run "rejects_a_wide_network" {
  command = plan
  variables {
    allowed_cidr = "10.0.0.0/8"
  }
  expect_failures = [var.allowed_cidr]
}

run "rejects_something_that_is_not_a_cidr" {
  command = plan
  variables {
    allowed_cidr = "my-laptop"
  }
  expect_failures = [var.allowed_cidr]
}

run "instance_is_hardened_and_cannot_surprise_bill" {
  command = apply

  assert {
    condition     = aws_instance.app.metadata_options[0].http_tokens == "required"
    error_message = "IMDSv2 must be required."
  }
  assert {
    condition     = aws_instance.app.metadata_options[0].http_put_response_hop_limit == 2
    error_message = "Hop limit 2 so containers can reach the instance role credentials."
  }
  assert {
    condition     = aws_instance.app.root_block_device[0].encrypted
    error_message = "Root volume must be encrypted."
  }
  assert {
    condition     = aws_instance.app.credit_specification[0].cpu_credits == "standard"
    error_message = "T4g 'unlimited' credits can bill extra; use 'standard'."
  }
  assert {
    condition     = aws_instance.app.instance_type == "t4g.large" && aws_instance.app.ami == "ami-0123456789abcdef0"
    error_message = "Expected the Graviton t4g.large on the latest Amazon Linux 2023 ARM image."
  }
  assert {
    condition     = aws_instance.app.subnet_id == "subnet-0a"
    error_message = "Subnet choice must be deterministic (sorted)."
  }
}

run "instance_role_is_least_privilege" {
  command = apply

  assert {
    condition = alltrue([
      for s in jsondecode(aws_iam_role_policy.instance.policy).Statement :
      s.Resource != "*" || s.Action == "ecr:GetAuthorizationToken"
    ])
    error_message = "Only ecr:GetAuthorizationToken may use Resource \"*\" (it can't be scoped)."
  }
  assert {
    condition = alltrue(flatten([
      for s in jsondecode(aws_iam_role_policy.instance.policy).Statement :
      [for a in flatten([s.Action]) : !strcontains(a, "*")]
    ]))
    error_message = "No wildcard actions."
  }
  assert {
    condition = alltrue([
      for s in jsondecode(aws_iam_role_policy.instance.policy).Statement : s.Effect == "Allow" && !can(s.NotAction)
    ])
    error_message = "No NotAction tricks."
  }
  assert {
    condition = contains(
      flatten([for s in jsondecode(aws_iam_role_policy.instance.policy).Statement : flatten([s.Resource]) if s.Sid == "ReadOurParameters"]),
      "arn:aws:ssm:us-east-1:123456789012:parameter/cardflow/*"
    )
    error_message = "Parameter access must be limited to /cardflow/*."
  }
  assert {
    condition = contains(
      flatten([for s in jsondecode(aws_iam_role_policy.instance.policy).Statement : flatten([s.Resource]) if s.Sid == "InvokeAssistantModel"]),
      "arn:aws:bedrock:*::foundation-model/anthropic.claude-haiku-4-5-20251001-v1:0"
    )
    error_message = "Bedrock access must be pinned to the assistant's model."
  }
}

run "only_main_of_this_repo_can_deploy" {
  command = apply

  assert {
    condition = (
      jsondecode(aws_iam_role.github_deploy.assume_role_policy).Statement[0].Condition.StringEquals["token.actions.githubusercontent.com:sub"]
      == "repo:jasonlam11/cardflow:ref:refs/heads/main"
    )
    error_message = "Deploy role must trust only the main branch of this repository."
  }
  assert {
    condition     = jsondecode(aws_iam_role.github_deploy.assume_role_policy).Statement[0].Condition.StringEquals["token.actions.githubusercontent.com:aud"] == "sts.amazonaws.com"
    error_message = "Token audience must be STS."
  }
  assert {
    condition     = !can(jsondecode(aws_iam_role.github_deploy.assume_role_policy).Statement[0].Condition.StringLike)
    error_message = "No wildcard (StringLike) subject matching."
  }
  assert {
    condition = alltrue([
      for s in jsondecode(aws_iam_role_policy.github_deploy.policy).Statement :
      s.Resource != "*" || contains(["ecr:GetAuthorizationToken", "ssm:GetCommandInvocation"], s.Action)
    ])
    error_message = "Deploy role: Resource \"*\" only for actions that can't be scoped."
  }
  assert {
    condition = alltrue(flatten([
      for s in jsondecode(aws_iam_role_policy.github_deploy.policy).Statement :
      [for a in flatten([s.Action]) : !strcontains(a, "*")]
    ]))
    error_message = "No wildcard actions."
  }
}

run "images_are_immutable_scanned_and_expired" {
  command = apply

  assert {
    condition     = length(aws_ecr_repository.service) == 6
    error_message = "One repository per image."
  }
  assert {
    condition = alltrue([
      for r in aws_ecr_repository.service :
      r.image_tag_mutability == "IMMUTABLE" && r.image_scanning_configuration[0].scan_on_push
    ])
    error_message = "Tags must be immutable and every push scanned."
  }
  assert {
    condition     = jsondecode(aws_ecr_lifecycle_policy.keep_recent["dashboard"].policy).rules[0].selection.countNumber == 5
    error_message = "Keep only the 5 newest images."
  }
}

run "secrets_are_generated_and_encrypted" {
  command = apply

  assert {
    condition     = length(aws_ssm_parameter.secret) == 6 && alltrue([for p in aws_ssm_parameter.secret : p.type == "SecureString"])
    error_message = "All six secrets must be SecureString."
  }
  assert {
    condition     = alltrue([for p in aws_ssm_parameter.secret : startswith(p.name, "/cardflow/")])
    error_message = "Secrets live under /cardflow/, the only path the instance can read."
  }
  assert {
    condition     = alltrue([for p in aws_ssm_parameter.config : p.type == "String"])
    error_message = "Non-secret config is plain String."
  }
}

run "artifacts_bucket_is_private_and_holds_the_docs" {
  command = apply

  assert {
    condition = alltrue([
      aws_s3_bucket_public_access_block.artifacts.block_public_acls,
      aws_s3_bucket_public_access_block.artifacts.block_public_policy,
      aws_s3_bucket_public_access_block.artifacts.ignore_public_acls,
      aws_s3_bucket_public_access_block.artifacts.restrict_public_buckets,
    ])
    error_message = "All four public-access blocks must be on."
  }
  assert {
    condition     = length(aws_s3_object.benefits_doc) == 8
    error_message = "All 8 benefits documents are uploaded."
  }
  assert {
    condition     = output.github_deploy_role_arn == aws_iam_role.github_deploy.arn
    error_message = "Deploy role ARN is exported for the workflow."
  }
}
