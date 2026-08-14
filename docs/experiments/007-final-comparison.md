# 실험 007 - 최종 성능 비교

## 목적

Phase 1~6을 마친 뒤, **최초 Baseline과 최종 시스템**을 같은 k6로 비교하고 무엇을 배웠는지 정리한다.
새 최적화는 넣지 않는다.

## 비교 조건

동일 스크립트 `load-test/k6/playback-baseline.js` (ramping 10→50→100 VU, start 후 DELETE, 회원 2000, contentId=1).

| 항목 | Baseline (Phase 2) | Final (Phase 5 이후) |
| --- | --- | --- |
| 앱 | Spring Boot 하나 | 동일 |
| Hikari | 10 | 40 |
| Cache | 없음 | Caffeine Member/Content |
| 동시재생 | COUNT→INSERT, Lock 없음 | Subscription `FOR UPDATE` + `READ_COMMITTED` |
| Redis | 없음 | 없음 |

Phase 6 Load/Stress/Spike는 조건이 다르므로 아래 표에 섞지 않는다. 한계 숫자는 별도 절.

## 같은 k6 비교

| 항목 | Baseline | Final |
| ---: | ---: | ---: |
| Iteration RPS | 321.5 | 296.0 |
| start p50 | 14.61 ms | 21.41 ms |
| start p95 | 34.62 ms | 32.61 ms |
| start p99 | (미기록) | 41.31 ms |
| start Error Rate | 0.03% | 0.06% |
| 요청당 DB (start, cache hit) | 5 (member, content, sub, COUNT, INSERT) | 3 (sub `FOR UPDATE`, COUNT, INSERT) |
| DB Connection 한도 | 10 | 40 |
| Hikari pending max | 40 | (Phase 5 구간에서 풀 대기는 줄고, acquire ~1.1 ms) |
| BASIC 40 동시 요청 ACTIVE | (미측정, 이후 40으로 재현) | 1 |

출처: [001-baseline.md](001-baseline.md), [004-concurrency.md](004-concurrency.md).

## 단계별 요약

| Phase | 한 일 | 같은 k6에서 본 것 |
| --- | --- | --- |
| 1 | Baseline 앱 | — |
| 2 | 측정만 | RPS 322, p50 14.6 ms, 풀 10 포화 |
| 3 | Hikari 40 | RPS 326(+1.4%), p50 21.6 ms로 악화, pending 40→17 |
| 4 | Caffeine | p50 15.8, p95 26.8. RPS는 그대로. Redis 불필요 |
| 5 | `FOR UPDATE` | 한도 40→1. p95 26.8→32.6. RPS 322→296 |
| 6 | 500 / 1k / spike | 천장 ~500 start RPS. 병목은 풀 40 |

## 최종 분석

### 어떤 문제가 있었는가?

1. 커넥션 풀 10에서 스레드가 대기했다.
2. 요청마다 Member/Content/Subscription을 MySQL에서 읽었다.
3. `COUNT → INSERT`는 같은 계정 동시 요청에서 한도를 깼다 (BASIC 40 스레드 → ACTIVE 40).
4. 500 start RPS 근처에서 지연이 급증하고, 그 위로는 처리량이 늘지 않았다.

### 문제의 원인은 무엇이었는가?

1. Hikari 기본 10 + 트랜잭션이 COUNT/INSERT/JWT 동안 커넥션을 붙듦.
2. 읽기 많은 마스터 데이터가 매 요청 DB를 탐.
3. 트랜잭션만으로는 두 요청이 같은 COUNT=0을 봄. MySQL `REPEATABLE READ` 스냅샷은 `FOR UPDATE`만으로 부족했다.
4. 풀 40 × 커넥션 점유 시간이 처리량 상한을 정함. Java CPU(~20%)가 먼저가 아님.

### 어떤 대안을 검토했는가?

