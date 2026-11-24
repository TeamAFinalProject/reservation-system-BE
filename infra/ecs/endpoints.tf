locals {
  interface_services = toset(concat(
    ["ecr.api", "ecr.dkr", "logs"],
    length(var.db_secret_value_from) > 0 ? ["secretsmanager"] : []
  ))
}

data "aws_route_tables" "app_explicit" {
  for_each = var.manage_vpc_endpoints ? data.aws_subnet.app : {}
  vpc_id   = data.aws_vpc.existing.id

  filter {
    name   = "association.subnet-id"
    values = [each.value.id]
  }
}

data "aws_route_tables" "main" {
  count  = var.manage_vpc_endpoints ? 1 : 0
  vpc_id = data.aws_vpc.existing.id

  filter {
    name   = "association.main"
    values = ["true"]
  }
}

resource "aws_vpc_endpoint" "s3" {
  count             = var.manage_vpc_endpoints ? 1 : 0
  vpc_id            = data.aws_vpc.existing.id
  service_name      = "com.amazonaws.${var.aws_region}.s3"
  vpc_endpoint_type = "Gateway"

  route_table_ids = toset(flatten([
    for table in values(data.aws_route_tables.app_explicit) :
    length(table.ids) > 0 ? table.ids : data.aws_route_tables.main[0].ids
  ]))

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = "*"
      Action    = "s3:GetObject"
      Resource  = "arn:aws:s3:::prod-${var.aws_region}-starport-layer-bucket/*"
    }]
  })
}

resource "aws_security_group" "endpoint" {
  count       = var.manage_vpc_endpoints ? 1 : 0
  name        = "${local.name}-endpoint"
  description = "HTTPS from Booking ECS tasks to interface endpoints"
  vpc_id      = data.aws_vpc.existing.id

  ingress {
    description     = "HTTPS from Booking ECS tasks"
    protocol        = "tcp"
    from_port       = 443
    to_port         = 443
    security_groups = [data.aws_security_group.existing["task"].id]
  }
}

resource "aws_vpc_endpoint" "interface" {
  for_each            = var.manage_vpc_endpoints ? local.interface_services : toset([])
  vpc_id              = data.aws_vpc.existing.id
  service_name        = "com.amazonaws.${var.aws_region}.${each.key}"
  vpc_endpoint_type   = "Interface"
  private_dns_enabled = true
  subnet_ids          = values(data.aws_subnet.app)[*].id
  security_group_ids  = [aws_security_group.endpoint[0].id]

  lifecycle {
    precondition {
      condition     = data.aws_vpc.existing.enable_dns_support && data.aws_vpc.existing.enable_dns_hostnames
      error_message = "Interface endpoint private DNS requires VPC DNS support and DNS hostnames."
    }
  }
}
