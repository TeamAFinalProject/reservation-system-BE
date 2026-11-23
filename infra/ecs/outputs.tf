output "internal_alb_arn" {
  description = "ARN for the future CloudFront VPC Origin."
  value       = aws_lb.booking.arn
}

output "internal_alb_dns_name" {
  value = aws_lb.booking.dns_name
}

output "ecs_cluster_name" {
  value = aws_ecs_cluster.booking.name
}

output "ecs_service_name" {
  value = aws_ecs_service.booking.name
}
