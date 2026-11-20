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
