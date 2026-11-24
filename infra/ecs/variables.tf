variable "environment" {
  type        = string
  description = "Environment suffix for the resources owned by this stack."

  validation {
    condition     = can(regex("^[a-z0-9]([a-z0-9-]{0,10}[a-z0-9])?$", var.environment))
    error_message = "Use 1–12 lowercase letters, digits, or hyphens, starting and ending with a letter or digit."
  }
}

variable "aws_region" {
  type = string
}

variable "aws_account_id" {
  type = string
}

variable "vpc_id" {
  type = string
}

variable "alb_subnet_names" {
  type = set(string)

  validation {
    condition     = length(var.alb_subnet_names) == 2
    error_message = "Provide two private ALB subnet Name tags."
  }
}

variable "app_subnet_names" {
  type = set(string)

  validation {
    condition     = length(var.app_subnet_names) == 2 && length(setintersection(var.app_subnet_names, var.alb_subnet_names)) == 0
    error_message = "Provide two private app subnet Name tags distinct from the ALB subnets."
  }
}

variable "alb_security_group_id" {
  type = string
}

variable "task_security_group_id" {
  type = string

  validation {
    condition     = var.task_security_group_id != var.alb_security_group_id
    error_message = "Use different existing security groups for the ALB and ECS tasks."
  }
}

variable "manage_vpc_endpoints" {
  type        = bool
  description = "Create the AWS service endpoints and their dedicated security group. Disable only when task egress is provided separately."
  default     = true
}

variable "execution_role_name" {
  type = string
}

variable "booking_repository_name" {
  type = string
}

variable "booking_image_digest" {
  type = string

  validation {
    condition     = can(regex("^sha256:[0-9a-f]{64}$", var.booking_image_digest))
    error_message = "Provide an existing Linux AMD64 ECR image's sha256 digest."
  }
}

variable "desired_count" {
  type        = number
  default     = 0
  description = "Keep tasks stopped until the selected profile's image and dependencies are ready."

  validation {
    condition     = var.desired_count >= 0 && floor(var.desired_count) == var.desired_count
    error_message = "Desired count must be a nonnegative integer."
  }
}

variable "spring_profile" {
  type        = string
  default     = "prod"
  description = "Spring Boot profile: prod for Booking, smoke for DB-free infrastructure checks."

  validation {
    condition     = contains(["prod", "smoke"], var.spring_profile)
    error_message = "Select prod or smoke."
  }
}

variable "db_secret_value_from" {
  type        = map(string)
  default     = {}
  description = "DB_URL, DB_USERNAME, and DB_PASSWORD as same-account Secrets Manager ARN selectors. Do not supply secret values."

  validation {
    condition     = var.spring_profile != "smoke" || length(var.db_secret_value_from) == 0
    error_message = "The smoke profile must not receive database secret selectors."
  }

  validation {
    condition = (length(var.db_secret_value_from) == 0 || toset(keys(var.db_secret_value_from)) == toset(["DB_URL", "DB_USERNAME", "DB_PASSWORD"])) && alltrue([
      for arn in values(var.db_secret_value_from) :
      startswith(arn, "arn:aws:secretsmanager:${var.aws_region}:${var.aws_account_id}:secret:")
    ])
    error_message = "Supply all three database environment names with same-region, same-account Secrets Manager ARN selectors, or leave the map empty."
  }
}

variable "enable_github_actions_deploy" {
  type        = bool
  default     = false
  description = "Advertise that the Booking ECS service is ready for GitHub Actions to deploy task revisions."
}
