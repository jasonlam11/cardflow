terraform {
  # 1.11+: native S3 state locking (use_lockfile), no DynamoDB table needed
  required_version = ">= 1.11"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.68"
    }
  }

  # Bootstrap state stays local: this stack creates the bucket every other stack stores its state in.
  # It holds no secrets, and rerunning it is safe (everything is imported or recreated by name).
}

provider "aws" {
  region = var.region

  default_tags {
    tags = {
      project    = "cardflow"
      managed_by = "terraform"
      stack      = "bootstrap"
    }
  }
}
