# Playback Gate - 설계 문서

## 1. 문서 목적

이 문서는 Playback Gate의 **현재 시스템 구조와 구현 기준**을 정의한다.

기능 요구사항은 `REQUIREMENTS.md`를 기준으로 하고, 이 문서에서는 해당 요구사항을 어떤 구조로 구현할지를 다룬다.

주요 내용은 다음과 같다.

* 전체 시스템 구조
* 기술 스택
* 프로젝트 및 패키지 구조
* 도메인 모델
* 데이터베이스 구조
* API 구조
* 주요 비즈니스 흐름
* 트랜잭션과 데이터 정합성 기준
* 테스트 및 관측 구조
* 성능 개선에 따른 설계 변경 원칙

이 문서는 버전별로 새로 만드는 문서가 아니다.

프로젝트가 진행되면서 Redis, Lock, 모니터링 등의 구조가 실제로 추가되면 **현재 코드와 일치하도록 계속 갱신한다.**

중요한 설계 변경의 이유는 ADR에 별도로 남기고, 성능 개선 효과는 experiments 문서에 기록한다.

```text
REQUIREMENTS.md
    │
    ▼
 DESIGN.md ───────────── 현재 시스템 구조
    │
    ├── adr/ ─────────── 왜 설계를 변경했는가
    │
    └── experiments/ ── 실제로 개선됐는가
```

---

# 2. 개발 원칙

## 2.1 필요한 것만 구현한다

Playback Gate의 핵심 기능과 직접 관계없는 기능은 구현하지 않는다.

다음 기능은 제외한다.

* 회원가입
* 로그인 UI
* 관리자 페이지
* 실제 결제
* 실제 영상 스트리밍
* DRM
* CDN
* 추천
* 검색
* 댓글
* 좋아요
* 시청 기록 분석

---

## 2.2 초기 구현은 의도적으로 단순하게 시작한다

초기 Baseline 구현에서는 다음 기술을 사용하지 않는다.

* Redis
* Caffeine
* Kafka
* Distributed Lock
* Redis Lock
* 비동기 이벤트 처리
* 별도 Microservice
* Kubernetes

초기에는 모든 데이터를 MySQL에서 직접 조회한다.

목적은 정답처럼 보이는 구조를 처음부터 만드는 것이 아니라, **측정 가능한 Baseline을 확보하고 실제 병목을 확인한 뒤 필요한 기술을 도입하는 것**이다.

단, 이후 실험 결과에 따라 Redis, Lock, Local Cache, 모니터링 구조 등은 이 문서에 정식 설계로 추가될 수 있다.

---

## 2.3 성능 문제를 추측으로 해결하지 않는다

AI가 코드 생성 과정에서 예상되는 성능 문제를 발견하더라도 임의로 최적화하지 않는다.

예를 들어 다음과 같은 최적화를 선제적으로 적용하지 않는다.

```text
Redis Cache 적용
Query 결과 Cache
DB Lock
Redis Lock
Local Cache
비동기 처리
Batch 처리
```

필요한 최적화는 실제 부하 테스트와 모니터링 결과를 근거로 적용한다.

---

## 2.4 구조를 과도하게 복잡하게 만들지 않는다

Playback Gate는 하나의 Spring Boot 애플리케이션으로 시작한다.

```text
Client
   ↓
Spring Boot
   ↓
MySQL
```

현재 요구사항만으로 MSA로 분리할 이유가 없으므로 단일 애플리케이션으로 구현한다.

향후 분리가 필요하다면 트래픽 특성, 배포 독립성, 장애 격리 등 실제 근거가 생겼을 때 결정한다.

---

# 3. 기술 스택

## Backend

* Java 21
* Spring Boot
* Spring MVC
* Spring Data JPA
* Spring Security
* Bean Validation

## Database

* MySQL 8
* HikariCP `maximum-pool-size=40` (Phase 3: Baseline에서 기본값 10이 포화되어 pending 40이 관측됨)

## Authentication

* JWT

## Test

* JUnit 5
* Spring Boot Test
* Testcontainers

## Build

* Gradle

## Local Environment

* Docker
* Docker Compose

## Performance / Observability

Phase 2부터 측정을 위해 아래를 사용한다.

* k6
* Spring Actuator
* Micrometer
* Prometheus
* Grafana

