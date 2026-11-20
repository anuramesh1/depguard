# Application Load Balancer: the public entry point. It health-checks the app and sends traffic
# only to healthy tasks.
#
# Demo choices: plain HTTP (no domain or certificate needed) and access limited to allowed_cidr.
# Production alternative: HTTPS listener with an ACM certificate, HTTP->HTTPS redirect, and AWS WAF.

resource "aws_security_group" "alb" {
  name        = "${var.app_name}-alb"
  description = "Inbound HTTP from the allowed CIDR only"
  vpc_id      = aws_vpc.main.id

  ingress {
    description = "HTTP from allowed caller"
    from_port   = 80
    to_port     = 80
    protocol    = "tcp"
    cidr_blocks = [var.allowed_cidr]
  }

  egress {
    description = "To the app tasks"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = [aws_vpc.main.cidr_block]
  }
}

resource "aws_lb" "app" {
  name                       = "${var.app_name}-alb"
  load_balancer_type         = "application"
  security_groups            = [aws_security_group.alb.id]
  subnets                    = aws_subnet.public[*].id
  drop_invalid_header_fields = true
}

resource "aws_lb_target_group" "app" {
  name        = "${var.app_name}-tg"
  port        = 8080
  protocol    = "HTTP"
  vpc_id      = aws_vpc.main.id
  target_type = "ip" # required for Fargate

  # Spring Boot Actuator health endpoint
  health_check {
    path                = "/actuator/health"
    matcher             = "200"
    interval            = 15
    healthy_threshold   = 2
    unhealthy_threshold = 3
  }

  deregistration_delay = 10 # faster deploys and teardown; fine for a demo
}

resource "aws_lb_listener" "http" {
  load_balancer_arn = aws_lb.app.arn
  port              = 80
  protocol          = "HTTP"

  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.app.arn
  }
}
