#!/usr/bin/env bash
set -euo pipefail

export AWS_PAGER=""
cluster="arn:aws:ecs:$AWS_REGION:$AWS_ACCOUNT_ID:cluster/$ECS_STACK"
service="arn:aws:ecs:$AWS_REGION:$AWS_ACCOUNT_ID:service/$ECS_STACK/$ECS_STACK"
family="arn:aws:ecs:$AWS_REGION:$AWS_ACCOUNT_ID:task-definition/$ECS_STACK"
repository="$AWS_ACCOUNT_ID.dkr.ecr.$AWS_REGION.amazonaws.com/teama/booking"

fail() {
  echo "::error::$1"
  exit 1
}

is_current_source() {
  local head
  head="$(gh api "repos/$GH_REPO/git/ref/heads/develop" --jq .object.sha)" ||
    fail "Could not verify the current develop commit."
  [[ "$SOURCE_SHA" == "$head" ]]
}

describe_service() {
  aws ecs describe-services --cluster "$cluster" --services "$service" --include TAGS
}

[[ "$IMAGE" == "$repository@"* && "${IMAGE#*@}" =~ ^sha256:[0-9a-f]{64}$ ]] ||
  fail "Expected an immutable Booking image reference."

if ! is_current_source; then
  echo "Skipping superseded develop commit $SOURCE_SHA."
  exit 0
fi

initial="$(describe_service)"
baseline="$(jq -er --arg cluster "$cluster" --arg service "$service" '
  select((.failures | length) == 0 and (.services | length) == 1)
  | .services[0]
  | select(.clusterArn == $cluster and .serviceArn == $service and .status == "ACTIVE")
  | select(.deploymentController.type == "ECS" and .desiredCount > 0)
  | select(any(.tags[]?; .key == "DeploymentOwner" and .value == "github-actions"))
  | .taskDefinition
' <<< "$initial")" ||
  fail "Expected an active ECS rolling service with desired count above zero and the Terraform ownership tag."
[[ "$baseline" == "$family:"* ]] || fail "The service uses an unexpected task family."

request="$(aws ecs describe-task-definition --task-definition "$baseline" |
  jq -ce --arg image "$IMAGE" '
    .taskDefinition
    | select(.status == "ACTIVE" and .runtimePlatform.cpuArchitecture == "X86_64"
        and .runtimePlatform.operatingSystemFamily == "LINUX"
        and (.requiresCompatibilities | index("FARGATE")))
    | select(([.containerDefinitions[] | select(.name == "booking")] | length) == 1)
    | del(.taskDefinitionArn, .revision, .status, .requiresAttributes, .compatibilities,
          .registeredAt, .registeredBy, .deregisteredAt)
    | (.containerDefinitions[] | select(.name == "booking").image) = $image
  ')" || fail "The active task definition is incompatible with the Booking image."

if ! is_current_source; then
  echo "Skipping superseded develop commit $SOURCE_SHA."
  exit 0
fi

revision="$(aws ecs register-task-definition --cli-input-json "$request" \
  --query 'taskDefinition.taskDefinitionArn' --output text)"
[[ "$revision" == "$family:"* ]] || fail "ECS returned an unexpected task definition ARN."

if ! is_current_source; then
  echo "Leaving unused revision $revision registered because develop advanced."
  exit 0
fi

describe_service | jq -e --arg baseline "$baseline" '
  select((.failures | length) == 0) | .services[0]
  | .status == "ACTIVE" and .desiredCount > 0 and .taskDefinition == $baseline
    and any(.tags[]?; .key == "DeploymentOwner" and .value == "github-actions")
' >/dev/null || fail "The service changed during preparation. Revision $revision was not deployed."

aws ecs update-service --cluster "$cluster" --service "$service" --task-definition "$revision" >/dev/null
if ! aws ecs wait services-stable --cluster "$cluster" --services "$service"; then
  fail "ECS did not stabilize. Check the deployment and service events for $revision."
fi

describe_service | jq -e --arg revision "$revision" '
  select((.failures | length) == 0) | .services[0]
  | .status == "ACTIVE" and .taskDefinition == $revision and .desiredCount > 0
    and .runningCount == .desiredCount and .pendingCount == 0
    and any(.deployments[]; .status == "PRIMARY" and .taskDefinition == $revision
      and .rolloutState == "COMPLETED")
' >/dev/null || fail "The requested revision did not become the completed primary deployment."

echo "ECS completed deployment of $revision ($IMAGE)."
