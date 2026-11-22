variable "region" {
  description = "AWS region to deploy into."
  type        = string
  default     = "us-east-1"
}

variable "app_name" {
  description = "Name prefix for every resource."
  type        = string
  default     = "depguard"
}

variable "package_path" {
  description = "Path to the Lambda zip built by './mvnw -Plambda package' (set by scripts/deploy.sh)."
  type        = string
  default     = "../../target/depguard-lambda.zip"
}

variable "model_id" {
  description = "Bedrock model (inference profile) the triage agent uses. Must be enabled for your account."
  type        = string
  default     = "us.anthropic.claude-haiku-4-5-20251001-v1:0"
}

variable "embedding_model_id" {
  description = "Bedrock embedding model for the RAG knowledge base (must match what ingestion used)."
  type        = string
  default     = "amazon.titan-embed-text-v2:0"
}

variable "aurora_engine_version" {
  description = "Aurora PostgreSQL version. 16.3+ supports scaling to 0 ACUs (auto-pause) and pgvector 0.8."
  type        = string
  default     = "16.9"
}

variable "langfuse_host" {
  description = "Langfuse Cloud region: https://cloud.langfuse.com (EU) or https://us.cloud.langfuse.com (US)."
  type        = string
  default     = "https://cloud.langfuse.com"
}

variable "memory_mb" {
  description = "Lambda memory. Lambda gives CPU in proportion to memory; 1024 MB suits Spring Boot."
  type        = number
  default     = 1024
}

variable "throttle_rate_per_second" {
  description = "Steady requests per second API Gateway allows. Caps the cost if someone hammers the public URL."
  type        = number
  default     = 5
}

variable "throttle_burst" {
  description = "Short burst of requests API Gateway allows above the steady rate."
  type        = number
  default     = 10
}

variable "github_repository" {
  description = "owner/name of the repository DepGuard opens upgrade pull requests on."
  type        = string
  default     = "anuramesh1/depguard-demo-app"
}

variable "secrets_version" {
  description = "SSM parameter versions (set by scripts/deploy.sh): changing a secret publishes a new Lambda version."
  type        = string
  default     = "none"
}

variable "max_concurrency" {
  description = "Most Lambdas that may run at once (reserved concurrency): a hard cost ceiling."
  type        = number
  default     = 5
}

variable "alert_email" {
  description = "Where alarms and budget alerts go (ALERT_EMAIL in .env). Empty: no email, no budget."
  type        = string
  default     = ""
}

variable "monthly_budget_usd" {
  description = "Monthly cost (whole account) above which the budget alert emails you."
  type        = number
  default     = 25
}
