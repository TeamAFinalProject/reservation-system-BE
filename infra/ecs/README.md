# ECS Fargate 배포 준비

Terraform으로 기존 VPC, Subnet, Security Group, IAM Role, ECR을 data source로 조회하고, 이 스택이 관리할 리소스만 별도로 정의합니다. Provider 잠금 파일과 환경별 변수 파일을 사용할 수 있고, AWS에 접속하지 않고도 구성과 Provider 스키마를 검증할 수 있어 Terraform을 선택했습니다.

`develop`의 Gradle 모듈과 Spring Boot 진입점은 각각 하나입니다. Booking 서비스는 PR #9에서 구축한 `teama/booking`의 Linux AMD64 이미지 digest를 사용합니다. Queue Worker는 독립 실행 단위가 준비된 뒤 별도 Task Definition과 Service로 추가합니다.

## 정적 검증

```sh
terraform -chdir=infra/ecs fmt -check -recursive
terraform -chdir=infra/ecs fmt -check - < infra/ecs/environments/mvp.tfvars.example
terraform -chdir=infra/ecs init -backend=false -input=false -lockfile=readonly
terraform -chdir=infra/ecs validate
```

초기화는 Provider 다운로드를 위해 Terraform Registry에 접근합니다. 위 단계에는 AWS 자격 증명이 필요하지 않습니다.
GitHub Actions의 Terraform Validate 워크플로도 `infra/ecs` 변경이 있는 PR에서 같은 검사를 실행합니다.

## 기존 인프라 읽기 전용 조사

AWS CLI와 대상 계정의 읽기 권한이 있는 프로필이 필요합니다. 아래 명령은 리소스 ID, Availability Zone, 태그 및 네트워크 연결만 조회합니다.

```sh
export AWS_PROFILE='<read-only-profile>'
export AWS_DEFAULT_REGION=ap-northeast-2
vpc_id=vpc-01fddc91a0da8830d

aws sts get-caller-identity --query Account --output text
aws ec2 describe-vpcs --vpc-ids "$vpc_id" --query 'Vpcs[].{ID:VpcId,CIDR:CidrBlock,Name:Tags[?Key==`Name`].Value|[0]}'
aws ec2 describe-vpc-attribute --vpc-id "$vpc_id" --attribute enableDnsSupport
aws ec2 describe-vpc-attribute --vpc-id "$vpc_id" --attribute enableDnsHostnames

aws ec2 describe-subnets --filters "Name=vpc-id,Values=$vpc_id" \
  --query 'Subnets[].{Name:Tags[?Key==`Name`].Value|[0],ID:SubnetId,AZ:AvailabilityZone,CIDR:CidrBlock,FreeIPv4:AvailableIpAddressCount}'
aws ec2 describe-security-groups --filters "Name=vpc-id,Values=$vpc_id" \
  --query 'SecurityGroups[].{Name:GroupName,ID:GroupId,Ingress:IpPermissions,Egress:IpPermissionsEgress}'
aws ec2 describe-route-tables --filters "Name=vpc-id,Values=$vpc_id" \
  --query 'RouteTables[].{ID:RouteTableId,Associations:Associations[].{Subnet:SubnetId,Main:Main},Routes:Routes}'
aws ec2 describe-vpc-endpoints --filters "Name=vpc-id,Values=$vpc_id" \
  --query 'VpcEndpoints[].{ID:VpcEndpointId,Service:ServiceName,Subnets:SubnetIds,Tables:RouteTableIds,State:State}'
aws ec2 describe-internet-gateways --filters "Name=attachment.vpc-id,Values=$vpc_id" \
  --query 'InternetGateways[].InternetGatewayId'

aws iam get-role --role-name teama-ecsTaskExecutionRole --query 'Role.{ARN:Arn,Trust:AssumeRolePolicyDocument}'
aws iam list-attached-role-policies --role-name teama-ecsTaskExecutionRole
aws iam list-role-policies --role-name teama-ecsTaskExecutionRole
aws ecr describe-repositories --repository-names teama/booking teama/queue \
  --query 'repositories[].{Name:repositoryName,URI:repositoryUri}'
aws ecr describe-images --repository-name teama/booking \
  --query 'reverse(sort_by(imageDetails,&imagePushedAt))[:5].{Digest:imageDigest,Tags:imageTags}'
```

