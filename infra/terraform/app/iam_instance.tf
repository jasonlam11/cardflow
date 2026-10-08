# The instance's identity. Containers on the instance get temporary credentials for this role
# from the instance metadata service, so no AWS keys exist anywhere on the box.
# Least privilege: each statement names exactly the resources it needs.

resource "aws_iam_role" "instance" {
  name = "${var.project}-instance"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "ec2.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })
}

# Lets the SSM agent register the instance, so deploys and shell sessions work without SSH
resource "aws_iam_role_policy_attachment" "ssm_core" {
  role       = aws_iam_role.instance.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
}

resource "aws_iam_role_policy" "instance" {
  name = "${var.project}-instance"
  role = aws_iam_role.instance.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid      = "EcrLogin"
        Effect   = "Allow"
        Action   = "ecr:GetAuthorizationToken" # account-wide by design; can't be scoped to a repo
        Resource = "*"
      },
      {
        Sid      = "PullOurImages"
        Effect   = "Allow"
        Action   = ["ecr:BatchGetImage", "ecr:GetDownloadUrlForLayer", "ecr:BatchCheckLayerAvailability"]
        Resource = [for r in aws_ecr_repository.service : r.arn]
      },
      {
        Sid      = "ReadOurParameters"
        Effect   = "Allow"
        Action   = ["ssm:GetParametersByPath", "ssm:GetParameter", "ssm:GetParameters"]
        Resource = ["arn:aws:ssm:${var.region}:${local.account_id}:parameter${local.ssm_prefix}", "arn:aws:ssm:${var.region}:${local.account_id}:parameter${local.ssm_prefix}/*"]
      },
      {
        Sid      = "ListArtifacts"
        Effect   = "Allow"
        Action   = "s3:ListBucket"
        Resource = aws_s3_bucket.artifacts.arn
      },
      {
        Sid      = "ReadArtifacts"
        Effect   = "Allow"
        Action   = "s3:GetObject"
        Resource = "${aws_s3_bucket.artifacts.arn}/*"
      },
      {
        Sid    = "InvokeAssistantModel"
        Effect = "Allow"
        Action = "bedrock:InvokeModel"
        # A global inference profile routes to the model in any region, so the foundation-model
        # ARN uses a region wildcard; the model itself is pinned
        Resource = [
          "arn:aws:bedrock:${var.region}:${local.account_id}:inference-profile/${var.bedrock_model_id}",
          "arn:aws:bedrock:*::foundation-model/${trimprefix(var.bedrock_model_id, "global.")}",
        ]
      },
    ]
  })
}

resource "aws_iam_instance_profile" "instance" {
  name = "${var.project}-instance"
  role = aws_iam_role.instance.name
}
