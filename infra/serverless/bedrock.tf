# Lets the Lambda call exactly two Bedrock models: the chat model (through a cross-region inference
# profile) and the embedding model that turns search questions into vectors.
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
  statement {
    sid       = "EmbedSearchQueries"
    actions   = ["bedrock:InvokeModel"]
    resources = ["arn:aws:bedrock:${var.region}::foundation-model/${var.embedding_model_id}"]
  }
}

resource "aws_iam_role_policy" "bedrock" {
  name   = "${var.app_name}-bedrock"
  role   = aws_iam_role.lambda.id
  policy = data.aws_iam_policy_document.bedrock.json
}

