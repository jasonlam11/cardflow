variable "region" {
  description = "AWS region for everything CardFlow creates"
  type        = string
  default     = "us-east-1"
}

variable "budget_email" {
  description = "Where AWS Budgets sends cost alerts"
  type        = string

  validation {
    condition     = can(regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$", var.budget_email))
    error_message = "budget_email must be an email address."
  }
}

variable "monthly_budget_usd" {
  description = "Monthly cost budget; alerts fire at 50% and 100% (actual) and 100% (forecast)"
  type        = number
  default     = 10
}
