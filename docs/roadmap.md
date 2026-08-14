# Playback Gate - ROADMAP

## 1. 문서 목적

이 문서는 Playback Gate 프로젝트의 **전체 진행 순서와 각 단계의 완료 조건**을 정의한다.

각 문서의 역할은 다음과 같다.

```text
REQUIREMENTS.md
→ 무엇을 만족해야 하는가

DESIGN.md
→ 현재 시스템은 어떤 구조인가

ROADMAP.md
→ 프로젝트를 어떤 순서로 진행할 것인가

TASKS.md
→ 현재 단계에서 구체적으로 무엇을 할 것인가

adr/
→ 중요한 설계 결정을 왜 내렸는가

experiments/
→ 실제 실험 결과가 어땠는가
```

`ROADMAP.md`는 특정 기술을 반드시 도입하기 위한 계획이 아니다.

Playback Gate에서는 다음 흐름을 따른다.

```text
구현
 ↓
측정
 ↓
문제 발견
 ↓
원인 분석
 ↓
대안 비교
 ↓
설계 결정
 ↓
개선
 ↓
재측정
```

따라서 Redis, Lock, Index 등의 기술은 미리 정답으로 적용하지 않는다.

**실제 문제와 측정 결과가 해당 기술의 필요성을 뒷받침할 때만 도입한다.**

---

# 2. 전체 진행 흐름

```text
Phase 1
Baseline 애플리케이션 구현
        ↓
Phase 2
Baseline 성능 측정
        ↓
Phase 3
DB 병목 분석 및 개선
        ↓
Phase 4
Cache 적용 가능성 검증
        ↓
Phase 5
동시성 문제 재현 및 해결
        ↓
Phase 6
대규모 / 순간 트래픽 검증
        ↓
Phase 7
최종 성능 비교 및 정리
```

각 Phase는 이전 단계의 결과를 기반으로 진행한다.

---

# 3. Phase 1 - Baseline 애플리케이션 구현

## 목표

성능 최적화가 적용되지 않은 **정상 동작하는 Playback Gate**를 만든다.

이 단계의 목적은 최종 구조를 만드는 것이 아니라 이후 실험에서 비교 기준으로 사용할 Baseline 애플리케이션을 확보하는 것이다.

---

## 구현 범위

다음 기능을 구현한다.

* Member 조회 및 상태 검증
* Content 조회 및 공개 상태 검증
* Subscription 조회 및 유효성 검증
* 이용권 등급 검증
* 연령 제한 검증
* 동시 재생 수 확인
* PlaybackSession 생성
* PlaybackSession 종료
* Playback Token 발급

---

## 초기 구조

```text
Client
   ↓
Spring Boot
   ↓
MySQL
```

이 단계에서는 다음 기술을 사용하지 않는다.

* Redis
* Caffeine
* Kafka
* Distributed Lock
* Redis Lock
* 비동기 처리
* Kubernetes

---

## 테스트

* Unit Test
* Integration Test
* Testcontainers

주요 비즈니스 정책이 정상적으로 동작하는지 검증한다.

---

## 완료 조건

* 모든 기본 기능 요구사항이 구현되어 있다.
* Unit Test가 통과한다.
* Integration Test가 통과한다.
* Docker Compose로 MySQL을 실행할 수 있다.
* 애플리케이션을 로컬에서 실행할 수 있다.
* 성능 최적화가 아직 적용되지 않았다.

조건을 만족하면 Phase 2로 이동한다.

---

# 4. Phase 2 - Baseline 성능 측정

## 목표

현재 Playback Gate가 **어느 정도의 트래픽까지 처리할 수 있는지 객관적인 기준값을 확보**한다.

아직 성능을 개선하지 않는다.

먼저 현재 상태를 측정한다.

---

## 주요 작업

### k6 도입

다음 부하 테스트 환경을 구성한다.

```text
적은 부하
 ↓
중간 부하
 ↓
높은 부하
```

사용자 및 요청량을 점진적으로 증가시킨다.

---

## 측정 지표

