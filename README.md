# 실시간 대기열 기반 예약 시스템 (reservation-system-BE)

접속이 한순간에 몰리는 예약 오픈 시점에 사용자를 가상 대기열에 세우고, 백엔드가 감당할 수 있는 속도로만 순서대로 입장시키는 예약 시스템의 백엔드 레포지토리입니다.

> 트래픽 폭주 상황에서도 서버가 죽지 않고, 먼저 온 사람이 먼저 예약하는 공정한 예약 서비스

## 서비스 흐름

이벤트 페이지 진입 → 대기실(내 앞 남은 인원, 전체 대기 인원 표시) → 차례가 되면 입장 → 좌석·시간대 선택(일정 시간 임시 점유) → 예약 확정

차례가 오기 전까지는 대기실에서 현황만 갱신되고, 입장 토큰을 받은 사용자만 좌석·시간대 선택과 예약 확정 단계로 넘어갑니다.

## 주요 기능

- 대기열 등록 및 대기 순서 관리
- 실시간 대기 현황 조회 (내 앞에 남은 인원, 전체 대기 인원)
- 예약 페이지 입장 관리 (입장 토큰)
- 중복 대기 등록, 연속 클릭, 재시도 방지
- 1인 1매 예약 제한

> 기능 범위는 기획 문서의 MoSCoW 기준이며, 개발 진행에 따라 갱신합니다.

## 아키텍처

![AWS 아키텍처 (MVP)](docs/architecture-mvp.png)

1. **접속 및 트래픽 방어**: 사용자가 접속하면 Route 53과 CloudFront를 통해 서비스를 제공하고, AWS WAF가 비정상적인 요청을 필터링합니다. CloudFront는 `/queue/*`, `/booking/*` 요청을 VPC Origin을 통해 Private ALB로 전달합니다.
2. **대기열 등록**: ECS에서 동작하는 Queue / Admission Worker가 대기 요청을 접수하고, ElastiCache(Valkey)에 순번과 대기 상태를 저장합니다.
3. **입장 제어**: Queue / Admission Worker가 예약 서버의 처리 용량에 맞춰 대기 순서대로 사용자의 입장을 허용합니다.
4. **예약 처리**: 입장 토큰을 가진 사용자만 Booking API에 접근하며, RDS PostgreSQL 트랜잭션으로 중복 예약을 방지합니다.
5. **모니터링**: CloudWatch에서 대기열 및 예약 처리 상태와 서버 성능을 관찰합니다.
6. **Phase 2 (MVP 제외)**: 예약 정보와 Outbox 이벤트를 함께 저장하고, SQS와 Lambda를 통해 예약 완료 이메일 알림을 별도로 처리합니다.

MVP에서는 API Gateway와 Queue Lambda를 제외하고, CloudFront에서 Private ALB를 거쳐 ECS로 바로 연결합니다. 서울 리전(ap-northeast-2)의 두 가용 영역(A, C)에 서버를 분산 배치합니다.

## 기술 스택

| 구분 | 내용 |
| --- | --- |
| Language | Java 21 |
| Framework | Spring Boot 4.1.3 |
| Build | Gradle (Groovy) |
| Database | PostgreSQL (AWS RDS) |
| ORM | Spring Data JPA (Hibernate) |
| 대기열 저장소 | Valkey (AWS ElastiCache, Redis 호환) - 도입 예정 |
| 기타 | Lombok, Validation, Spring Boot Actuator |
| 테스트 | JUnit, H2 (인메모리 DB, PostgreSQL 모드) |
| 인프라 | AWS (Route 53, CloudFront, WAF, ALB, ECS, CloudWatch, Parameter Store) |

## 시작하기

### 요구 사항

- JDK 21
- PostgreSQL

### 1. 데이터베이스 준비

PostgreSQL에 접속해 데이터베이스를 만듭니다.

```sql
CREATE DATABASE reservation;
```

### 2. 환경변수 설정

DB 접속 정보는 환경변수로 주입하며, 값은 레포지토리에 커밋하지 않습니다. `.env.example`을 참고해 아래 변수를 설정합니다.

| 변수 | 설명 | 예시 |
| --- | --- | --- |
| `DB_URL` | JDBC 접속 주소 | `jdbc:postgresql://localhost:5432/reservation` |
| `DB_USERNAME` | DB 계정 | |
| `DB_PASSWORD` | DB 비밀번호 | |

