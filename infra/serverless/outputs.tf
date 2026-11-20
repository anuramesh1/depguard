output "url" {
  description = "Base URL of the deployed app (HTTPS)."
  value       = aws_apigatewayv2_api.app.api_endpoint
}

output "function_name" {
  value = aws_lambda_function.app.function_name
}

output "live_version" {
  description = "Lambda version the 'live' alias points to (increases on every code change)."
  value       = aws_lambda_alias.live.function_version
}

output "log_group" {
  value = aws_cloudwatch_log_group.app.name
}
