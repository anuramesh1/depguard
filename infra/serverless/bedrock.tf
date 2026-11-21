# Lets the Lambda call exactly one Bedrock model, through a cross-region inference profile.
#
# An inference profile (e.g. "us.anthropic.claude-haiku-4-5-...") routes each request to the model in one of
# several US regions, so the policy must allow the profile itself AND the underlying foundation model in
# any region the profile can route to.

data "aws_caller_identity" "current" {}

locals {
  # "us.anthropic.claude-haiku-4-5-20251001-v1:0" -> "anthropic.claude-haiku-4-5-20251001-v1:0"
  foundation_model_id = replace(var.model_id, "/^(us|eu|apac|global)\\./", "")
}

data "aws_iam_policy_document" "bedrock" {
  statement {
    sid     = "InvokeTriageModel"
    actions = ["bedrock:InvokeModel", "bedrock:InvokeModelWithResponseStream"]
    resources = [
      "arn:aws:bedrock:${var.region}:${data.aws_caller_identity.current.account_id}:inference-profile/${var.model_id}",
      "arn:aws:bedrock:*::foundation-model/${local.foundation_model_id}",
    ]
  }
}

resource "aws_iam_role_policy" "bedrock" {
  name   = "${var.app_name}-bedrock"
  role   = aws_iam_role.lambda.id
  policy = data.aws_iam_policy_document.bedrock.json
}

# API key for /api/triage. Generated once and kept in the (local, gitignored) Terraform state.
# Read it with: terraform -chdir=infra/serverless output -raw api_key
resource "random_password" "api_key" {
  length  = 40
  special = false
}
