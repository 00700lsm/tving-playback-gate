# 실험 003 - Cache 적용 가능성

## 목적

요청마다 반복되는 DB 조회에 Cache를 넣을 가치가 있는지 측정으로 판단한다.
Redis를 써보기 위해 Redis를 넣지 않는다.

## 현재 문제

Phase 3 ([002-db-analysis.md](002-db-analysis.md)): 풀을 40으로 올려도 Iteration RPS는 326으로 거의 그대로였고 start p50은 21.6 ms로 나빠졌다.
요청당 MySQL 조회 4회 + INSERT 1회가 남아 있다.

## 가설

Member / Content / Subscription은 읽기가 많고 쓰기가 적다. Local Cache로 이 3개를 빼면 p50/p95가 줄어든다.
PlaybackSession COUNT는 캐시하면 동시 재생 한도가 깨지므로 캐시하지 않는다.

## Cache 후보 분석

| 데이터 | 조회 빈도 | 수정 빈도 | 약간 오래된 값 허용 | 서버 간 공유 필요 | 캐시? |
| --- | --- | --- | --- | --- | --- |
| Content | 매 요청, 부하 테스트에서 ID=1 | 거의 없음 | 예 (TTL 60s) | 아니오 (앱 1개) | 예 |
| Member | 매 요청, 2000명 순환 | 거의 없음 | 예 | 아니오 | 예 |
| Subscription | 매 요청, 회원당 1건 | 거의 없음 | 예 | 아니오 | 예 |
| PlaybackSession COUNT/INSERT | 매 요청 | 매 요청 변경 | 아니오 | - | 아니오 |

## 대안

- DB Only: 추가 인프라 없음. 조회 4회 유지.
- Caffeine: 단일 프로세스 Cache Aside. TTL 60초.
- Redis: 인스턴스 간 공유용. 지금은 앱이 하나라 이점이 없다.

선택: **Caffeine**. 이유와 장단점은 [docs/adr/002-content-cache-strategy.md](../adr/002-content-cache-strategy.md).

## 변경 사항

- `CachedPlaybackReads` Cache Aside
- Caffeine `maximumSize=10000`, `expireAfterWrite=60s`, `recordStats`
- Session COUNT/INSERT는 MySQL만 사용

테스트 프로필은 `spring.cache.type=none`.

## 재측정

Phase 3과 동일: 회원 2000, contentId=1, k6 ramping 10→50→100 VU, start 후 DELETE, 세션 테이블 TRUNCATE 후 시작.

## 결과

| 지표 | Phase 3 (pool 40, no cache) | Phase 4 (pool 40 + Caffeine) |
| --- | ---: | ---: |
| Iteration RPS | 326.0 | 321.5 |
| start 성공 | 97,818 | 98,286 |
| start 실패 | 3 | 41 (0.04%) |
| start p50 | 21.63 ms | 15.82 ms |
| start p95 | 33.88 ms | 26.82 ms |
| start p99 | 44.07 ms | 36.10 ms |
| Hikari pending max | 17 | 17 |
| acquire 평균 | ~1.3 ms | ~0.68 ms |

Hit Rate (Actuator `cache.gets`):

| cache | hit | miss | hit rate |
| --- | ---: | ---: | ---: |
| contents | 98,221 | 65 | 99.93% |
| members | 88,989 | 9,297 | 90.55% |
| subscriptions | 88,976 | 9,310 | 90.53% |

Content는 키가 하나라 거의 전부 hit. Member/Subscription miss는 TTL 60초 × 5분 테스트에서 키가 만료되며 다시 채워진 양과 맞는다.

## Before / After

- p50 21.6 → 15.8 ms, p95 33.9 → 26.8 ms, p99 44.1 → 36.1 ms. 지연은 줄었다.
- RPS는 비슷하다 (326 → 322). k6 `sleep(0.1)`과 남은 COUNT+INSERT가 처리량을 붙잡고 있다.
- 풀 pending 17은 여전하다. Session 쓰기는 캐시 밖이다.

## 분석

조회 3개를 캐시할 가치는 있다. 지연이 줄었고 hit rate가 높다.
처리량이 안 오른 것은 Cache가 실패해서가 아니라, 요청마다 남는 COUNT/INSERT와 VU sleep 때문이다.

Redis는 이 측정에서 필요 없다. 서버가 하나이고 Caffeine hit rate가 이미 높다.

## 결론

- Cache 후보를 데이터별로 판단했다.
- Caffeine Local Cache를 적용했고 ADR에 이유를 남겼다.
- 동일 k6로 재측정했고 Hit Rate를 기록했다.
- DESIGN.md(현재 설계 문서 `docs/requirements.md`)를 Caffeine 구조로 맞춰 두었다.

다음: Phase 5 동시성 재현. Cache는 Session COUNT에 쓰지 않았으므로 Race Condition 실험과 별개다.
