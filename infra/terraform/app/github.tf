# The role GitHub Actions assumes to deploy, via OIDC (the provider is created by the bootstrap
# stack). The trust policy is the security boundary: only workflows from this repository,
# running on the deploy branch, can get credentials. Pull requests and forks cannot.

locals {
  github_oidc_provider_arn = "arn:aws:iam::${local.account_id}:oidc-provider/token.actions.githubusercontent.com"
}

resource "aws_iam_role" "github_deploy" {
  name                 = "${var.project}-github-deploy"
  max_session_duration = 3600

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Federated = local.github_oidc_provider_arn }
      Action    = "sts:AssumeRoleWithWebIdentity"
      Condition = {
        StringEquals = {
          "token.actions.githubusercontent.com:aud" = "sts.amazonaws.com"
          "token.actions.githubusercontent.com:sub" = "repo:${var.github_repo}:ref:refs/heads/${var.deploy_branch}"
        }
      }
    }]
  })
}

resource "aws_iam_role_policy" "github_deploy" {
  name = "${var.project}-github-deploy"
  role = aws_iam_role.github_deploy.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid      = "EcrLogin"
        Effect   = "Allow"
        Action   = "ecr:GetAuthorizationToken"
        Resource = "*"
      },
      {
        Sid    = "PushOurImages"
        Effect = "Allow"
        Action = [
          "ecr:BatchCheckLayerAvailability", "ecr:InitiateLayerUpload", "ecr:UploadLayerPart",
          "ecr:CompleteLayerUpload", "ecr:PutImage", "ecr:BatchGetImage",
        ]
        Resource = [for r in aws_ecr_repository.service : r.arn]
      },
      {
        Sid      = "UploadRelease"
        Effect   = "Allow"
        Action   = "s3:PutObject"
        Resource = "${aws_s3_bucket.artifacts.arn}/deploy/*"
      },
      {
        Sid    = "RunDeployOnOurInstance"
        Effect = "Allow"
        Action = "ssm:SendCommand"
        Resource = [
          local.instance_arn,
          "arn:aws:ssm:${var.region}::document/AWS-RunShellScript",
        ]
      },
      {
        Sid      = "ReadDeployResult"
        Effect   = "Allow"
        Action   = "ssm:GetCommandInvocation" # no resource-level permissions for this action
        Resource = "*"
      },
    ]
  })
}
