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

# Least privilege: write its own logs, call two Bedrock models, and run SQL on one Aurora cluster.
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

  snap_start {
    apply_on = "PublishedVersions"
  }

  environment {
    variables = {
      # Faster JIT warm-up for short-lived functions (AWS's recommended setting for Java on Lambda).
      JAVA_TOOL_OPTIONS = "-XX:+TieredCompilation -XX:TieredStopAtLevel=1"
      # Which Bedrock model the agent uses (Spring's relaxed binding of spring.ai.bedrock...model).
      SPRING_AI_BEDROCK_CONVERSE_CHAT_OPTIONS_MODEL = var.model_id
      # Callers of /api/triage and /api/knowledge must send this as the X-Api-Key header (see ApiKeyFilter).
      DEPGUARD_API_KEY = random_password.api_key.result
      # RAG knowledge base: Aurora through the RDS Data API (see aurora.tf)
      DEPGUARD_RAG_STORE                      = "data-api"
      DEPGUARD_RAG_CLUSTER_ARN                = aws_rds_cluster.knowledge.arn
      DEPGUARD_RAG_SECRET_ARN                 = aws_rds_cluster.knowledge.master_user_secret[0].secret_arn
      SPRING_AI_BEDROCK_TITAN_EMBEDDING_MODEL = var.embedding_model_id
      # Tracing to Langfuse (off when the keys are empty). Lambda freezes the process after each response,
      # so spans must be flushed before it returns (see TraceFlushFilter).
      # Demo trade-off: the secret key sits in the Lambda configuration and the local Terraform state (both
      # encrypted at rest). Production: keep it in Secrets Manager and read it at startup.
      LANGFUSE_HOST                = var.langfuse_host
      LANGFUSE_PUBLIC_KEY          = var.langfuse_public_key
      LANGFUSE_SECRET_KEY          = var.langfuse_secret_key
      LANGFUSE_FLUSH_AFTER_REQUEST = "true"
    }
  }

  depends_on = [aws_cloudwatch_log_group.app, aws_iam_role_policy_attachment.logs, aws_iam_role_policy.bedrock,
  aws_iam_role_policy.data_api]
}

resource "aws_lambda_alias" "live" {
  name             = "live"
  function_name    = aws_lambda_function.app.function_name
  function_version = aws_lambda_function.app.version
}
