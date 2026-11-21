# Lets GitHub Actions run DepGuard's evals with short-lived AWS credentials (OIDC), instead of stored access keys.
#
# Not applied by any script. To enable evals in CI (see README.md in this folder):
#   terraform -chdir=infra/ci init && terraform -chdir=infra/ci apply
#   then set the repository variable AWS_EVAL_ROLE_ARN to the "role_arn" output.
#
# The role can only be assumed by workflows on this repository's main branch, and can only call the three
# Bedrock models the evals use. Nothing else.

terraform {
  required_version = ">= 1.6"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.73"
    }
  }
}

provider "aws" {
  region = var.region
}

variable "region" {
  type    = string
  default = "us-east-1"
}

variable "github_repository" {
  description = "owner/name of the GitHub repository allowed to assume the role."
  type        = string
  default     = "anuramesh1/depguard"
}

variable "create_oidc_provider" {
  description = "An AWS account can have only one GitHub OIDC provider. Set false if it already exists."
  type        = bool
  default     = true
}

data "aws_caller_identity" "current" {}

resource "aws_iam_openid_connect_provider" "github" {
  count          = var.create_oidc_provider ? 1 : 0
  url            = "https://token.actions.githubusercontent.com"
  client_id_list = ["sts.amazonaws.com"]
}

locals {
  oidc_provider_arn = var.create_oidc_provider ? aws_iam_openid_connect_provider.github[0].arn : "arn:aws:iam::${data.aws_caller_identity.current.account_id}:oidc-provider/token.actions.githubusercontent.com"
}

data "aws_iam_policy_document" "trust" {
  statement {
    actions = ["sts:AssumeRoleWithWebIdentity"]
    principals {
      type        = "Federated"
      identifiers = [local.oidc_provider_arn]
    }
    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:aud"
      values   = ["sts.amazonaws.com"]
    }
    # Only workflows running on main in this repository (not forks, not other branches).
    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:sub"
      values   = ["repo:${var.github_repository}:ref:refs/heads/main"]
    }
  }
}

resource "aws_iam_role" "evals" {
  name                 = "depguard-github-evals"
  assume_role_policy   = data.aws_iam_policy_document.trust.json
  max_session_duration = 3600
}

# The agent (Claude Haiku 4.5 via a US inference profile), the judge (Nova Pro via a US inference
# profile) and the embedding model (Titan Text v2).
data "aws_iam_policy_document" "bedrock" {
  statement {
    actions = ["bedrock:InvokeModel", "bedrock:InvokeModelWithResponseStream"]
    resources = [
      "arn:aws:bedrock:${var.region}:${data.aws_caller_identity.current.account_id}:inference-profile/us.anthropic.claude-haiku-4-5-20251001-v1:0",
      "arn:aws:bedrock:*::foundation-model/anthropic.claude-haiku-4-5-20251001-v1:0",
      "arn:aws:bedrock:${var.region}:${data.aws_caller_identity.current.account_id}:inference-profile/us.amazon.nova-pro-v1:0",
      "arn:aws:bedrock:*::foundation-model/amazon.nova-pro-v1:0",
      "arn:aws:bedrock:${var.region}::foundation-model/amazon.titan-embed-text-v2:0",
    ]
  }
}

resource "aws_iam_role_policy" "bedrock" {
  name   = "bedrock-eval-models"
  role   = aws_iam_role.evals.id
  policy = data.aws_iam_policy_document.bedrock.json
}

output "role_arn" {
  description = "Set this as the GitHub repository variable AWS_EVAL_ROLE_ARN."
  value       = aws_iam_role.evals.arn
}
