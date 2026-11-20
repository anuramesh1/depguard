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

variable "image_tag" {
  description = "Container image tag to run (the tag you pushed to ECR)."
  type        = string
  default     = "latest"
}

variable "allowed_cidr" {
  description = "Who may call the demo, usually your current IP (x.x.x.x/32)."
  type        = string

  validation {
    condition     = can(cidrhost(var.allowed_cidr, 0))
    error_message = "allowed_cidr must be a CIDR block, e.g. 203.0.113.10/32."
  }
}

variable "cpu" {
  description = "Fargate task CPU units (512 = 0.5 vCPU)."
  type        = number
  default     = 512
}

variable "memory" {
  description = "Fargate task memory in MiB. 1 GB is comfortable for Spring Boot."
  type        = number
  default     = 1024
}

variable "desired_count" {
  description = "Number of running tasks. 1 for a demo; 2+ across AZs for production."
  type        = number
  default     = 1
}
