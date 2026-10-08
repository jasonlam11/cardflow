output "state_bucket" {
  description = "Pass to the app stack: terraform init -backend-config=\"bucket=<this>\""
  value       = aws_s3_bucket.state.bucket
}

output "github_oidc_provider_arn" {
  value = aws_iam_openid_connect_provider.github.arn
}