Phase 4부터 Member/Content 조회 캐시로 Caffeine을 사용한다.
Phase 5부터 동시 재생 제한은 Subscription 행 Pessimistic Lock(`SELECT … FOR UPDATE`)으로 직렬화한다.
Phase 6에서 Load/Stress/Spike를 측정했다. 이 노트북 단일 프로세스 기준으로 처리량 천장은 약 500 start RPS이고, 먼저 포화되는 것은 Hikari 40이다. Redis는 없어서 비교 대상이 아니다. 구조는 바꾸지 않았다.
Phase 7에서 Baseline과 Final을 같은 k6로 비교했다. 정리: [docs/experiments/007-final-comparison.md](experiments/007-final-comparison.md).
Redis, Distributed Lock은 도입하지 않는다.

---

# 4. 전체 시스템 구조

현재 구조는 다음과 같다. (Final: Caffeine + Subscription `FOR UPDATE`)

```text
                     Client
                        │
                        ▼
                PlaybackController
                        │
                        ▼
                PlaybackService
                        │
              ┌─────────┴─────────┐
              ▼                   ▼
           Caffeine             MySQL
      Member/Content        Subscription FOR UPDATE
                            COUNT / INSERT
```

Member, Content는 Cache Aside(Caffeine, TTL 60초)로 읽는다.
Subscription은 캐시하지 않는다. 같은 회원 요청을 직렬화하려면 행 락이 필요하고, 캐시 hit면 그 조회가 빠진다.
PlaybackSession COUNT와 INSERT는 그 락을 잡은 같은 트랜잭션에서 MySQL만 사용한다.

Redis는 단일 애플리케이션이라 도입하지 않았다. 인스턴스를 나누고 캐시 공유가 필요해지면 그때 다시 비교한다.

---

# 5. 프로젝트 구조

Repository의 전체 구조는 다음과 같이 구성한다.

```text
playback-gate/
├── src/
│   ├── main/
│   └── test/
│
├── docs/
│   ├── REQUIREMENTS.md
│   ├── DESIGN.md
│   ├── TASKS.md
│   ├── adr/
│   └── experiments/
│
├── load-test/
│   └── k6/
│
├── monitoring/
│   ├── prometheus/
│   └── grafana/
│
├── docker/
│   └── docker-compose.yml
│
└── README.md
```

각 디렉터리의 역할은 다음과 같다.

| 경로                  | 역할                       |
| ------------------- | ------------------------ |
| `src/`              | 애플리케이션 코드와 테스트 코드        |
| `docs/`             | 요구사항, 설계, 작업 목록, 의사결정 기록 |
| `docs/adr/`         | 중요한 아키텍처 선택의 이유          |
| `docs/experiments/` | 부하 테스트와 성능 개선 실험 결과      |
| `load-test/`        | k6 시나리오                  |
| `monitoring/`       | Prometheus / Grafana 설정  |
| `docker/`           | 로컬 실행 환경                 |

---

# 6. 패키지 구조

기능 중심으로 패키지를 구성한다.

```text
com.playbackgate

├── common
│   ├── exception
│   ├── response
│   └── config
│
├── auth
│   ├── JwtProvider
│   ├── JwtAuthenticationFilter
│   └── SecurityConfig
│
├── member
│   ├── domain
│   ├── repository
│   └── service
│
├── subscription
│   ├── domain
│   └── repository
│
├── content
│   ├── domain
│   └── repository
│
└── playback
    ├── controller
    ├── service
    ├── domain
    ├── repository
    └── dto
```

불필요한 인터페이스 계층이나 추상화는 만들지 않는다.

향후 실제 필요성이 발생했을 때 리팩터링한다.

---

# 7. 도메인 모델

## 7.1 Member

회원을 나타낸다.

### 주요 필드

```text
id
email
birthDate
status
createdAt
```

### MemberStatus

```text
ACTIVE
BLOCKED
WITHDRAWN
```

### 규칙

`ACTIVE` 상태의 회원만 콘텐츠를 재생할 수 있다.

---

## 7.2 Subscription

회원이 보유한 이용권을 나타낸다.

### 주요 필드

```text
id
memberId
plan
status
startedAt
expiresAt
```

### SubscriptionPlan

```text
BASIC
STANDARD
PREMIUM
```

### SubscriptionStatus

```text
ACTIVE
SUSPENDED
EXPIRED
```

### 동시 재생 제한

```text
BASIC       = 1
STANDARD    = 2
PREMIUM     = 4
```

동시 재생 가능 수는 이용권 정책으로 관리한다.

---

## 7.3 Content

