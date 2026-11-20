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

# Phase 1 only needs to write logs. Phase 2 adds bedrock:InvokeModel: least privilege, granted when needed.
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
  filename         = var.package_path
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
    }
  }

  depends_on = [aws_cloudwatch_log_group.app, aws_iam_role_policy_attachment.logs]
}

resource "aws_lambda_alias" "live" {
  name             = "live"
  function_name    = aws_lambda_function.app.function_name
  function_version = aws_lambda_function.app.version
}
