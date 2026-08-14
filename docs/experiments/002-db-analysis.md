# 실험 002 - DB 병목 분석

## 목적

Playback 시작 API에서 Database가 실제 병목인지 확인하고, 근거가 있는 경우에만 한 가지를 적용한 뒤 Phase 2와 같은 조건으로 재측정한다.

Redis, Cache, Lock, COUNT용 Composite Index는 이번 실험에서 적용하지 않았다.

## 현재 문제

Phase 2 Baseline ([001-baseline.md](001-baseline.md)):

- Hikari active max = 10 (기본 한도)
- Hikari pending max = 40
- start p50 = 14.61 ms, p95 = 34.62 ms
- Iteration RPS = 321.5
- CPU max ≈ 22% (CPU saturating은 아님)

## 가설

커넥션 풀 한도 10 때문에 스레드가 커넥션을 기다린다. 풀만 키우면 pending과 acquire time이 줄고, RPS/p95가 좋아질 수 있다.

COUNT Full Scan은 이번 데이터에서 주원인이 아닐 가능성이 크다. (아래 EXPLAIN)

## 분석

### 요청당 SQL (`POST /sessions`, 코드 기준, 한 `@Transactional`)

1. `SELECT member WHERE id=?` (PK)
2. `SELECT content WHERE id=?` (PK)
3. `SELECT subscription WHERE member_id=? ORDER BY id DESC LIMIT 1` (FK)
4. `SELECT COUNT(*) FROM playback_session WHERE member_id=? AND status=? AND expires_at>=?` (FK `member_id`)
5. `INSERT playback_session`

JWT 발급도 같은 트랜잭션 안에 있어 커넥션을 그 시간 동안 붙든다.

`DELETE /sessions/{id}`는 `session_id` Unique 조회 후 UPDATE다. 행은 지우지 않고 `ENDED`로 남긴다.

### EXPLAIN (측정 시점, `playback_session` 약 9.9만 행)

| Query | type | key | rows | Extra |
| --- | --- | --- | ---: | --- |
| member by id | const | PRIMARY | 1 | |
| content by id | const | PRIMARY | 1 | |
| subscription by member_id | ref | FK member_id | 1 | Backward index scan |
| COUNT session | ref | FK member_id | ~50 | Using where |

COUNT는 테이블 Full Scan(`ALL`)이 아니다. `member_id` FK로 자른 뒤 status/expires_at을 필터한다. 로드맵 기준으로 Composite Index는 이번엔 적용하지 않는다.

### Hikari (Phase 2 직후 누적)

- acquire COUNT ≈ 199,081, TOTAL_TIME ≈ 1393 s → 평균 acquire **약 7.0 ms**
- 요청 p50 14.6 ms 대비 풀 대기 비중이 크다.

### 판단

**DB 커넥션 풀 대기가 병목이다.** Query 플랜 자체보다 한도 10에서 대기열이 쌓인다.

적용할 후보 하나: Hikari `maximum-pool-size` 10 → **40**. Index/TX 축소/Redis는 넣지 않는다.

## 변경 사항

```yaml
spring.datasource.hikari.maximum-pool-size: 40
```

[src/main/resources/application.yml](../../src/main/resources/application.yml)

재측정 전 `playback_session`을 TRUNCATE 해서 Phase 2와 같이 빈 세션 테이블에서 시작했다. 회원 2000명·콘텐츠 1건·k6 stages는 동일하다.

## 재측정

동일 스크립트 `load-test/k6/playback-baseline.js` (p99 포함).

## 결과

| 지표 | Baseline (pool 10) | After (pool 40) |
| --- | ---: | ---: |
| Iteration RPS | 321.5 | 326.0 |
| start 성공 | 99,535 | 97,818 |
| start 실패 | 27 (0.03%) | 3 (0.003%) |
| start p50 (med) | 14.61 ms | 21.63 ms |
| start p95 | 34.62 ms | 33.88 ms |
| start p99 | (미기록) | 44.07 ms |
| start max | 154.03 ms | 137.66 ms |
| Hikari max | 10 | 40 |
| Hikari active max | 10 | 40 |
| Hikari pending max | 40 | 17 |
| acquire 평균 | ~7.0 ms | ~1.3 ms |
| CPU max | ~22% | ~21% |

## Before / After

- 풀 대기: pending 40 → 17, acquire 7.0 ms → 1.3 ms. 가설의 “대기열” 부분은 맞다.
- 처리량: RPS +1.4%. 의미 있는 향상으로 보기 어렵다.
- p50은 오히려 나빠졌다 (14.6 → 21.6 ms). 동시 커넥션이 늘면서 MySQL 쪽 쿼리 시간이 늘어난 것으로 본다.
- p95는 소폭 개선, max는 줄었다.
- 풀 40에서도 active가 한도에 닿았다. 대기가 앱 큐에서 DB 실행으로 이동했다.

## 분석 (재측정 후)

커넥션 풀 10은 실제로 포화였다. 다만 풀을 키우는 것만으로는 RPS/p50이 좋아지지 않았다.

다음에 볼 곳 (Phase 4): 요청당 조회 5회를 Cache로 줄일 가치가 있는지. 이번엔 Redis를 넣지 않았다.

TX에서 JWT를 빼는 것은 적용하지 않았다. 풀 변경과 효과를 섞지 않기 위해서다.

## 결론

- Playback API 주요 Query와 EXPLAIN을 확인했다.
- DB **커넥션 풀 대기**가 Baseline 병목이었다. COUNT Full Scan은 아니었다.
- 근거 있는 변경 하나(Hikari 40)만 적용하고 같은 k6로 재측정했다.
- 대기열은 줄었지만 RPS/p50 개선은 거의 없다. 현재 구조(풀 40)를 유지하고 Phase 4에서 Cache 후보를 측정으로 판단한다.

ADR은 작성하지 않는다. 풀 크기 조정은 시스템 구조를 바꾸는 결정이 아니다.
