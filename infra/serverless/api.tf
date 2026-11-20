# API Gateway HTTP API: the public HTTPS URL in front of the Lambda.
#
# Chosen over a Lambda Function URL because it has built-in throttling. The URL is public, so the
# throttle caps what anyone could cost you by hammering it.
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

# Send every path and method to Spring; its controllers do the routing.
resource "aws_apigatewayv2_route" "all" {
  api_id    = aws_apigatewayv2_api.app.id
  route_key = "$default"
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