- **Subnet** · ALB A/C와 App A/C의 ID를 확인합니다. Terraform은 VPC ID와 정확한 `Name` 태그로 조회하며, ALB와 App이 같은 두 AZ에 있는지 검사합니다. ALB 서브넷은 각각 /27 이상 크기와 사용 가능한 IPv4 8개 이상을 확인합니다. App 서브넷에는 Task와 Interface Endpoint ENI에 필요한 여유 주소를 확인합니다.
- **SG** · 기존 ALB용과 Task용 SG ID를 환경 변수 파일에 입력합니다. Endpoint를 관리할 때 이 스택이 전용 SG를 생성하고 Booking Task SG에서 오는 TCP 443만 허용합니다. 기존 SG에 필요한 규칙이 없다면 네트워크 소유자에게 별도 변경을 요청합니다.
- **Route Table** · App A/C의 유효 테이블 ID를 확인합니다. 명시적 연결이 없으면 VPC의 main 테이블이 적용됩니다. Terraform도 같은 방식으로 조회하고 중복 ID를 제거합니다. S3 Gateway Endpoint는 해당 테이블에 S3 경로를 추가하므로 공유 테이블과 사용 중인 S3 워크로드를 먼저 확인합니다. 이 스택의 Endpoint 정책은 ECR 이미지 레이어 버킷 읽기만 허용합니다.

## 검증 Plan과 운영 State

기존 VPC, Subnet, SG, Route Table, ECR 및 실행 역할은 data source로 조회한 상태로 사용합니다. ECS, Internal ALB, 로그 그룹, Endpoint와 전용 SG는 이 스택에서 관리합니다. S3 Gateway Endpoint 연결로 기존 앱 Route Table에 S3 경로가 추가됩니다.

| 구분 | State와 검증 범위 |
| --- | --- |
| 최초 검증 Plan | 별도 임시 디렉터리에 Terraform 소스와 Provider 잠금 파일을 복사하고 `init -backend=false`를 실행합니다. 빈 로컬 State를 기준으로 AWS 조회 및 생성 계획을 검증합니다. |
| 운영 Plan | 승인된 S3 State를 읽고, 해당 환경의 변경·삭제·교체까지 검토합니다. Plan 중 `.tflock` 객체를 생성하고 해제하므로 운영 backend 접근 승인이 필요합니다. |

### 운영 S3 backend 설계

- 승인된 팀 소유 S3 버킷에 `reservation-system/ecs/<환경>/terraform.tfstate` 키를 둡니다. 환경마다 키와 접근 권한을 분리하고 각각 `default` workspace를 사용합니다. Terraform 1.10 이상에서 `use_lockfile = true`로 환경별 동시 실행을 직렬화합니다.
- 버킷에는 Versioning, 기본 SSE-S3 암호화, 퍼블릭 액세스 차단 설정 4개를 활성화합니다. 버킷 정책은 버킷 ARN과 객체 ARN에 대해 `aws:SecureTransport = false`인 `s3:*` 요청을 명시적으로 거부합니다. State 복구용 이전 버전은 팀 보존 정책에 따라 관리합니다.
- Backend 역할은 ECS 실행 역할과 별도로 관리합니다. Plan 역할은 아래에서 State 객체 읽기와 잠금 권한만 사용합니다. 승인된 배포 역할에만 State 객체 쓰기를 추가합니다.

| 권한 | 범위 |
| --- | --- |
| `s3:ListBucket` | 승인 버킷 ARN. `s3:prefix` 조건을 `reservation-system/ecs/mvp/terraform.tfstate`와 Terraform의 workspace 조회 접두사인 `reservation-system/ecs/mvp/workspaces/`로 제한 |
| `s3:GetObject` | `reservation-system/ecs/mvp/terraform.tfstate` 객체 ARN |
| `s3:PutObject` | 동일 State 객체 ARN. 승인된 배포 역할에만 부여 |
| `s3:GetObject`, `s3:PutObject`, `s3:DeleteObject` | 동일 키에 `.tflock`을 붙인 잠금 객체 ARN |

### 승인 후 최초 초기화와 운영 Plan

