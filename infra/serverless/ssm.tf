# Read access to DepGuard's own secrets in SSM Parameter Store (/depguard/...), and nothing else.
#
# The parameters themselves are NOT managed here: scripts/deploy.sh writes them from .env with the AWS CLI before
# applying, so secret values never appear in Terraform files or state. SecureString parameters use the AWS-managed
# key aws/ssm (free). Parameter Store is used instead of Secrets Manager because it is free and enough here: no
# automatic rotation is needed for a Langfuse key or a GitHub token.

data "aws_iam_policy_document" "ssm" {
  statement {
    sid     = "ReadOwnParameters"
    actions = ["ssm:GetParametersByPath", "ssm:GetParameters", "ssm:GetParameter"]
    resources = ["arn:aws:ssm:${var.region}:${data.aws_caller_identity.current.account_id}:parameter/${var.app_name}/*",
    "arn:aws:ssm:${var.region}:${data.aws_caller_identity.current.account_id}:parameter/${var.app_name}"]
  }
  statement {
    sid       = "DecryptThroughSsmOnly"
    actions   = ["kms:Decrypt"]
    resources = ["*"] # the AWS-managed aws/ssm key; the condition limits use to SSM in this region
    condition {
      test     = "StringEquals"
      variable = "kms:ViaService"
      values   = ["ssm.${var.region}.amazonaws.com"]
    }
  }
}

resource "aws_iam_role_policy" "ssm" {
  name   = "${var.app_name}-ssm"
  role   = aws_iam_role.lambda.id
  policy = data.aws_iam_policy_document.ssm.json
}
