# API Gateway HTTP API: the public HTTPS URL in front of the Lambda.
#
# Chosen over a Lambda Function URL because it has built-in throttling and a free JWT authorizer. The URL is public,
# but every route except the health check needs a Cognito login; the throttle caps the rest.
# HTTPS comes free with the default execute-api domain.

resource "aws_apigatewayv2_api" "app" {
  name          = var.app_name
  protocol_type = "HTTP"
}

resource "aws_apigatewayv2_integration" "lambda" {
  api_id                 = aws_apigatewayv2_api.app.id
  integration_type       = "AWS_PROXY"
  integration_uri        = aws_lambda_alias.live.invoke_arn # the alias, so SnapStart is used
  payload_format_version = "2.0"                            # matches StreamLambdaHandler
  timeout_milliseconds   = 30000
}

# Paid and private routes need a valid Cognito access token, checked here before a Lambda even starts (and
# checked again, with roles, by Spring Security: defence in depth). The authorizer is free.
resource "aws_apigatewayv2_authorizer" "cognito" {
  api_id           = aws_apigatewayv2_api.app.id
  name             = "cognito"
  authorizer_type  = "JWT"
  identity_sources = ["$request.header.Authorization"]
  jwt_configuration {
    issuer   = local.cognito_issuer
    audience = [aws_cognito_user_pool_client.api.id] # matched against the access token's client_id
  }
}

# Every request needs a login, including unknown paths: a bot probing /wp-login.php is refused by API Gateway and never
# starts the Lambda. Only users created with scripts/users.sh can log in (Cognito sign-up is off).
resource "aws_apigatewayv2_route" "all" {
  api_id             = aws_apigatewayv2_api.app.id
  route_key          = "$default"
  target             = "integrations/${aws_apigatewayv2_integration.lambda.id}"
  authorization_type = "JWT"
  authorizer_id      = aws_apigatewayv2_authorizer.cognito.id
}

# The one public route: deploy.sh's health check. It returns only {"status":"UP"} or DOWN.
resource "aws_apigatewayv2_route" "health" {
  api_id    = aws_apigatewayv2_api.app.id
  route_key = "GET /actuator/health"
  target    = "integrations/${aws_apigatewayv2_integration.lambda.id}"
}

resource "aws_apigatewayv2_stage" "default" {
  api_id      = aws_apigatewayv2_api.app.id
  name        = "$default"
  auto_deploy = true

  default_route_settings {
    throttling_rate_limit  = var.throttle_rate_per_second
    throttling_burst_limit = var.throttle_burst
  }
}

# Allow API Gateway (this API only) to call the "live" alias.
resource "aws_lambda_permission" "api_gateway" {
  statement_id  = "AllowApiGatewayInvoke"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.app.function_name
  qualifier     = aws_lambda_alias.live.name
  principal     = "apigateway.amazonaws.com"
  source_arn    = "${aws_apigatewayv2_api.app.execution_arn}/*/*"
}
