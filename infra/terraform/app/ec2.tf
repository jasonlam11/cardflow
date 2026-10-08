# One Graviton (ARM) instance running the whole Docker Compose stack. ADR 0014 explains why
# this instead of ECS/EKS/MSK/RDS. Stop it between demos: a stopped instance costs only its disk.

resource "aws_instance" "app" {
  ami                         = data.aws_ssm_parameter.al2023_arm64.insecure_value
  instance_type               = var.instance_type
  subnet_id                   = sort(data.aws_subnets.default.ids)[0]
  vpc_security_group_ids      = [aws_security_group.app.id]
  iam_instance_profile        = aws_iam_instance_profile.instance.name
  associate_public_ip_address = true

  user_data = templatefile("${path.module}/templates/user_data.sh.tftpl", {
    region     = var.region
    ssm_prefix = local.ssm_prefix
  })

  # Burstable instances default to "unlimited" on T4g, which can bill for extra CPU credits.
  # "standard" caps usage at the earned credits instead: slower under sustained load, never a surprise bill.
  credit_specification {
    cpu_credits = "standard"
  }

  # IMDSv2 only (blocks the SSRF-style credential theft IMDSv1 allows). Hop limit 2 so containers
  # (one network hop behind the host) can still get the role's temporary credentials.
  metadata_options {
    http_endpoint               = "enabled"
    http_tokens                 = "required"
    http_put_response_hop_limit = 2
  }

  root_block_device {
    volume_type           = "gp3"
    volume_size           = var.root_volume_gb
    encrypted             = true
    delete_on_termination = true
  }

  tags = {
    Name = "${var.project}-app"
  }

  lifecycle {
    # A newly published AMI shouldn't replace the running instance on the next apply
    ignore_changes = [ami, user_data]
  }
}
