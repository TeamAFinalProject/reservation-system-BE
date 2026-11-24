data "aws_vpc" "existing" {
  id = var.vpc_id
}

data "aws_subnet" "alb" {
  for_each = var.alb_subnet_names
  vpc_id   = data.aws_vpc.existing.id

  tags = {
    Name = each.key
  }
}

data "aws_subnet" "app" {
  for_each = var.app_subnet_names
  vpc_id   = data.aws_vpc.existing.id

  tags = {
    Name = each.key
  }
}

data "aws_security_group" "existing" {
  for_each = {
    alb  = var.alb_security_group_id
    task = var.task_security_group_id
  }

  id     = each.value
  vpc_id = data.aws_vpc.existing.id
}

data "aws_iam_role" "execution" {
  name = var.execution_role_name
}

data "aws_ecr_repository" "booking" {
  name = var.booking_repository_name
}

data "aws_ecr_image" "booking" {
  repository_name = data.aws_ecr_repository.booking.name
  image_digest    = var.booking_image_digest
}
