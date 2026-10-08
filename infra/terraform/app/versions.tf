terraform {
  required_version = ">= 1.11"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.68"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.8"
    }
  }

  # Remote state in the bucket the bootstrap stack created. Partial config: the bucket name is
  # passed at init time (terraform init -backend-config="bucket=cardflow-tfstate-<account>"),
  # so no account id is committed. use_lockfile = native S3 locking (no DynamoDB).
  backend "s3" {
    key          = "app/terraform.tfstate"
    region       = "us-east-1"
    encrypt      = true
    use_lockfile = true
  }
}

provider "aws" {
  region = var.region

  default_tags {
    tags = {
      project    = var.project
      managed_by = "terraform"
      stack      = "app"
    }
  }
}
