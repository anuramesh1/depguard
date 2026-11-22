# The Spring Boot app as one Lambda function, with SnapStart.
#
# How SnapStart works: when a version is published, Lambda starts the function once (Spring boots
# during init, see StreamLambdaHandler), snapshots the memory, and restores that snapshot on every
# cold start. SnapStart only applies to published versions, so API Gateway calls the "live" alias,
# which always points at the newest version. SnapStart is free for Java.

resource "aws_cloudwatch_log_group" "app" {
  name              = "/aws/lambda/${var.app_name}"
  retention_in_days = 7 # demo logs don't need to live long
}

data "aws_iam_policy_document" "lambda_assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["lambda.amazonaws.com"]
    }
  }
}

# Least privilege: write its own logs, call two Bedrock models, run SQL on one Aurora cluster, read its own SSM parameters.
resource "aws_iam_role" "lambda" {
  name               = "${var.app_name}-lambda"
  assume_role_policy = data.aws_iam_policy_document.lambda_assume.json
}

resource "aws_iam_role_policy_attachment" "logs" {
  role       = aws_iam_role.lambda.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole"
}

resource "aws_lambda_function" "app" {
  function_name    = var.app_name
  role             = aws_iam_role.lambda.arn
  runtime          = "java21"
  architectures    = ["arm64"] # Graviton: about 20% cheaper per GB-second than x86
  handler          = "dev.depguard.lambda.StreamLambdaHandler::handleRequest"
  s3_bucket        = aws_s3_object.package.bucket
  s3_key           = aws_s3_object.package.key
  source_code_hash = filebase64sha256(var.package_path) # new code -> new version
  memory_size      = var.memory_mb
  timeout          = 30   # API Gateway HTTP APIs wait at most 30 s anyway
  publish          = true # SnapStart needs published versions
  # Hard cap on simultaneous executions: with API Gateway's throttle, this bounds what a traffic spike can cost.
  reserved_concurrent_executions = var.max_concurrency

  snap_start {
    apply_on = "PublishedVersions"
  }

  environment {
    variables = {
      # Faster JIT warm-up for short-lived functions (AWS's recommended setting for Java on Lambda).
      JAVA_TOOL_OPTIONS = "-XX:+TieredCompilation -XX:TieredStopAtLevel=1"
      # Which Bedrock model the agent uses (Spring's relaxed binding of spring.ai.bedrock...model).
      SPRING_AI_BEDROCK_CONVERSE_CHAT_OPTIONS_MODEL = var.model_id
      # Login: tokens from this Cognito user pool and app client (see cognito.tf and SecurityConfig).
      DEPGUARD_AUTH_ISSUER_URI = local.cognito_issuer
      DEPGUARD_AUTH_CLIENT_ID  = aws_cognito_user_pool_client.api.id
      # Secrets (Langfuse keys, GitHub token) are read from SSM Parameter Store at startup, not set here.
      DEPGUARD_SSM_PATH = "/${var.app_name}/"
      # Changes whenever a secret changes (deploy.sh passes the SSM parameter versions), so a new Lambda version and
      # SnapStart snapshot are made with the new values. It contains version numbers, never the secrets.
      DEPGUARD_SECRETS_VERSION   = var.secrets_version
      DEPGUARD_GITHUB_REPOSITORY = var.github_repository
      # RAG knowledge base: Aurora through the RDS Data API (see aurora.tf)
      DEPGUARD_RAG_STORE                      = "data-api"
      DEPGUARD_RAG_CLUSTER_ARN                = aws_rds_cluster.knowledge.arn
      DEPGUARD_RAG_SECRET_ARN                 = aws_rds_cluster.knowledge.master_user_secret[0].secret_arn
      SPRING_AI_BEDROCK_TITAN_EMBEDDING_MODEL = var.embedding_model_id
      # Tracing to Langfuse (keys from SSM). Lambda freezes the process after each response, so spans must be
      # flushed before it returns (see TraceFlushFilter).
      LANGFUSE_HOST                = var.langfuse_host
      LANGFUSE_FLUSH_AFTER_REQUEST = "true"
    }
  }

  depends_on = [aws_cloudwatch_log_group.app, aws_iam_role_policy_attachment.logs, aws_iam_role_policy.bedrock,
  aws_iam_role_policy.data_api, aws_iam_role_policy.ssm]
}

resource "aws_lambda_alias" "live" {
  name             = "live"
  function_name    = aws_lambda_function.app.function_name
  function_version = aws_lambda_function.app.version
}
