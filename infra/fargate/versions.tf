terraform {
  required_version = ">= 1.6"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.73"
    }
  }

  # Local state on purpose: a one-person demo that is created and destroyed often.
  # For production, use a remote backend (S3 with locking). See README "Design alternatives".
}

provider "aws" {
  region = var.region

  # Every resource gets these tags, so the demo's cost is easy to find in Cost Explorer.
  default_tags {
    tags = {
      Project   = var.app_name
      ManagedBy = "terraform"
      Purpose   = "demo"
    }
  }
}
