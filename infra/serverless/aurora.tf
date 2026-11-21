# The RAG knowledge base: Aurora Serverless v2 (PostgreSQL + pgvector), used through the RDS Data API.
#
# Why this shape:
# - Data API: the app talks to the database over HTTPS with IAM, so the Lambda stays OUT of the VPC and
#   keeps reaching OSV/EPSS/CISA directly (a Lambda inside a VPC would need a ~$32/month NAT gateway).
# - Min capacity 0 + auto-pause: the database costs nothing for compute while idle. The first query after a
#   pause waits ~15 s for it to resume; the agent's search tool reports "unavailable" instead of hanging.
# - The VPC below exists only because Aurora must live in one. It has no internet gateway, no NAT and no
#   inbound rules: nothing can reach the database except through the Data API.

data "aws_availability_zones" "available" {
  state = "available"
}

resource "aws_vpc" "db" {
  cidr_block = "10.42.0.0/16"
  tags       = { Name = "${var.app_name}-db" }
}

# Aurora needs subnets in at least two availability zones.
resource "aws_subnet" "db" {
  count             = 2
  vpc_id            = aws_vpc.db.id
  cidr_block        = cidrsubnet(aws_vpc.db.cidr_block, 8, count.index)
  availability_zone = data.aws_availability_zones.available.names[count.index]
  tags              = { Name = "${var.app_name}-db-${count.index}" }
}

resource "aws_db_subnet_group" "db" {
  name       = "${var.app_name}-db"
  subnet_ids = aws_subnet.db[*].id
}

# No ingress rules: the Data API doesn't connect through the VPC.
resource "aws_security_group" "db" {
  name        = "${var.app_name}-db"
  description = "Aurora for DepGuard; reached only through the RDS Data API"
  vpc_id      = aws_vpc.db.id
}

resource "aws_rds_cluster" "knowledge" {
  cluster_identifier = "${var.app_name}-knowledge"
  engine             = "aurora-postgresql"
  engine_version     = var.aurora_engine_version
  engine_mode        = "provisioned" # Serverless v2 uses "provisioned" plus db.serverless instances
  database_name      = "depguard"
  master_username    = "depguard"
  # AWS generates the password and keeps it in Secrets Manager (~$0.40/month); it never appears in Terraform.
  manage_master_user_password = true
  enable_http_endpoint        = true # turns on the RDS Data API

  db_subnet_group_name   = aws_db_subnet_group.db.name
  vpc_security_group_ids = [aws_security_group.db.id]
  storage_encrypted      = true

  serverlessv2_scaling_configuration {
    min_capacity             = 0   # pause when idle: $0 compute
    max_capacity             = 2   # plenty for a demo; caps the cost of a busy hour
    seconds_until_auto_pause = 600 # pause after 10 idle minutes
  }

  # Demo settings: easy to tear down. In production keep a final snapshot and deletion protection.
  skip_final_snapshot = true
  deletion_protection = false
}

resource "aws_rds_cluster_instance" "knowledge" {
  identifier         = "${var.app_name}-knowledge-1"
  cluster_identifier = aws_rds_cluster.knowledge.id
  instance_class     = "db.serverless"
  engine             = aws_rds_cluster.knowledge.engine
  engine_version     = aws_rds_cluster.knowledge.engine_version
}

# The Lambda may run SQL through the Data API on this cluster only, using this cluster's secret only.
data "aws_iam_policy_document" "data_api" {
  statement {
    sid       = "RunSqlOnKnowledgeBase"
    actions   = ["rds-data:ExecuteStatement", "rds-data:BatchExecuteStatement"]
    resources = [aws_rds_cluster.knowledge.arn]
  }
  statement {
    sid       = "ReadDatabaseSecret"
    actions   = ["secretsmanager:GetSecretValue"]
    resources = [aws_rds_cluster.knowledge.master_user_secret[0].secret_arn]
  }
}

resource "aws_iam_role_policy" "data_api" {
  name   = "${var.app_name}-data-api"
  role   = aws_iam_role.lambda.id
  policy = data.aws_iam_policy_document.data_api.json
}