최소한 다음 지표를 기록한다.

* RPS
* p50
* p95
* p99
* Error Rate
* CPU
* Memory
* JVM Heap
* GC
* DB Connection Pool
* DB Query 시간

---

## Observability

필요한 지표를 확인하기 위해 다음 도구를 구성한다.

* Spring Actuator
* Micrometer
* Prometheus
* Grafana

---

## 결과 문서

다음 문서를 작성한다.

```text
docs/experiments/001-baseline.md
```

내용:

```text
테스트 목적

테스트 환경

데이터 규모

k6 Scenario

RPS

p50

p95

p99

Error Rate

CPU / Memory

DB 상태

관찰된 현상
```

---

## 완료 조건

* 반복 가능한 k6 테스트가 존재한다.
* Baseline RPS가 측정되어 있다.
* p50 / p95 / p99가 측정되어 있다.
* Error Rate가 측정되어 있다.
* 주요 서버 및 DB Metric을 확인할 수 있다.
* 동일한 테스트를 다시 실행할 수 있다.
* Baseline 결과가 experiments 문서에 기록되어 있다.

조건을 만족하면 Phase 3로 이동한다.

---

# 5. Phase 3 - DB 병목 분석 및 개선

## 목표

Playback 요청 처리 과정에서 **Database가 실제 병목인지 확인하고 필요한 경우 개선한다.**

DB 최적화를 반드시 하는 단계가 아니다.

DB가 실제 문제인지 검증하는 단계다.

---

## 분석 대상

다음 항목을 확인한다.

* 요청당 SQL 실행 횟수
* Query 실행시간
* 실행 계획
* Full Scan 여부
* Index 사용 여부
* Connection Pool 사용률
* Transaction 범위
* DB Connection 점유 시간

---

## 진행 방식

```text
Baseline 분석
     ↓
DB 병목 존재?
   ┌─┴─┐
  NO   YES
  ↓     ↓
기록   원인 분석
        ↓
     개선 후보 비교
        ↓
       적용
        ↓
     동일 조건 재측정
```

---

## 가능한 개선 후보

문제에 따라 다음 방법을 검토할 수 있다.

* Index
* Composite Index
* Query 개선
* 불필요한 Query 제거
* Fetch 전략 개선
* Connection Pool 조정
* Transaction 범위 조정

단, 측정 결과와 관계없이 모두 적용하지 않는다.

---

## 결과 문서

```text
docs/experiments/002-db-analysis.md
```

구조:

```text
문제

Baseline 데이터

가설

분석

변경사항

재측정

Before / After

결론
```

중요한 구조적 결정이 발생했다면 ADR을 작성한다.

예:

```text
docs/adr/001-playback-session-index.md
```

사소한 Index 추가까지 모두 ADR로 기록할 필요는 없다.

---

## 완료 조건

* Playback API에서 발생하는 주요 Query를 파악했다.
* 실행 계획을 확인했다.
* DB가 병목인지 판단했다.
* 필요한 경우 개선을 적용했다.
* 동일한 조건에서 다시 측정했다.
* Baseline과 결과를 비교했다.

조건을 만족하면 Phase 4로 이동한다.

---

# 6. Phase 4 - Cache 적용 가능성 검증

## 목표

DB에서 반복적으로 조회되는 데이터 중 **Cache를 적용할 가치가 있는 데이터가 실제로 존재하는지 확인한다.**

Redis를 사용해보기 위해 Redis를 넣는 것이 목적이 아니다.

---

## 분석 대상

다음 데이터를 중심으로 확인한다.

* Content
* Subscription
* Member
* PlaybackSession

각 데이터에 대해 다음 질문을 한다.

```text
조회 빈도가 높은가?

수정 빈도가 낮은가?

약간 오래된 데이터가 반환되어도 되는가?

여러 서버에서 데이터를 공유해야 하는가?

DB 조회가 실제 성능 병목인가?
```

---

## 진행 흐름