1. State 소유자와 버킷, 환경별 키, AWS 계정, 역할 및 신규 초기화 여부를 확정합니다. 버킷 관리자가 버킷 소유 계정과 보안 설정을 확인하고, 대상 키의 현재 객체 및 버전 이력과 잠금 유무를 조회합니다. 기존 State나 잠금을 발견하거나 조회 권한이 부족하면 소유권을 확인할 때까지 중단합니다.
2. 로컬 State 및 `.terraform` 메타데이터가 없는 새 체크아웃에서 `infra/ecs`로 이동합니다. `environments/backend.s3.tf.example`을 Git에서 무시되는 `backend.s3.tf`로 복사하고 승인된 버킷을 지정합니다. MVP 외 환경은 키와 workspace 조회 접두사, IAM 범위를 함께 바꿉니다. 인증은 승인된 AWS 프로필을 사용합니다.
3. `environments/mvp.tfvars.example`에서 환경 입력 파일을 준비합니다. 계정·리전, VPC ID, ALB·앱 서브넷 이름, 기존 ALB·Booking SG ID, 실행 역할명, ECR 저장소 및 Linux AMD64 digest를 검증합니다. DB 준비 전에는 `desired_count = 0`을 유지합니다.
4. 승인된 프로필의 STS 계정과 검토된 backend 입력을 확인한 뒤 아래 명령을 실행합니다. `mvp` 예시이며 명령은 `infra/ecs`에서 실행합니다.

```sh
export AWS_PROFILE=teama
export TF_WORKSPACE=default
aws sts get-caller-identity --region ap-northeast-2 --query Account --output text
terraform init -input=false -lockfile=readonly
terraform workspace show
terraform plan -input=false -lock-timeout=60s -var-file=environments/mvp.tfvars
```

계정은 `109729968560`, workspace는 `default`여야 합니다. Plan의 대상 계정, 생성·변경·삭제·교체 및 S3 경로 영향을 리뷰합니다. 기존 State가 발견되면 원본·대상 소유권, 리소스 목록, lineage와 serial, 암호화된 백업 및 복구 절차를 확인한 뒤 State 이전을 별도로 승인받습니다. 잘못된 키나 빈 State를 사용하면 기존 리소스의 중복 생성이 계획될 수 있습니다. 최초 신규 초기화에 `-migrate-state`나 `-force-copy`를 사용하지 않으며, 잠금 경합 시 실행자를 확인합니다.

