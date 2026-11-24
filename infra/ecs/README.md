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
- **SG** · 기존 ALB용, Task용, Endpoint용 SG ID를 환경 변수 파일에 입력합니다. 기존 SG에 필요한 규칙이 없다면 네트워크 소유자에게 별도 변경을 요청합니다.
- **Route Table** · App A/C의 유효 테이블 ID를 확인합니다. 명시적 연결이 없으면 VPC의 main 테이블이 적용됩니다. Terraform도 같은 방식으로 조회하고 중복 ID를 제거합니다. S3 Gateway Endpoint는 해당 테이블에 S3 경로를 추가하므로 공유 테이블과 사용 중인 S3 워크로드를 먼저 확인합니다. 이 스택의 Endpoint 정책은 ECR 이미지 레이어 버킷 읽기만 허용합니다.
- **Terraform State** · Git 이력에서는 기존 Terraform 구성을 확인하지 못했습니다. 적용 전 인프라 소유자에게 기존 S3 backend/state key, HCP Terraform workspace 또는 다른 IaC 도구의 관리 범위를 확인합니다. 기존 리소스는 이 스택으로 import하지 않습니다. Endpoint와 신규 리소스 이름이 기존 State나 환경과 겹치면 관리 주체를 먼저 정합니다. 별도 State를 사용하는 경우 팀이 승인한 암호화·버전 관리·잠금이 있는 backend와 환경별 키를 구성합니다. 이 구성은 backend를 고정하지 않습니다.

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
4. 이미지, DB, SG, 네트워크 준비 후에만 `desired_count`를 1 이상으로 설정합니다. ALB는 IP 타깃과 `/actuator/health`의 HTTP 200을 사용합니다. 롤링 배포는 최소 정상 100%, 최대 200%, 시작 유예 120초와 실패 롤백을 사용합니다. 롤백에는 이전에 완료된 배포가 필요합니다. AZ 장애 대응이 필요하면 Task 2개와 여유 IP·용량을 확보합니다.
5. CloudFront를 연결할 때 활성 상태인 Internal ALB의 `internal_alb_arn`을 VPC Origin에 사용합니다. 지원 리전, VPC에 붙은 IGW, 사설 서브넷의 여유 IPv4, 리스너 프로토콜을 확인합니다. 현재 리스너는 HTTP 80이며, HTTPS가 필요하면 인증서와 리스너를 별도로 준비합니다. CloudFront 설정 시 ALB SG에 관리형 Prefix List를 허용하고, 서비스 관리형 SG가 생성되면 해당 SG로 제한하는 절차를 검토합니다.
6. 승인된 별도 적용 작업에서는 계획의 삭제·교체와 대상 계정을 리뷰합니다. ALB 삭제 방지를 기본 활성화했습니다. 롤링 배포 중에 필요할 수 있는 Task Definition 교체와 로그·Endpoint 삭제는 별도로 검토합니다. NAT, CloudFront, RDS, Valkey는 별도 구성 대상입니다.

[AWS ECR VPC Endpoint](https://docs.aws.amazon.com/AmazonECR/latest/userguide/vpc-endpoints.html) · [CloudFront VPC Origin](https://docs.aws.amazon.com/AmazonCloudFront/latest/DeveloperGuide/private-content-vpc-origins.html) · [VPC 요금](https://aws.amazon.com/vpc/pricing/) · [PrivateLink 요금](https://aws.amazon.com/privatelink/pricing/)
