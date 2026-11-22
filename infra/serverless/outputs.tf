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

# Used by scripts/ingest.sh aws to fill the knowledge base from your laptop through the Data API.
output "rag_cluster_arn" {
  value = aws_rds_cluster.knowledge.arn
}

output "rag_secret_arn" {
  value = aws_rds_cluster.knowledge.master_user_secret[0].secret_arn
}

# Login: scripts/users.sh and scripts/token.sh use these.
output "cognito_user_pool_id" {
  value = aws_cognito_user_pool.users.id
}

output "cognito_client_id" {
  value = aws_cognito_user_pool_client.api.id
}

output "alerts_topic" {
  description = "SNS topic the alarms notify (confirm the email subscription AWS sends you)."
  value       = aws_sns_topic.alerts.arn
}
