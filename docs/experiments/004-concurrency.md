# 실험 004 - 동시성 문제 재현 및 해결

## 목적

BASIC 한도(1)에서 COUNT-INSERT Race를 테스트로 재현한 뒤, 후보를 비교하고 한 가지를 적용한다.
Redis를 써보기 위해 Redis를 넣지 않는다.

## 현재 문제

Phase 1~4는 `COUNT → 검증 → INSERT`다. `@Transactional`만으로는 같은 회원의 동시 요청이 같은 COUNT를 본다.

## 가설

같은 회원 요청을 Subscription 행에서 직렬화하면 ACTIVE Session이 한도를 넘지 않는다.
회원 2000명이 흩어지는 k6 baseline에서는 경합이 거의 없어 p95 영향은 작을 것이다.

## 재현

`ConcurrentPlaybackTest`: BASIC 회원, 40 스레드가 동시에 `POST /sessions`. Lock 없음.

| 항목 | 값 |
| --- | ---: |
| HTTP 200 | 40 |
| ACTIVE Session | 40 |
| 허용 | 1 |

Race가 재현됐다. 이 상태에서 고친다.

## 대안 비교

| 기준 | A. DB `FOR UPDATE` | B. JVM per-member Lock | C. Redis Lock / Atomic |
| --- | --- | --- | --- |
| 정확성 (단일 앱) | 보장 | 보장 | 보장 (구현에 따름) |
| 여러 서버 | DB가 락 | 깨짐 | 가능 |
| 구현 복잡도 | JPA `@Lock` | 맵 + finally | 인프라 + 키 설계 |
| 장애 | MySQL | 프로세스 재시작 시 락 소멸 | Redis 다운 시 재생 불가 또는 우회 |
| 추가 인프라 | 없음 | 없음 | Redis |
| Lock 대기 | 커넥션 + 행 락 | 힙 | 네트워크 + Redis |
| k6 (회원 분산) | 경합 거의 없음 | 경합 거의 없음 | 이득 없음 |

C는 앱이 하나라 이 실험의 문제를 푸는 데 필요 없다. B는 지금 동작하지만 인스턴스를 나누면 같은 버그가 돌아온다.

선택: **A**. 이유와 장단점은 [docs/adr/003-concurrency-control.md](../adr/003-concurrency-control.md).

## 변경 사항

- `SubscriptionRepository.findLatestByMemberIdForUpdate` — EntityManager `PESSIMISTIC_WRITE` + `LIMIT 1`
- `PlaybackService.start`는 Subscription을 캐시가 아니라 이 조회로 읽는다
- `start()` 트랜잭션 isolation은 `READ_COMMITTED` (MySQL `REPEATABLE READ` 스냅샷이면 락 대기 중 커밋된 INSERT를 COUNT가 못 봄)
- Member/Content Caffeine은 유지
- Subscription Caffeine은 제거 (락 대상 행을 캐시하면 락 조회가 사라짐)

테스트 프로필은 기존처럼 `spring.cache.type=none`.

## 동시성 테스트 (적용 후)

같은 `ConcurrentPlaybackTest` 40 스레드:

| 항목 | Lock 없음 | `FOR UPDATE` |
| --- | ---: | ---: |
| HTTP 200 | 40 | 1 |
| ACTIVE Session | 40 | 1 |

한도가 지켜진다.

## 재측정

Phase 4와 동일: 회원 2000, contentId=1, k6 ramping 10→50→100 VU, start 후 DELETE, 세션 테이블 TRUNCATE 후 시작.

k6는 회원 ID를 흩뿌리므로 **한도 Race가 아니라** 락 오버헤드를 본다.

## 결과

k6 전체 구간 약 5분 6초, 완료 iteration 90,622. `dial: i/o timeout` 50건(start 실패 0.06%).

| 지표 | Phase 4 (Caffeine, Lock 없음) | Phase 5 (Caffeine + `FOR UPDATE`) |
| --- | ---: | ---: |
| Iteration RPS | 321.5 | 296.0 |
| start 성공 | 98,286 | 90,572 |
| start 실패 | 41 (0.04%) | 50 (0.06%) |
| start p50 | 15.82 ms | 21.41 ms |
| start p95 | 26.82 ms | 32.61 ms |
| start p99 | 36.10 ms | 41.31 ms |
| Hikari acquire 평균 | ~0.68 ms | ~1.06 ms |

Hit Rate (Actuator `cache.gets`). Subscription 캐시는 제거했다.

| cache | hit | miss | hit rate |
| --- | ---: | ---: | ---: |
| contents | 90,548 | 24 | 99.97% |
| members | 81,608 | 8,964 | 90.11% |

측정 후 `playback_session` 90,572행, ACTIVE 0 (start 성공 건과 같고 DELETE로 종료됨).

## Before / After

- 한도: 테스트에서 40/40 성공 → 1/1. 이 Phase의 목표다.
- 지연: p50 15.8 → 21.4 ms, p95 26.8 → 32.6 ms. Subscription을 매 요청 DB에서 `FOR UPDATE`로 읽어서 수 ms가 늘었다.
- 처리량: RPS 322 → 296. 회원 2000명이라 락 경합은 거의 없고, 실패 50건이 k6 기본 60s timeout이라 VU가 오래 묶였다.

## 분석

락은 **같은 계정 동시 요청**을 막기 위한 것이다. 이 k6는 계정을 흩뿌리므로 정확성 실험이 아니다. 분산 부하에서 p95가 조금 나빠진 것은 캐시 1개 제거 + 비경합 `FOR UPDATE` 비용으로 본다.

`FOR UPDATE`만 넣고 isolation을 그대로 두면, 테스트(캐시 없음)에서 Member/Content SELECT가 `REPEATABLE READ` 스냅샷을 만들어 COUNT가 계속 0이었다. `READ_COMMITTED`를 같이 써야 한도가 지켜졌다.

Redis는 이 단계에 넣지 않는다. 앱이 하나이고 DB 행 락으로 충분하다.

## 결론

- Race를 테스트로 재현했다 (40/40 성공).
- DB Pessimistic vs JVM vs Redis를 비교했고 Pessimistic + `READ_COMMITTED`를 골랐다.
- 적용 후 동시 요청에서 ACTIVE=1을 테스트로 보장한다.
- 동일 k6에서 p95는 약 6 ms 늘었다. 한도를 깨지 않기 위한 비용이다.
- Redis는 이 단계에 넣지 않는다.

다음: Phase 6 대규모 / 순간 트래픽 검증.
