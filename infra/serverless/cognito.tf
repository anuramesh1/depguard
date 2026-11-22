# Who can log in: an Amazon Cognito user pool. Users belong to a group that becomes their role in DepGuard:
#   VIEWER      scan, triage, search the knowledge base, propose upgrades
#   MAINTAINER  everything a viewer can, plus approve or reject upgrade pull requests
# Users are created with scripts/users.sh (no public sign-up). The "Lite" tier is free for this scale
# (pricing is per monthly active user, with a free allowance); machine-to-machine clients are avoided on purpose,
# because Cognito bills those per client and per token request.

resource "aws_cognito_user_pool" "users" {
  name           = "${var.app_name}-users"
  user_pool_tier = "LITE"

  admin_create_user_config {
    allow_admin_create_user_only = true # no self sign-up: an admin adds every user
  }

  password_policy {
    minimum_length    = 14
    require_lowercase = true
    require_uppercase = true
    require_numbers   = true
    require_symbols   = false
  }

  # Demo setting; production: deletion_protection = "ACTIVE" and MFA required for MAINTAINERs.
  deletion_protection = "INACTIVE"
}

resource "aws_cognito_user_group" "viewer" {
  name         = "VIEWER"
  user_pool_id = aws_cognito_user_pool.users.id
  description  = "Scan, triage, search, propose upgrades"
}

resource "aws_cognito_user_group" "maintainer" {
  name         = "MAINTAINER"
  user_pool_id = aws_cognito_user_pool.users.id
  description  = "Everything a viewer can, plus approve upgrade pull requests"
}

# The app client DepGuard accepts tokens from. Public client (no secret): users log in with username + password and
# get a short-lived access token. DepGuard checks the token's client_id against this client's ID.
resource "aws_cognito_user_pool_client" "api" {
  name                          = "${var.app_name}-api"
  user_pool_id                  = aws_cognito_user_pool.users.id
  generate_secret               = false
  explicit_auth_flows           = ["ALLOW_USER_PASSWORD_AUTH", "ALLOW_REFRESH_TOKEN_AUTH"]
  prevent_user_existence_errors = "ENABLED" # same error for "no such user" and "wrong password"
  access_token_validity         = 60        # minutes
  refresh_token_validity        = 1         # day
  token_validity_units {
    access_token  = "minutes"
    refresh_token = "days"
  }
}

locals {
  cognito_issuer = "https://cognito-idp.${var.region}.amazonaws.com/${aws_cognito_user_pool.users.id}"
}
