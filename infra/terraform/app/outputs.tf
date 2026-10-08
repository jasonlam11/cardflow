output "dashboard_url" {
  value = "http://${aws_instance.app.public_ip}:${var.dashboard_port}"
}

output "instance_id" {
  value = aws_instance.app.id
}

output "ecr_registry" {
  value = local.registry
}

output "artifacts_bucket" {
  value = aws_s3_bucket.artifacts.bucket
}

output "github_deploy_role_arn" {
  description = "Set as the AWS_DEPLOY_ROLE_ARN repository variable to enable deploys"
  value       = aws_iam_role.github_deploy.arn
}
