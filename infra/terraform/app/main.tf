data "aws_caller_identity" "current" {}

# The default VPC and its public subnets: no NAT gateway (~$33/month) or load balancer
# (~$16/month) needed for one instance. See ADR 0014.
data "aws_vpc" "default" {
  default = true
}

data "aws_subnets" "default" {
  filter {
    name   = "vpc-id"
    values = [data.aws_vpc.default.id]
  }
  filter {
    name   = "default-for-az"
    values = ["true"]
  }
}

# AWS publishes the latest Amazon Linux 2023 image id here; ARM64 to match the Graviton instance
data "aws_ssm_parameter" "al2023_arm64" {
  name = "/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-arm64"
}

locals {
  account_id   = data.aws_caller_identity.current.account_id
  ssm_prefix   = "/${var.project}"
  registry     = "${local.account_id}.dkr.ecr.${var.region}.amazonaws.com"
  docs_dir     = "${path.module}/../../../services/assistant-service/docs/benefits"
  instance_arn = "arn:aws:ec2:${var.region}:${local.account_id}:instance/${aws_instance.app.id}"
}