```text
DB 반복 조회 발견
       ↓
Cache 효과 예상
       ↓
후보 비교
 ┌─────┼─────┐
 ↓     ↓     ↓
DB   Local  Redis
유지  Cache
       ↓
설계 결정
       ↓
적용
       ↓
재측정
```

---

## 주요 학습 내용

* Cache Aside
* Cache Hit
* Cache Miss
* TTL
* Cache Invalidation
* Local Cache
* Distributed Cache
* Redis

---

## ADR

Redis 또는 Local Cache 도입처럼 시스템 구조가 변경된다면 ADR을 작성한다.

예:

```text
docs/adr/002-content-cache-strategy.md
```

내용:

```text
문제

대안

DB Only
Caffeine
Redis

선택

선택 이유

장점

단점
```

---

## DESIGN.md 갱신

Redis가 실제 시스템에 추가됐다면 `DESIGN.md`도 현재 구조와 일치하도록 수정한다.

예:

```text
Before

Spring Boot
    ↓
MySQL


After

Spring Boot
 ├─ Redis
 └─ MySQL
```

---

## 결과 문서

```text
docs/experiments/003-cache.md
```

---

## 완료 조건

* Cache 후보 데이터를 분석했다.
* Cache 필요성을 측정 결과로 판단했다.
* Cache를 적용했다면 적용 이유를 ADR에 기록했다.
* DESIGN.md가 현재 구조와 일치한다.
* Cache 적용 전후 성능을 동일 조건에서 비교했다.
* Cache Hit Rate 등의 관련 지표를 확인할 수 있다.

조건을 만족하면 Phase 5로 이동한다.

---

# 7. Phase 5 - 동시성 문제 재현 및 해결

## 목표

동일 사용자가 동시에 재생을 요청했을 때 발생할 수 있는 **Race Condition을 직접 재현하고 해결한다.**

---

## 문제 상황

BASIC 이용권의 최대 동시 재생 수를 1이라고 가정한다.

```text
Request A                  Request B

ACTIVE Session = 0        ACTIVE Session = 0

재생 가능                 재생 가능

INSERT                     INSERT
```

결과:

```text
허용 = 1

실제 ACTIVE Session = 2
```

이 상황을 실제 테스트로 재현한다.

---

## 첫 번째 목표

**문제를 해결하기 전에 Race Condition부터 확실하게 재현한다.**

예:

```text
동일 사용자

10개 동시 요청
100개 동시 요청

↓

ACTIVE Session 개수 확인
```

---

## 해결 후보

다음 방법을 비교한다.

### DB Pessimistic Lock

```text
DB에서 대상 데이터 Lock
```

### Redis Distributed Lock

```text
사용자 단위 Distributed Lock
```

### Redis Atomic Operation

```text
Redis 연산 자체를 원자적으로 처리
```

필요하다면 다른 방식도 추가로 비교할 수 있다.

---

## 비교 기준

단순히 성공/실패만 비교하지 않는다.

* 정확성
* RPS
* p95
* p99
* Lock 대기시간
* 구현 복잡도
* 장애 상황
* 여러 서버에서의 동작 여부

---

## 결과 문서

```text
docs/experiments/004-concurrency.md
```

최종 동시성 제어 전략이 결정되면 ADR을 작성한다.

```text
docs/adr/003-concurrency-control.md
```

그리고 `DESIGN.md`를 현재 구조에 맞게 수정한다.

---

## 완료 조건

* Race Condition을 재현했다.
* 테스트를 통해 잘못된 Session 생성을 확인했다.
* 최소 2개 이상의 해결 방식을 비교했다.
* 최종 방식을 선택했다.
* 선택 이유를 ADR에 기록했다.
* 동시 요청에서도 이용권별 최대 Session 수가 보장된다.
* 최종 구조가 DESIGN.md에 반영되어 있다.

조건을 만족하면 Phase 6로 이동한다.

---

# 8. Phase 6 - 대규모 / 순간 트래픽 검증

## 목표

개선된 Playback Gate가 실제 OTT에서 발생할 수 있는 **높은 트래픽과 순간적인 트래픽 증가 상황에서도 어떻게 동작하는지 확인한다.**

