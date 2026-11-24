# Booking ECS 자동 배포

`develop` Push의 CI가 성공하면 ECR Push 워크플로가 그 CI에서 검증한 커밋을 체크아웃합니다. 수동 실행도 `develop`에서만 허용하며 같은 Gradle 검사를 먼저 실행합니다. 이미지 태그는 커밋 SHA, 실행 ID, 시도 번호로 구분하고 ECS에는 ECR이 반환한 digest를 전달합니다. `workflow_run`을 수신하려면 워크플로가 저장소 기본 브랜치에 있어야 합니다.

## 활성화

1. Terraform PR #10과 별도로 리뷰하는 ECS 리비전 소유권 인계 변경을 반영합니다. 승인된 S3 State를 사용해 ECS가 준비되면 `teama-booking-mvp` 서비스의 가동 조건과 기본 Task Definition을 검토합니다.
2. GitHub 환경 `ecs-mvp`를 생성하고 배포 브랜치를 `develop`으로 제한합니다. 필요한 경우 환경 승인자를 설정합니다. 이 환경에서만 사용할 CD용 OIDC 역할을 승인받습니다.
3. 기존 `AWS_REGION=ap-northeast-2`, `AWS_ROLE_ARN`은 ECR 작업에서 사용합니다. 별도 CD 역할의 ARN을 저장소 또는 `ecs-mvp` 환경의 `ECS_DEPLOY_ROLE_ARN` 변수에 설정합니다.
4. 서비스가 `ACTIVE` 상태이고 Terraform이 `DeploymentOwner=github-actions` 태그를 설정했으며 `desiredCount > 0`임을 확인한 뒤 저장소 변수 `ECS_DEPLOY_ENABLED=true`로 활성화합니다. 변수가 없거나 값이 다르면 ECR Push까지 진행합니다. 활성화 후 대상 서비스나 조건이 누락되면 CD가 리비전 등록 전에 실패합니다.
5. 다음 `develop` CI 성공 또는 `ECR Push` 수동 실행으로 검증합니다. CD는 서비스가 가리키는 Task Definition에서 `booking` 이미지 digest만 바꾸고, 새 리비전이 PRIMARY 및 COMPLETED 상태이고 실행 태스크 수가 desired count와 일치할 때 성공합니다. ECS 롤백으로 이전 리비전이 안정화되면 해당 실행은 실패로 표시됩니다.

GitHub Actions는 배포를 직렬화하고 `develop` 최신 커밋을 확인합니다. Terraform 서비스 업데이트나 수동 배포 전에는 활성화 변수를 끄고 진행 중인 배포가 끝났는지 확인합니다. Terraform은 네트워크, 서비스 및 초기 Task Definition을 관리하고, 서비스의 활성 `task_definition`은 `ignore_changes` 대상으로 둡니다. Terraform의 기준 리비전은 `skip_destroy`로 보존합니다. CPU, 역할, 시크릿 등을 변경할 때는 CD를 중지한 상태에서 새 기준 리비전을 서비스에 반영한 뒤 CD를 재개합니다. 런타임 헬스와 업무 API 검증은 실제 서비스 환경에서 별도로 수행합니다.

## AWS OIDC 역할

기존 ECR 역할은 이미 승인된 저장소·`develop` 신뢰를 사용합니다. ECR 로그인에는 `ecr:GetAuthorizationToken`의 `Resource: "*"`, Booking 저장소에는 업로드 작업과 `ecr:BatchGetImage`를 사용합니다. 기존 역할의 Booking 저장소 정책에 두 권한이 포함되어 있습니다.

별도 ECS 역할의 신뢰 조건은 아래 값으로 제한합니다. Principal은 계정 `109729968560`의 GitHub OIDC provider `token.actions.githubusercontent.com`이며 허용 작업은 `sts:AssumeRoleWithWebIdentity`입니다.

| 조건 키 | StringEquals 값 |
| --- | --- |
| `token.actions.githubusercontent.com:aud` | `sts.amazonaws.com` |
| `token.actions.githubusercontent.com:sub` | `repo:TeamAFinalProject/reservation-system-BE:environment:ecs-mvp` |

ECS 역할 권한 예시는 [ecs-cd-policy.example.json](ecs-cd-policy.example.json)에 있습니다. 새 태스크 역할을 도입할 때만 해당 역할의 정확한 ARN을 `iam:PassRole`에 추가합니다. Terraform State, Secrets Manager 원문, 네트워크 변경 권한은 ECS 배포 역할의 범위에서 제외합니다. [AWS ECS 권한 참조](https://docs.aws.amazon.com/service-authorization/latest/reference/list_ecs.html)
