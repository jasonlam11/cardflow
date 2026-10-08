plugin "terraform" {
  enabled = true
  preset  = "recommended"
}

# AWS rules: invalid instance types/AMIs, deprecated arguments, etc.
plugin "aws" {
  enabled = true
  version = "0.49.0"
  source  = "github.com/terraform-linters/tflint-ruleset-aws"
}