재생 가능한 콘텐츠를 나타낸다.

### 주요 필드

```text
id
title
status
ageRating
requiredPlan
availableFrom
availableUntil
createdAt
```

### ContentStatus

```text
OPEN
CLOSED
```

---

## 7.4 PlaybackSession

현재 콘텐츠 재생 상태를 나타낸다.

### 주요 필드

```text
id
sessionId
memberId
contentId
deviceId
status
startedAt
expiresAt
endedAt
```

### PlaybackSessionStatus

```text
ACTIVE
ENDED
EXPIRED
```

---

# 8. 데이터베이스 관계

도메인의 기본 관계는 다음과 같다.

```text
Member
  │
  │ 1
  │
  │ N
Subscription


Member
  │
  │ 1
  │
  │ N
PlaybackSession
  │
  │ N
  │
  │ 1
Content
```

PlaybackSession은 Member와 Content를 연결한다.

---

# 9. 데이터베이스 설계

## member

```text
id              BIGINT PK
email           VARCHAR
birth_date      DATE
status          VARCHAR
created_at      DATETIME
```

---

## subscription

```text
id              BIGINT PK
member_id       BIGINT
plan            VARCHAR
status          VARCHAR
started_at      DATETIME
expires_at      DATETIME
```

---

## content

```text
id                BIGINT PK
title             VARCHAR
status            VARCHAR
age_rating        INT
required_plan     VARCHAR
available_from    DATETIME
available_until   DATETIME
created_at        DATETIME
```

---

## playback_session

```text
id              BIGINT PK
session_id      VARCHAR
member_id       BIGINT
content_id      BIGINT
device_id       VARCHAR
status          VARCHAR
started_at      DATETIME
expires_at      DATETIME
ended_at        DATETIME NULL
```

초기에는 성능을 위한 인덱스를 과도하게 생성하지 않는다.

PK, FK 및 명백하게 필요한 Unique Constraint 정도만 우선 적용한다.

성능용 Composite Index 등은 부하 테스트와 Query 분석 이후 필요성이 확인될 때 적용한다.

---

# 10. 핵심 API

## 10.1 Playback 시작

```http
POST /api/v1/playback/sessions
```

### Request

```json
{
  "contentId": 100,
  "deviceId": "iphone-001"
}
```

회원 ID는 JWT 인증 정보에서 가져온다.

### Response

```json
{
  "sessionId": "uuid",
  "playbackToken": "jwt-token",
  "expiresAt": "2026-08-13T22:00:00+09:00"
}
```

---

## 10.2 Playback 종료

```http
DELETE /api/v1/playback/sessions/{sessionId}
```

### Response

```http
204 No Content
```

해당 PlaybackSession을 `ENDED` 상태로 변경한다.

---

# 11. Playback 시작 처리 흐름

Playback 시작 요청은 다음 순서로 처리한다.

```text
POST /playback/sessions
        ↓
JWT에서 memberId 확인
        ↓
Member 조회
        ↓
Member 상태 검증
        ↓
Content 조회
        ↓
Content 상태 검증
        ↓
Content 공개 기간 검증
        ↓
Subscription 조회 (FOR UPDATE)
        ↓
Subscription 유효성 검증
        ↓
Subscription Plan 검증
        ↓
연령 제한 검증
        ↓
ACTIVE PlaybackSession 수 조회
        ↓
동시 재생 제한 검증
        ↓
PlaybackSession 생성
        ↓
Playback Token 생성
        ↓
Response
```

---

# 12. 비즈니스 규칙

## 회원 상태

```text
ACTIVE
→ 재생 가능

BLOCKED
→ 재생 불가

WITHDRAWN
→ 재생 불가
```

---

## 콘텐츠 공개 상태

다음 조건을 모두 만족해야 한다.

```text
status == OPEN

availableFrom <= now

availableUntil >= now
```

---

## 이용권 상태

다음 조건을 모두 만족해야 한다.

```text
status == ACTIVE

startedAt <= now

expiresAt >= now
```

---

## 이용권 등급

등급 순서는 다음과 같다.

```text
BASIC < STANDARD < PREMIUM
```

사용자 이용권이 콘텐츠의 `requiredPlan` 이상이어야 한다.

---

## 연령 제한

```text
회원 만 나이 >= Content.ageRating
```

이어야 한다.

날짜 계산은 서버의 현재 날짜를 기준으로 한다.

---

## 동시 재생 제한

현재 사용자의 유효한 `ACTIVE` Session 수를 조회한다.