[Terraform S3 backend 권한 및 잠금](https://developer.hashicorp.com/terraform/language/backend/s3)

## 네트워크 방식

| 방식 | 필요한 구성 | 과금 및 운영 |
| --- | --- | --- |
| NAT Gateway | Public NAT Subnet C, IGW 기본 경로, NAT Gateway와 EIP, App Route Table의 기본 경로 | Gateway 시간, 처리 GB, 공인 IPv4, 필요 시 AZ 간 전송 비용. C에만 두면 A 워크로드가 C 경로에 의존합니다. 외부 API 등 일반 인터넷 목적지에 접근할 수 있습니다. |
| VPC Endpoint | A/C App Subnet에 ECR API·DKR·Logs Interface Endpoint, App Route Table에 S3 Gateway Endpoint, 사설 DNS 및 TCP 443 SG 규칙. DB secret 사용 시 Secrets Manager Interface Endpoint 추가. | 기본 Interface Endpoint 3개 × 2 AZ, DB secret 사용 시 4개 × 2 AZ에 시간 및 처리 GB 요금이 적용됩니다. S3 Gateway Endpoint 자체의 시간·처리 요금은 없습니다. 허용한 AWS 서비스 경로를 사설망에서 운영합니다. |

MVP에는 **VPC Endpoint**를 기본값으로 선택했습니다. 현재 요구하는 AWS 서비스 경로를 제공하면서 App Subnet에 인터넷 기본 경로를 추가할 필요가 없습니다. AZ당 Interface Endpoint 수에 따라 NAT보다 고정 비용이 높을 수 있으므로, 실제 트래픽으로 서울 리전 견적을 비교합니다. App Subnet의 egress가 별도로 준비되어 있다면 `manage_vpc_endpoints = false`로 이 스택의 Endpoint 생성을 끌 수 있습니다. 적용 이후 이 값을 끄면 스택이 소유한 Endpoint 삭제가 계획되므로 먼저 대체 경로를 검증합니다.

별도 비용 항목은 Internal ALB 시간·LCU, 실행 중인 Fargate Task vCPU·메모리, CloudWatch Logs 수집·보관, ECR 이미지 저장, Secrets Manager입니다. `desired_count = 0`이어도 ALB와 Interface Endpoint가 배포되어 있으면 시간 비용이 발생합니다.

## 실제 적용 전 확인사항

1. `environments/mvp.tfvars.example`을 로컬 `.tfvars`로 복사해 확인된 SG ID와 ECR digest를 채웁니다. 기본 Task 크기는 테스트용 0.25 vCPU·512 MiB이며, JVM과 연결 풀의 메모리 사용량을 보고 조정합니다. ECR lifecycle policy가 현재 및 롤백용 digest를 보존하는지도 확인합니다.
2. 기존 ALB SG는 승인된 소스에서 TCP 80 인바운드, Task SG로 TCP 8080 아웃바운드를 허용해야 합니다. Task SG는 ALB SG에서 8080 인바운드, Endpoint SG 및 S3 관리형 Prefix List로 TCP 443 아웃바운드를 허용해야 합니다. Endpoint SG는 Task SG에서 TCP 443 인바운드를 허용해야 합니다. DB 연결에는 Task SG에서 DB SG로의 아웃바운드와 DB SG에서 Task SG로부터의 인바운드가 필요합니다. NACL 및 VPC 사설 DNS도 확인합니다.
3. PostgreSQL 및 스키마를 준비하고 `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`에 대응하는 Secrets Manager ARN 또는 JSON 키 선택자를 `db_secret_value_from`에 지정합니다. 실행 역할의 ECR pull, CloudWatch Logs 기록, `secretsmanager:GetSecretValue` 및 고객 관리형 KMS 키 사용 시 `kms:Decrypt` 권한을 확인합니다. 역할 신뢰 정책에는 ECS Task 서비스가 필요합니다. Secret 원문은 Terraform 입력 및 State에 넣지 않습니다.
   DB를 준비하기 전에는 `desired_count = 0`과 빈 `db_secret_value_from`으로 계획할 수 있습니다. 이 경우 Task Definition에 DB 시크릿을 주입하지 않고 Secrets Manager Endpoint도 계획에 포함되지 않습니다. Spring Boot의 `prod` 프로필은 DB 설정과 준비된 스키마를 사용합니다.
4. 이미지, DB, SG, 네트워크 준비 후에만 `desired_count`를 1 이상으로 설정합니다. ALB는 IP 타깃과 `/actuator/health`의 HTTP 200을 사용합니다. 롤링 배포는 최소 정상 100%, 최대 200%, 시작 유예 120초와 실패 롤백을 사용합니다. 롤백에는 이전에 완료된 배포가 필요합니다. AZ 장애 대응이 필요하면 Task 2개와 여유 IP·용량을 확보합니다.
5. CloudFront를 연결할 때 활성 상태인 Internal ALB의 `internal_alb_arn`을 VPC Origin에 사용합니다. 지원 리전, VPC에 붙은 IGW, 사설 서브넷의 여유 IPv4, 리스너 프로토콜을 확인합니다. 현재 리스너는 HTTP 80이며, HTTPS가 필요하면 인증서와 리스너를 별도로 준비합니다. CloudFront 설정 시 ALB SG에 관리형 Prefix List를 허용하고, 서비스 관리형 SG가 생성되면 해당 SG로 제한하는 절차를 검토합니다.
6. 승인된 별도 적용 작업에서는 계획의 삭제·교체와 대상 계정을 리뷰합니다. ALB 삭제 방지를 기본 활성화했습니다. 롤링 배포 중에 필요할 수 있는 Task Definition 교체와 로그·Endpoint 삭제는 별도로 검토합니다. NAT, CloudFront, RDS, Valkey는 별도 구성 대상입니다.

[AWS ECR VPC Endpoint](https://docs.aws.amazon.com/AmazonECR/latest/userguide/vpc-endpoints.html) · [CloudFront VPC Origin](https://docs.aws.amazon.com/AmazonCloudFront/latest/DeveloperGuide/private-content-vpc-origins.html) · [VPC 요금](https://aws.amazon.com/vpc/pricing/) · [PrivateLink 요금](https://aws.amazon.com/privatelink/pricing/)
