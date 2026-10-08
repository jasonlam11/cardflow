variable "region" {
  type    = string
  default = "us-east-1"
}

variable "project" {
  type    = string
  default = "cardflow"
}

variable "github_repo" {
  description = "owner/name of the only repository allowed to deploy"
  type        = string
  default     = "jasonlam11/cardflow"
}

variable "deploy_branch" {
  description = "Only workflows running on this branch can assume the deploy role"
  type        = string
  default     = "main"
}

variable "allowed_cidr" {
  description = "The one network allowed to reach the dashboard, e.g. your IP as 203.0.113.7/32"
  type        = string

  validation {
    condition     = can(cidrhost(var.allowed_cidr, 0))
    error_message = "allowed_cidr must be a CIDR block, e.g. 203.0.113.7/32."
  }
  validation {
    # The dashboard has no login and can approve or reject reviews: never open it to everyone
    condition     = !can(cidrhost(var.allowed_cidr, 0)) || tonumber(split("/", var.allowed_cidr)[1]) >= 24
    error_message = "allowed_cidr must be /24 or narrower; the dashboard must not be open to the internet."
  }
}

variable "instance_type" {
  description = "8 GB fits Kafka, Postgres, three JVMs and the Python services (~3.8 GB measured locally)"
  type        = string
  default     = "t4g.large"
}

variable "root_volume_gb" {
  type    = number
  default = 30
}

variable "dashboard_port" {
  type    = number
  default = 3000
}

variable "services" {
  description = "One ECR repository per image"
  type        = set(string)
  default = [
    "ledger-service", "authorization-service", "fraud-service",
    "assistant-service", "dashboard", "simulator",
  ]
}

variable "images_to_keep" {
  description = "ECR lifecycle: keep this many most recent images per repository"
  type        = number
  default     = 5
}

variable "bedrock_model_id" {
  description = "Bedrock inference profile for the assistant (global = no regional price premium)"
  type        = string
  default     = "global.anthropic.claude-haiku-4-5-20251001-v1:0"
}
