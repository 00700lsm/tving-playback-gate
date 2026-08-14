# 실험 005 - Load / Stress

## 목적

Phase 5까지 적용한 앱이 **목표 수준의 지속 부하**에서 안정적인지, **어디부터 무너지는지** 측정한다.
추측으로 Redis, 풀 크기, 인덱스를 넣지 않는다.

## 현재 문제

Phase 2~5 k6는 최대 100 VU + `sleep(0.1)`이라 Iteration RPS가 약 300이었다.
실제 OTT의 일반/한계 부하는 그보다 높다. 로드맵 기준은 500 RPS 유지, 이후 1,000 → 5,000으로 올린다.

## 가설

Hikari `maximum-pool-size=40`이고 `start()`가 트랜잭션 동안 커넥션을 붙든다.
요청이 20 ms면 이론 상한은 약 2,000 RPS, 100 ms면 약 400 RPS다.
500 start RPS는 이미 풀 근처일 수 있다.

## 테스트 환경

| 항목 | 값 |
| --- | --- |
| 일시 | 2026-08-14 |
| OS | macOS (Darwin arm64), Docker Desktop 메모리 8 GB |
| App | Spring Boot 3.4.5, Java 21, 단일 프로세스, `local,load-test` |
| DB | MySQL 8.0 (Docker), Hikari 40 |
| Cache | Caffeine Member/Content |
| Lock | Subscription `FOR UPDATE` + `READ_COMMITTED` |
| 부하 | k6 0.55.0 (Docker), arrival-rate, start 후 DELETE, timeout 10s |
| 회원 | PREMIUM `memberId` 9..2008, contentId=1 |

Redis는 없다. 각 테스트 전 `playback_session` TRUNCATE.

## Load Test

목표: **500 start RPS를 2분 유지**.

스크립트: `load-test/k6/playback-load.js` (`constant-arrival-rate` 500, maxVUs 400)

### 결과

| 지표 | 값 |
| --- | ---: |
| 목표 | 500 /s |
| 달성 iteration rate | 492.5 /s |
| iterations | 59,190 |
| dropped_iterations | 814 |
| start 실패 | 0.00% |
| start p50 | 105.69 ms |
| start p95 | 591.09 ms |
| start p99 | 912.26 ms |
| Hikari active max | 40 |
| Hikari pending max | 159 |
| Hikari timeout | 0 |
| process CPU max | ~21% |
| system CPU max | ~99% |

k6가 16초경 VU 400 한도에 닿았다. 성공률은 100%다. 지연은 Phase 5 baseline(p50 21 ms, p95 33 ms)보다 크게 나쁘다.

풀 acquire 평균은 이 구간에서 약 **86 ms**. 커넥션 40개가 거의 항상 바빴다.

### 판단

500 RPS는 **에러 없이 유지**된다. 다만 p95 591 ms는 이미 포화에 가깝다.
Java 프로세스 CPU는 20%대라 CPU 한계가 아니다. 먼저 차는 것은 **Hikari 40**이다.
system CPU 99%는 k6+MySQL+앱이 한 노트북을 나눠 쓰는 영향이다.

## Stress Test

목표: 500 → 1,000 → 2,000 → 3,000 → 5,000으로 올려 **한계점**을 찾는다.

스크립트: `load-test/k6/playback-stress.js` (maxVUs 1000)

### 구간

| 목표 RPS | 실제 완료량 | 메모 |
| --- | --- | --- |
| 500 (45s) | ~500 /s | VU 여유, 유지됨 |
| 1,000 | 목표 미달, 완료 ~500 /s | 1m14s VU 1000 한도. 곧 10s HTTP timeout |
| 2,000~5,000 | 완료 ~500 /s | k6는 목표 rate를 표시하지만 iteration을 drop |

### 전체 요약

| 지표 | 값 |
| --- | ---: |
| 완료 iterations | 148,009 |
| 평균 iteration rate | 501.4 /s |
| dropped_iterations | 436,740 |
| start error | 0.28% (대부분 10s timeout) |
| start p50 | 848.65 ms |
| start p95 | 1,340.72 ms |
| start p99 | 1,658.72 ms |
| Hikari active/pending max | 40 / 159 |
| Hikari timeout | 0 |
| process CPU max | ~22% |

테스트 후 health 200, pending 0, ACTIVE session 0.

### 판단

**처리량 천장은 약 500 start RPS**다. 1,000을 넣어도 완료량은 늘지 않고 대기·timeout만 는다.
에러가 나기 시작하는 시점은 목표 1,000 구간(약 1분 20초). 앱 5xx가 아니라 클라이언트 10초 타임아웃이다.
Hikari connection timeout은 0이다. 스레드는 커넥션을 기다리다 k6가 먼저 끊는다.

pending 159 + active 40 ≈ 199. Tomcat 기본 `maxThreads` 200과 맞는다. 풀이 찬 뒤 톰캣 스레드가 대기하고, 그다음 요청은 커넥터에서 밀린다.

Redis는 비교 대상이 아니다. 없는 구성에서 **DB 커넥션 풀이 먼저** 병목이다.

## 분석

1. 트래픽을 올리면 **지연이 먼저** 나빠지고(p50 21→106→849 ms), 그다음 drop/timeout이 난다.
2. 최대 처리량은 이 환경에서 **약 500 start RPS**.
3. Java CPU 20%, 풀 100%. CPU보다 **커넥션 점유 시간**(COUNT+INSERT+JWT가 한 트랜잭션)이 상한을 정한다.
4. 부하가 0이 되면 pending은 바로 0이 된다. 프로세스는 죽지 않았다.

후보(아직 적용하지 않음): 풀 크기, JWT를 트랜잭션 밖으로, Tomcat 스레드. 측정 없이 넣지 않는다.

## 결론

- 500 RPS Load는 성공률 100%로 버틴다. 품질(p95)은 이미 나쁘다.
- Stress에서 천장은 ~500 RPS. 1,000부터는 대기와 timeout이다.
- 병목은 Redis가 아니라 Hikari 40 + 트랜잭션 동안 커넥션 점유.
- 구조는 바꾸지 않았다.

Spike는 [006-spike-test.md](006-spike-test.md).