---

## Load Test

일정한 부하를 지속적으로 발생시킨다.

```text
500 RPS

↓

일정 시간 유지
```

목적:

> 목표 수준의 일반 트래픽에서 안정적인가?

---

## Stress Test

트래픽을 계속 증가시켜 시스템의 한계를 확인한다.

```text
500 RPS
 ↓
1,000
 ↓
2,000
 ↓
5,000
 ↓
...
```

목적:

> 어느 시점에서 시스템이 무너지기 시작하는가?

---

## Spike Test

순간적으로 트래픽을 급격하게 증가시킨다.

예:

```text
18:59

500 RPS

     ↓

19:00 경기 시작

     ↓

5,000 RPS

     ↓

다시 500 RPS
```

---

## 확인 지표

* RPS
* p50
* p95
* p99
* Error Rate
* CPU
* Memory
* JVM
* GC
* DB Connection
* Redis Latency
* Lock 대기
* Cache Hit Rate

---

## 주요 질문

```text
트래픽 증가 시 어떤 지표가 먼저 악화되는가?

시스템의 최대 처리량은 어디인가?

부하가 줄어들면 정상 상태로 회복되는가?

Error가 발생하기 시작하는 시점은 언제인가?

DB와 Redis 중 어느 쪽이 먼저 병목이 되는가?
```

---

## 결과 문서

```text
docs/experiments/005-load-test.md
docs/experiments/006-spike-test.md
```

새로운 구조 변경이 필요하다면:

```text
Experiment
    ↓
문제 확인
    ↓
대안 분석
    ↓
ADR
    ↓
DESIGN 수정
    ↓
TASK 생성
    ↓
구현
    ↓
재측정
```

순서로 처리한다.

---

## 완료 조건

* Load Test를 수행했다.
* Stress Test를 수행했다.
* Spike Test를 수행했다.
* 시스템의 성능 한계를 확인했다.
* 병목 지점을 설명할 수 있다.
* 부하 종료 후 시스템 회복 여부를 확인했다.
* 주요 결과가 experiments에 기록되어 있다.

조건을 만족하면 Phase 7로 이동한다.

---

# 9. Phase 7 - 최종 성능 비교 및 정리

## 목표

프로젝트 시작 시점과 최종 상태를 비교하여 **실제로 무엇이 개선됐는지 수치로 설명한다.**

---

## 비교 대상

최초 Baseline과 최종 시스템을 동일 조건에서 비교한다.

| 항목            | Baseline | Final |
| ------------- | -------: | ----: |
| RPS           |      측정값 |   측정값 |
| p50           |      측정값 |   측정값 |
| p95           |      측정값 |   측정값 |
| p99           |      측정값 |   측정값 |
| Error Rate    |      측정값 |   측정값 |
| DB Query      |      측정값 |   측정값 |
| DB Connection |      측정값 |   측정값 |

실제 측정값만 사용한다.

---

## 최종 분석

다음 질문에 답한다.

### 어떤 문제가 있었는가?

### 문제의 원인은 무엇이었는가?

### 어떤 대안을 검토했는가?

### 왜 현재 방식을 선택했는가?

### 실제로 얼마나 개선됐는가?

### 가장 효과가 컸던 변경은 무엇인가?

### 예상과 다르게 효과가 없었던 변경은 무엇인가?

### 실제 서비스라면 추가로 무엇을 고려해야 하는가?

---

## 최종 산출물

```text
README.md

docs/
├── REQUIREMENTS.md
├── DESIGN.md
├── ROADMAP.md
├── TASKS.md
│
├── adr/
│   └── 실제 설계 결정 기록
│
└── experiments/
    └── 전체 실험 기록
```

README에는 모든 실험을 그대로 복사하지 않고 핵심 결과만 요약한다.

---

# 10. Phase 전환 규칙

AI Coding Assistant가 임의로 다음 Phase로 넘어가지 않는다.

각 Phase의 완료 조건을 먼저 확인한다.