- 풀 크기 / Index / TX에서 JWT 제거 (Phase 3: 풀만 적용)
- DB Only vs Caffeine vs Redis (Phase 4: Caffeine)
- DB `FOR UPDATE` vs JVM Lock vs Redis Lock (Phase 5: `FOR UPDATE`)

### 왜 현재 방식을 선택했는가?

- Caffeine: 앱이 하나라 Redis 공유가 필요 없고, hit rate가 높다. Session COUNT는 캐시하지 않음.
- `FOR UPDATE`: 다중 인스턴스에서도 DB가 락을 소유. Redis는 인프라만 늘림.
- `READ_COMMITTED`: RR 스냅샷 때문에 COUNT가 커밋된 INSERT를 못 보는 것을 막음.
- Redis는 어떤 Phase에서도 “지금 이 문제를 푸는 데 필요”가 되지 않았다.

### 실제로 얼마나 개선됐는가?

같은 100 VU k6 기준으로 **처리량은 개선되지 않았다** (322 → 296). p95는 소폭 좋아졌고(34.6 → 32.6), p50은 Baseline보다 나쁘다(14.6 → 21.4).

개선된 것은 숫자 RPS가 아니라:

- 풀 대기 감소 (pending 40 → 한도 40에서 acquire 단축)
- 조회 지연 (Phase 3 대비 Caffeine)
- **동시재생 한도가 테스트로 보장됨**

### 가장 효과가 컸던 변경은 무엇인가?

목적별로 다르다.

- 지연(Phase 3 대비): **Caffeine**. p50 21.6 → 15.8 ms.
- 정확성: **Subscription `FOR UPDATE`**. 한도 위반이 사라짐.
- 관측: Actuator / Prometheus / k6. 이후 판단을 숫자로 할 수 있게 함.

### 예상과 다르게 효과가 없었던 변경은 무엇인가?

- **Hikari 10 → 40**: 대기는 줄었지만 RPS는 거의 그대로, p50은 나빠졌다.
- **Cache로 RPS를 올리려던 기대**: sleep(0.1)과 COUNT/INSERT가 처리량을 붙잡음.
- **Lock 후 같은 k6가 더 빨라질 것이라는 기대**: 락은 한도용이다. 분산 부하에서는 p95가 약 6 ms 늘었다.

### 실제 서비스라면 추가로 무엇을 고려해야 하는가?

측정으로 보인 다음 후보다. 이 프로젝트에서는 적용하지 않았다.

- JWT 발급을 트랜잭션 밖으로 빼 커넥션 점유 시간을 줄인다.
- 인스턴스·풀 크기·Tomcat 스레드를 트래픽에 맞게 조정한다.
- `ENDED` 세션 적재와 COUNT 비용을 본다. 필요하면 정리 배치 또는 인덱스.
- 서버를 여러 대로 나누면 Caffeine이 갈라진다. 그때 Redis Cache/Lock을 다시 비교한다.
- 노트북 한 대의 500 RPS 천장을 프로덕션 용량으로 쓰지 않는다.

## Phase 6 한계 (참고)

같은 Baseline 스크립트가 아님.

| 테스트 | 결과 |
| --- | --- |
| Load 500 RPS 2분 | 492.5 /s, 실패 0%, p95 591 ms, 풀 포화 |
| Stress 500→5,000 | 완료량은 ~500 /s에서 정지. 1,000부터 drop/timeout |
| Spike 500→5,000→500 | 프로세스는 안 죽음. idle이면 pending 0. 500 유지만으로는 지연 미회복 |

상세: [005-load-test.md](005-load-test.md), [006-spike-test.md](006-spike-test.md).

## 결론

- Baseline과 Final을 같은 k6로 비교했다.
- RPS를 크게 올린 변경은 없었다. 한도와 병목 원인을 측정으로 남긴 것이 이 프로젝트의 결과다.
- Redis는 끝까지 넣지 않았다.
- 로드맵 Phase 7 완료.