```text
currentActiveSessionCount < plan.maxConcurrentPlayback
```

조건을 만족하는 경우에만 새로운 Session을 생성한다.

구현:

```text
Subscription SELECT … FOR UPDATE
        ↓
ACTIVE Session COUNT
        ↓
최대 허용 수 비교
        ↓
Session INSERT
```

같은 회원의 동시 요청은 Subscription 행 락에서 직렬화된다. 다른 회원끼리는 기다리지 않는다.
`start()` 트랜잭션은 `READ_COMMITTED`다. MySQL 기본 `REPEATABLE READ`에서는 Member/Content 조회가 만든 스냅샷 때문에, `FOR UPDATE`로 줄을 세워도 COUNT가 이미 커밋된 Session을 0으로 볼 수 있다.

### 동시성 설계 원칙

초기 구현의 `COUNT → 검증 → INSERT`는 동시 요청에서 Race가 났다.
BASIC 한도 1, 40 스레드 재현 결과 HTTP 200과 ACTIVE Session이 모두 40이었다.

후보를 비교한 뒤 Subscription 행 Pessimistic Lock을 선택했다.

* DB Pessimistic Lock — 채택. 다중 인스턴스에서도 DB가 락을 소유한다.
* JVM per-member Lock — 단일 프로세스에서만 맞다.
* Redis Distributed Lock / Atomic — 앱이 하나라 인프라를 추가할 이유가 없다.

재현·비교·측정은 [docs/experiments/004-concurrency.md](experiments/004-concurrency.md), 선택 이유는 [docs/adr/003-concurrency-control.md](adr/003-concurrency-control.md).

---

# 13. Session 만료 정책

PlaybackSession에는 만료 시간을 둔다.

초기 정책:

```text
Session 유효시간 = 생성 시점부터 2시간
```

Session이 다음 조건이면 활성 상태로 취급하지 않는다.

```text
status != ACTIVE
```

또는

```text
expiresAt < 현재시간
```

초기에는 별도의 Scheduler를 이용해 EXPIRED 상태를 일괄 변경하지 않는다.

동시 재생 수 계산 시 `expiresAt`을 함께 확인하여 유효한 Session만 계산한다.

---

# 14. Playback Token

재생 권한 검증을 모두 통과하면 Playback Token을 발급한다.

JWT를 사용한다.

### Payload

```json
{
  "sub": "memberId",
  "contentId": 100,
  "sessionId": "uuid",
  "deviceId": "iphone-001",
  "iat": "...",
  "exp": "..."
}
```

Token의 만료시간은 PlaybackSession의 만료시간과 동일하게 설정한다.

별도의 DRM이나 CDN 검증 서버는 프로젝트 범위에 포함하지 않는다.

Token을 정상적으로 생성하고 응답하는 것까지만 구현한다.

---

# 15. Transaction 범위

Playback Session 시작 로직은 하나의 Application Service에서 처리한다.

Session 생성 작업에는 Transaction을 적용한다.

초기에는 비즈니스 로직의 일관성을 우선해 하나의 Transaction으로 처리한다.

실제 부하 테스트 이후 Transaction 범위가 DB Connection 점유 시간이나 Lock 경합에 영향을 주는지 확인하고 필요하면 조정한다.

---

# 16. 예외 처리

API의 오류 응답 형식은 통일한다.

```json
{
  "code": "CONCURRENT_PLAYBACK_LIMIT_EXCEEDED",
  "message": "동시 재생 가능 수를 초과했습니다."
}
```

주요 오류 코드는 다음과 같다.

```text
MEMBER_NOT_FOUND
MEMBER_NOT_ACTIVE

CONTENT_NOT_FOUND
CONTENT_NOT_AVAILABLE

SUBSCRIPTION_NOT_FOUND
SUBSCRIPTION_NOT_ACTIVE
SUBSCRIPTION_EXPIRED

PLAN_NOT_ALLOWED

AGE_RESTRICTED

PLAYBACK_SESSION_NOT_FOUND
CONCURRENT_PLAYBACK_LIMIT_EXCEEDED
```

예외는 `@RestControllerAdvice`를 이용해 공통 처리한다.

---

# 17. 테스트 전략

## 17.1 Unit Test

비즈니스 정책을 검증한다.

예:

```text
BLOCKED 회원은 재생할 수 없다.

만료된 Subscription으로 재생할 수 없다.

BASIC 사용자는 STANDARD 콘텐츠를 재생할 수 없다.

17세 사용자는 18세 콘텐츠를 재생할 수 없다.

BASIC 사용자는 Active Session이 존재하면 추가 재생할 수 없다.
```

---

## 17.2 Integration Test

Spring Boot와 실제 MySQL 환경을 이용한 통합 테스트를 작성한다.

Testcontainers를 사용한다.

확인할 내용:

```text
Playback 요청
    ↓
DB 조회
    ↓
정책 검증
    ↓
PlaybackSession 저장
    ↓
Response
```

전체 과정이 정상 동작하는지 검증한다.

---

# 18. 성능 및 동시성 테스트

기본 기능 구현이 완료된 이후 다음 테스트를 순차적으로 진행한다.

* k6 Load Test
* Stress Test
* Spike Test
* 대량 동시 요청 테스트
* Race Condition 테스트
* Redis 적용 전후 성능 비교
* DB Lock 성능 비교
* Redis Lock / Atomic 처리 비교

첫 번째 부하 테스트 결과는 Baseline으로 저장하고 이후 동일한 조건에서 반복 측정한다.

```text
Baseline
   ↓
병목 분석
   ↓
설계 변경
   ↓
재측정
   ↓
Before / After 비교
```

---

# 19. 기본 구현 완료 조건

다음 조건을 모두 만족하면 성능 실험을 시작할 수 있는 Baseline 구현이 완료된 것으로 판단한다.

### 기능

* 정상 사용자의 재생 요청이 성공한다.
* 회원 상태를 검증한다.
* 콘텐츠 상태와 공개 기간을 검증한다.
* 이용권 상태와 기간을 검증한다.
* 이용권 등급을 검증한다.
* 연령 제한을 검증한다.
* 동시 재생 수를 확인한다.
* PlaybackSession을 생성한다.
* Playback Token을 발급한다.
* Playback Session을 종료할 수 있다.

### 테스트

* 주요 비즈니스 규칙 Unit Test가 존재한다.
* Testcontainers 기반 Integration Test가 존재한다.
* 테스트가 모두 성공한다.

### 환경

* Docker Compose를 통해 MySQL을 실행할 수 있다.
* 로컬 환경에서 애플리케이션을 실행할 수 있다.

---

# 20. 프로젝트 진행 흐름

기본 구현 완료 후 바로 최적화를 시작하지 않는다.

먼저 성능을 측정한다.

```text
기본 구현 완료
    ↓
k6 도입
    ↓
Baseline Load Test
    ↓
RPS / p95 / p99 측정
    ↓
Prometheus / Grafana 연결
    ↓
병목 분석
    ↓
DB 최적화
    ↓
재측정
    ↓
Redis Cache
    ↓
재측정
    ↓
동시성 문제 재현
    ↓
동시성 제어 방식 비교
    ↓
Spike Test
```

각 단계는 이전 단계의 측정 결과를 근거로 진행한다.

---

# 21. 설계 변경 원칙

개발 과정에서 설계 변경이 필요할 경우 바로 코드를 변경하지 않는다.

먼저 다음 내용을 정리한다.

```text
현재 문제

↓

변경하려는 설계

↓

변경 이유

↓

예상되는 장점

↓

예상되는 단점
```

그 후 설계를 변경한다.

특히 성능 개선 단계에서는 반드시 측정 결과를 근거로 변경한다.

중요한 구조 변경이 발생한 경우 문서를 다음 순서로 갱신한다.

```text
실험 / 문제 발견
      ↓
ADR 작성
      ↓
DESIGN.md 갱신
      ↓
TASKS.md 작업 분해
      ↓
구현
      ↓
재측정
```

AI Coding Assistant의 세부 사용 방식은 별도의 `바이브 코딩 전략` 문서를 따른다.

---

# 22. 설계의 핵심 원칙

Playback Gate에서 중요한 것은 많은 기술을 사용하는 것이 아니다.

```text
문제 발견
    ↓
원인 분석
    ↓
해결 방법 결정
    ↓
적용
    ↓
측정
```

이라는 과정을 반복하는 것이다.

설계 자체도 고정된 정답으로 취급하지 않는다.

현재 구조는 언제든 바뀔 수 있지만, 변경은 반드시 **문제와 측정 결과를 근거로 한다.**

최종적으로 `DESIGN.md`는 프로젝트가 현재 어떤 구조로 동작하는지를 설명하는 **최신 설계의 단일 기준 문서**로 유지한다.
