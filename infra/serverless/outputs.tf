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

output "api_key" {
  description = "Send as the X-Api-Key header to call /api/triage and /api/knowledge."
  value       = random_password.api_key.result
  sensitive   = true
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
