# Security group = a firewall attached to the instance. AWS denies all inbound traffic unless
# a rule allows it, so the rules below are the complete list of what can reach the instance.
#
# - Inbound: only the dashboard port, only from var.allowed_cidr (your IP).
#   No SSH (port 22): shell access and deploys go through SSM, which needs no inbound port,
#   uses IAM for auth and logs every session.
#   Postgres, Kafka and the service APIs are not reachable from outside at all; they only
#   talk to each other on the Docker network inside the instance.
# - Outbound: HTTPS only, for pulling images (ECR), secrets (SSM), docs (S3) and Bedrock.
#   (DNS to the VPC resolver and the instance metadata service aren't filtered by security groups.)

resource "aws_security_group" "app" {
  name        = "${var.project}-app"
  description = "CardFlow: dashboard from one network only, no SSH"
  vpc_id      = data.aws_vpc.default.id
}

resource "aws_vpc_security_group_ingress_rule" "dashboard" {
  security_group_id = aws_security_group.app.id
  description       = "Dashboard (the only user-facing service)"
  ip_protocol       = "tcp"
  from_port         = var.dashboard_port
  to_port           = var.dashboard_port
  cidr_ipv4         = var.allowed_cidr
}

resource "aws_vpc_security_group_egress_rule" "https" {
  security_group_id = aws_security_group.app.id
  description       = "HTTPS to AWS APIs and package mirrors"
  ip_protocol       = "tcp"
  from_port         = 443
  to_port           = 443
  cidr_ipv4         = "0.0.0.0/0"
}