```text
현재 Phase 작업
      ↓
완료 조건 확인
      ↓
충족?
 ┌────┴────┐
 │         │
NO        YES
 │         │
현재      결과 기록
Phase     ↓
계속    다음 Phase
```

완료 조건이 충족되지 않았다면 현재 Phase에서 작업을 계속한다.

---

# 11. ADR 작성 규칙

Phase가 바뀐다고 해서 반드시 ADR을 작성하지 않는다.

ADR은 **중요한 기술적 의사결정이 발생했을 때만 작성한다.**

예:

```text
Phase 1 → Phase 2

단순 측정 단계 이동
→ ADR 필요 없음
```

반면:

```text
Redis Cache 도입 결정

→ 시스템 구조 변경

→ ADR 작성
```

또는:

```text
DB Lock 대신 Redis Atomic Operation 선택

→ 데이터 정합성 구조 변경

→ ADR 작성
```

즉:

```text
Phase 변경 ≠ ADR

중요한 설계 결정 = ADR
```

이다.

---

# 12. Experiment 작성 규칙

성능과 관련된 판단은 가능하면 Experiment를 먼저 만든다.

```text
측정
 ↓
Experiment 기록
 ↓
결과 분석
 ↓
설계 변경 필요?
```

Experiment의 기본 형식은 다음과 같다.

```markdown
# 실험 제목

## 목적

## 현재 문제

## 가설

## 테스트 환경

## 테스트 방법

## 변경 사항

## 결과

## Before / After

## 분석

## 결론
```

---

# 13. TASKS.md 운영 방식

`ROADMAP.md`에는 프로젝트 전체 흐름을 작성한다.

반면 `TASKS.md`에는 **현재 Phase에서 해야 할 작업만 구체적으로 작성한다.**

예:

```text
현재 Phase = Phase 1

TASKS.md

- [ ] Member 구현
- [ ] Content 구현
- [ ] Subscription 구현
- [ ] PlaybackSession 구현
- [ ] Playback 시작 API 구현
- [ ] Playback 종료 API 구현
- [ ] Unit Test
- [ ] Integration Test
```

Phase 1이 끝난 뒤:

```text
현재 Phase = Phase 2

TASKS.md

- [ ] k6 환경 구성
- [ ] Baseline Scenario 작성
- [ ] Actuator 구성
- [ ] Prometheus 구성
- [ ] Grafana 구성
- [ ] Baseline 측정
- [ ] Experiment 문서 작성
```

처럼 갱신한다.

---

# 14. DESIGN.md와의 관계

`ROADMAP.md`는 앞으로 무엇을 할지를 정의한다.

`DESIGN.md`는 **현재 실제 시스템이 어떤 구조인지를 정의한다.**

따라서 ROADMAP에:

```text
Phase 4
Redis Cache 검토
```

가 존재한다고 해서 DESIGN에 Redis를 미리 추가하지 않는다.

실험을 통해 Redis 도입이 결정된 이후에만:

```text
ADR 작성
 ↓
DESIGN.md 수정
 ↓
구현
```

한다.

---

# 15. 프로젝트 전체 원칙

Playback Gate에서는 기술 사용 자체를 목표로 하지 않는다.

```text
Redis를 써보고 싶다

↓

Redis 도입
```

방식으로 진행하지 않는다.

항상 다음 순서를 따른다.

```text
문제
 ↓
측정
 ↓
원인 분석
 ↓
대안 비교
 ↓
기술 선택
 ↓
적용
 ↓
재측정
```

따라서 ROADMAP에 언급된 기술이 최종 시스템에 반드시 포함되는 것은 아니다.

실험 결과 필요하지 않다고 판단된다면 **도입하지 않는 것도 정상적인 결과**로 인정한다.

최종 목표는 많은 기술을 사용하는 것이 아니라,

> **백엔드 시스템의 문제를 측정하고, 원인을 분석하고, 적절한 해결 방법을 선택한 뒤 실제 개선 효과를 검증하는 전체 과정을 경험하는 것**

이다.
