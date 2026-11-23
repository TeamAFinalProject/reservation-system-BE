locals {
  name           = "teama-booking-${var.environment}"
  container_port = 8080
}

resource "aws_lb" "booking" {
  name                       = local.name
  internal                   = true
  load_balancer_type         = "application"
  subnets                    = values(data.aws_subnet.alb)[*].id
  security_groups            = [data.aws_security_group.existing["alb"].id]
  enable_deletion_protection = true

  lifecycle {
    precondition {
      condition     = length(toset(values(data.aws_subnet.alb)[*].availability_zone_id)) == 2
      error_message = "The ALB subnets must be in two different Availability Zones."
    }
  }
}

resource "aws_lb_target_group" "booking" {
  name        = local.name
  vpc_id      = data.aws_vpc.existing.id
  target_type = "ip"
  port        = local.container_port
  protocol    = "HTTP"

  health_check {
    path    = "/actuator/health"
    matcher = "200"
  }
}

resource "aws_lb_listener" "http" {
  load_balancer_arn = aws_lb.booking.arn
  port              = 80
  protocol          = "HTTP"

  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.booking.arn
  }
}

resource "aws_cloudwatch_log_group" "booking" {
  name              = "/ecs/${local.name}"
  retention_in_days = 14
}

resource "aws_ecs_cluster" "booking" {
  name = local.name
}

resource "aws_ecs_task_definition" "booking" {
  family                   = local.name
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = "256"
  memory                   = "512"
  execution_role_arn       = data.aws_iam_role.execution.arn

  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "X86_64"
  }

  container_definitions = jsonencode([{
    name      = "booking"
    image     = "${data.aws_ecr_repository.booking.repository_url}@${data.aws_ecr_image.booking.image_digest}"
    essential = true

    portMappings = [{ containerPort = local.container_port, protocol = "tcp" }]
    environment  = [{ name = "SPRING_PROFILES_ACTIVE", value = "prod" }]
    secrets = [
      for name, selector in var.db_secret_value_from : { name = name, valueFrom = selector }
    ]

    logConfiguration = {
      logDriver = "awslogs"
      options = {
        awslogs-group         = aws_cloudwatch_log_group.booking.name
        awslogs-region        = var.aws_region
        awslogs-stream-prefix = "booking"
      }
    }
  }])
}

resource "aws_ecs_service" "booking" {
  name                              = local.name
  cluster                           = aws_ecs_cluster.booking.arn
  task_definition                   = aws_ecs_task_definition.booking.arn
  launch_type                       = "FARGATE"
  platform_version                  = "1.4.0"
  desired_count                     = var.desired_count
  health_check_grace_period_seconds = 120

  deployment_minimum_healthy_percent = 100
  deployment_maximum_percent         = 200

  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }

  network_configuration {
    subnets          = values(data.aws_subnet.app)[*].id
    security_groups  = [data.aws_security_group.existing["task"].id]
    assign_public_ip = false
  }

  load_balancer {
    target_group_arn = aws_lb_target_group.booking.arn
    container_name   = "booking"
    container_port   = local.container_port
  }

  depends_on = [aws_lb_listener.http, aws_vpc_endpoint.s3, aws_vpc_endpoint.interface]

  lifecycle {
    precondition {
      condition     = toset(values(data.aws_subnet.app)[*].availability_zone_id) == toset(values(data.aws_subnet.alb)[*].availability_zone_id)
      error_message = "The app subnets must cover the same two Availability Zones as the ALB."
    }

    precondition {
      condition     = var.desired_count == 0 || length(var.db_secret_value_from) == 3
      error_message = "Configure all three database secret selectors before allowing the service to start tasks."
    }
  }
}