Spring Boot는 `.env` 파일을 자동으로 읽지 않습니다. 변수가 설정되지 않으면 기본 프로필(`local`)의 애플리케이션 시작이 실패하므로, 실행 전에 아래 방법 중 하나로 환경변수를 적용합니다.

**IntelliJ에서 실행하는 경우**

실행 설정(Run/Debug Configurations)의 Environment variables에 `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`를 입력합니다. 값에 `;`가 들어 있으면 입력칸 오른쪽 아이콘으로 변수별로 나누어 입력합니다.

**터미널에서 실행하는 경우**

`.env`는 `.env.example`을 복사해서 만들며, `DB_URL`처럼 `&` 등이 들어갈 수 있는 값은 따옴표로 감쌉니다. 셸이 `&`를 명령 구분자로 해석하기 때문입니다.

```bash
# macOS / Linux / Git Bash
cp .env.example .env
set -a
source .env
set +a
./gradlew bootRun
```

```powershell
# Windows PowerShell (.env는 자동 적용되지 않으므로 직접 설정)
$env:DB_URL = "jdbc:postgresql://localhost:5432/reservation"
$env:DB_USERNAME = "사용자명"
$env:DB_PASSWORD = "비밀번호"
./gradlew bootRun
```

### 3. 실행

기본 프로필은 `local`입니다. 운영 환경에서는 `SPRING_PROFILES_ACTIVE=prod`로 실행합니다.

```bash
./gradlew bootRun
```

### 4. 빌드 및 테스트

테스트는 `test` 프로필(`src/test/resources/application-test.yml`)로 실행되며, 외부 DB 없이 H2 인메모리 DB를 사용합니다. 테스트 클래스에는 `@ActiveProfiles("test")`를 붙입니다.

```bash
./gradlew build
```

## 프로젝트 구조

기능별 패키지(`domain`)와 공통 코드(`global`)로 구성합니다.

```
src/
  main/
    java/com/asbg/backend/
      domain/
        <기능>/
          controller/   # HTTP 요청과 응답
          service/      # 유스케이스와 트랜잭션
          domain/       # 엔티티와 도메인 규칙
          repository/   # 데이터 접근
          dto/          # request, response
      global/
        config/         # 공통 설정
        exception/      # 공통 예외 처리
    resources/
      application.yml
      application-local.yml
      application-prod.yml
  test/
    java/com/asbg/backend/
    resources/
      application-test.yml
```

## 협업 규칙

### 브랜치 전략

| 브랜치 | 설명 |
| --- | --- |
| `main` | 운영 배포용 브랜치 |
| `develop` | 개발 통합 브랜치 (기본 브랜치) |
| `{타입}/{작업-관련-브랜치명}` | 작업별 개발 브랜치. 예: `feat/user-register` |

작업 브랜치에서 개발하고 push한 뒤, PR을 통해 `develop`으로 병합하고 브랜치를 삭제합니다.
브랜치 타입: `feat`, `fix`, `chore`, `refactor`, `build`, `deploy`, `test`

### 커밋 컨벤션

```
{타입}: {커밋 메시지}
```

예시: `feat: 회원가입 및 로그인 구현`
커밋 타입: `feat`, `fix`, `chore`, `refactor`, `merge`, `build`, `deploy`, `test`, `docs`, `hotfix`

### 이슈와 Pull Request

- 이슈 제목은 `[타입] 작업 내용` 형식으로 작성합니다. 예: `[chore] 스프링부트 프로젝트 초기 설정`
- PR 제목은 커밋 컨벤션과 같은 `타입: 작업 내용` 형식으로 작성합니다. 예: `chore: 스프링부트 프로젝트 초기 설정`
- PR 본문에 `Closes #이슈번호`를 적어 이슈와 연결합니다.
- PR은 승인(approve) 1명 이상이 있어야 병합할 수 있습니다.
- 병합 커밋 메시지는 PR 제목이 기본값으로 들어가므로 별도로 고치지 않아도 됩니다.
- PR을 올리면 CodeRabbit이 자동으로 리뷰합니다.

## 팀원

| 이름 | GitHub |
| --- | --- |
| 김서윤 | [SY0518](https://github.com/SY0518) |
| 김민주 | [mminjukim](https://github.com/mminjukim) |
| 이서현 | [seoL-ee](https://github.com/seoL-ee) |
| 이주연 | [leejuyeon7852](https://github.com/leejuyeon7852) |
| 임가영 | [lojhbm](https://github.com/lojhbm) |
| 정승민 | [snmng06](https://github.com/snmng06) |