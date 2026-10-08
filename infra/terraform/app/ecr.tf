# One private image repository per service. Images are tagged with the git commit SHA.

resource "aws_ecr_repository" "service" {
  for_each = var.services

  name                 = "${var.project}/${each.key}"
  image_tag_mutability = "IMMUTABLE" # a tag (commit SHA) always means the same image

  image_scanning_configuration {
    scan_on_push = true # free basic CVE scan of every pushed image
  }

  encryption_configuration {
    encryption_type = "AES256"
  }
}

# Storage is billed per GB-month, so old images are expired automatically
resource "aws_ecr_lifecycle_policy" "keep_recent" {
  for_each   = aws_ecr_repository.service
  repository = each.value.name

  policy = jsonencode({
    rules = [{
      rulePriority = 1
      description  = "Keep the ${var.images_to_keep} most recent images"
      selection = {
        tagStatus   = "any"
        countType   = "imageCountMoreThan"
        countNumber = var.images_to_keep
      }
      action = { type = "expire" }
    }]
  })
}
