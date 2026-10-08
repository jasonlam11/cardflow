# Artifacts bucket:
#   docs/           the assistant's benefits documents (uploaded by Terraform, synced by the instance)
#   deploy/<sha>/   compose files for one release (uploaded by the deploy workflow)

resource "aws_s3_bucket" "artifacts" {
  bucket = "${var.project}-artifacts-${local.account_id}"
}

resource "aws_s3_bucket_public_access_block" "artifacts" {
  bucket                  = aws_s3_bucket.artifacts.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_ownership_controls" "artifacts" {
  bucket = aws_s3_bucket.artifacts.id
  rule {
    object_ownership = "BucketOwnerEnforced"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "artifacts" {
  bucket = aws_s3_bucket.artifacts.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "artifacts" {
  bucket = aws_s3_bucket.artifacts.id
  rule {
    id     = "expire-old-releases"
    status = "Enabled"
    filter {
      prefix = "deploy/"
    }
    expiration {
      days = 30
    }
  }
}

resource "aws_s3_bucket_policy" "artifacts_tls_only" {
  bucket = aws_s3_bucket.artifacts.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Sid       = "DenyInsecureTransport"
      Effect    = "Deny"
      Principal = "*"
      Action    = "s3:*"
      Resource  = [aws_s3_bucket.artifacts.arn, "${aws_s3_bucket.artifacts.arn}/*"]
      Condition = { Bool = { "aws:SecureTransport" = "false" } }
    }]
  })
}

resource "aws_s3_object" "benefits_doc" {
  for_each = fileset(local.docs_dir, "*.md")

  bucket       = aws_s3_bucket.artifacts.id
  key          = "docs/${each.value}"
  source       = "${local.docs_dir}/${each.value}"
  etag         = filemd5("${local.docs_dir}/${each.value}")
  content_type = "text/markdown"
}
