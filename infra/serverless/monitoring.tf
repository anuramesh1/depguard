# Production hardening, kept free: alarms, a budget, and a hard cap on concurrent Lambdas.
#   - 4 CloudWatch alarms (the first 10 alarms are free) notify an SNS topic, which emails you (SNS email is free).
#   - 1 AWS Budget (the first 2 are free) emails at 80% of the monthly limit and when the forecast passes it.
#   - Reserved concurrency (lambda.tf) caps simultaneous Lambdas, so no traffic spike can run up an unbounded bill.
# The email address comes from ALERT_EMAIL in .env (scripts/deploy.sh passes it); it is never committed.
# AWS sends a confirmation email for the SNS subscription: alarms only reach you after you click it.

resource "aws_sns_topic" "alerts" {
  name = "${var.app_name}-alerts"
}

resource "aws_sns_topic_subscription" "email" {
  count     = var.alert_email == "" ? 0 : 1
  topic_arn = aws_sns_topic.alerts.arn
  protocol  = "email"
  endpoint  = var.alert_email
}

locals {
  alarm_actions = [aws_sns_topic.alerts.arn]
}

# Server errors returned to callers (Lambda crashes, timeouts at API Gateway's 30 s).
resource "aws_cloudwatch_metric_alarm" "api_5xx" {
  alarm_name          = "${var.app_name}-api-5xx"
  alarm_description   = "DepGuard returned 5 or more server errors in 5 minutes."
  namespace           = "AWS/ApiGateway"
  metric_name         = "5xx"
  dimensions          = { ApiId = aws_apigatewayv2_api.app.id, Stage = "$default" }
  statistic           = "Sum"
  period              = 300
  evaluation_periods  = 1
  threshold           = 5
  comparison_operator = "GreaterThanOrEqualToThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = local.alarm_actions
}

resource "aws_cloudwatch_metric_alarm" "lambda_errors" {
  alarm_name          = "${var.app_name}-lambda-errors"
  alarm_description   = "The DepGuard Lambda failed 5 or more invocations in 5 minutes."
  namespace           = "AWS/Lambda"
  metric_name         = "Errors"
  dimensions          = { FunctionName = aws_lambda_function.app.function_name }
  statistic           = "Sum"
  period              = 300
  evaluation_periods  = 1
  threshold           = 5
  comparison_operator = "GreaterThanOrEqualToThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = local.alarm_actions
}

# Slow triages: p95 over 20 s for 15 minutes means users are close to API Gateway's 30 s limit.
resource "aws_cloudwatch_metric_alarm" "lambda_slow" {
  alarm_name          = "${var.app_name}-lambda-p95-duration"
  alarm_description   = "DepGuard's p95 duration was over 20 s for 15 minutes."
  namespace           = "AWS/Lambda"
  metric_name         = "Duration"
  dimensions          = { FunctionName = aws_lambda_function.app.function_name }
  extended_statistic  = "p95"
  period              = 300
  evaluation_periods  = 3
  threshold           = 20000
  comparison_operator = "GreaterThanOrEqualToThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = local.alarm_actions
}

# Throttles: requests refused because the concurrency cap was reached (traffic spike, or abuse).
resource "aws_cloudwatch_metric_alarm" "lambda_throttles" {
  alarm_name          = "${var.app_name}-lambda-throttles"
  alarm_description   = "DepGuard hit its concurrency cap: requests were refused."
  namespace           = "AWS/Lambda"
  metric_name         = "Throttles"
  dimensions          = { FunctionName = aws_lambda_function.app.function_name }
  statistic           = "Sum"
  period              = 300
  evaluation_periods  = 1
  threshold           = 1
  comparison_operator = "GreaterThanOrEqualToThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = local.alarm_actions
}

# Whole-account monthly cost (Bedrock included), not just DepGuard's: AWS Budgets can't filter Bedrock usage by app.
resource "aws_budgets_budget" "monthly" {
  count        = var.alert_email == "" ? 0 : 1
  name         = "${var.app_name}-monthly"
  budget_type  = "COST"
  limit_amount = tostring(var.monthly_budget_usd)
  limit_unit   = "USD"
  time_unit    = "MONTHLY"

  notification {
    comparison_operator        = "GREATER_THAN"
    threshold                  = 80
    threshold_type             = "PERCENTAGE"
    notification_type          = "ACTUAL"
    subscriber_email_addresses = [var.alert_email]
  }

  notification {
    comparison_operator        = "GREATER_THAN"
    threshold                  = 100
    threshold_type             = "PERCENTAGE"
    notification_type          = "FORECASTED"
    subscriber_email_addresses = [var.alert_email]
  }
}
