# 실험 001 - Baseline 성능 측정

## 목적

최적화 없는 V1 Playback Gate가 어느 정도 트래픽을 처리하는지 기준값을 확보한다.
이 단계에서는 성능을 개선하지 않는다.

## 현재 문제

아직 병목을 단정하지 않는다. 측정이 목적이다.

## 가설

없음. Baseline은 가설 검증이 아니라 이후 비교 기준이다.

## 테스트 환경

| 항목 | 값 |
| --- | --- |
| 일시 | 2026-08-14 |
| OS | macOS (Darwin arm64) |
| App | Spring Boot 3.4.5, Java 21, 단일 프로세스 |
| DB | MySQL 8.0 (Docker), Hikari 기본 최대 연결 10 |
| 관측 | Actuator + Micrometer Prometheus, Prometheus, Grafana |
| 부하 | k6 0.55.0 (Docker), `load-test/k6/playback-baseline.js` |
| 프로필 | `local,load-test` |

Redis, Cache, Lock, 성능용 Index는 사용하지 않았다.

## 데이터 규모

- 데모 회원 8명 + 부하 테스트용 PREMIUM 회원 2000명 (`memberId` 9..2008)
- 재생 가능 콘텐츠 1건 (`contentId` 1)
- 시나리오: `POST /sessions` 성공 후 바로 `DELETE` (세션 누적·동시재생 한도로 Error Rate가 오염되지 않게)

## 테스트 방법

k6 ramping VUs, 한 번의 연속 실행:

```text
30s → 10 VU
1m  → 10 VU   (적은 부하)
30s → 50 VU
1m  → 50 VU   (중간 부하)
30s → 100 VU
1m  → 100 VU  (높은 부하)
30s → 0
```

반복 실행:

```bash
docker compose -f docker/docker-compose.yml up -d
./gradlew bootRun --args='--spring.profiles.active=local,load-test'
./load-test/k6/run-baseline.sh
```

## 변경 사항

측정만 했다. 애플리케이션 비즈니스 로직은 바꾸지 않았다.
관측을 위해 Actuator / Prometheus / Grafana / k6만 추가했다.

## 결과

k6 전체 구간 (약 5분, 최대 100 VU):

| 지표 | 값 |
| --- | ---: |
| 완료 iteration | 99,562 |
| Playback 시작 성공 | 99,535 |
| Playback 시작 실패 | 27 (0.03%) |
| http_reqs | 199,097 (start + end) |
| Iteration RPS (start 처리량에 해당) | 321.5 |
| HTTP RPS | 642.9 |
| start p50 (med) | 14.61 ms |
| start p95 | 34.62 ms |
| start max | 154.03 ms |
| http_req_duration p50 (med) | 13.21 ms |
| http_req_duration p95 | 33.60 ms |
| Error Rate (`http_req_failed`) | 0.014% |

p99는 이번 k6 기본 summary에 포함되지 않아 기록하지 못했다. 동일 스크립트에 `p(99)`를 넣어 다음 반복 측정부터 남긴다.

서버/DB (Prometheus, 테스트 구간 max_over_time 10m):

| 지표 | 값 |
| --- | ---: |
| Hikari active max | 10 (풀 한도) |
| Hikari pending max | 40 |
| process CPU max | 약 22% |
| JVM Heap (Eden max / Old max) | 약 88 MB / 50 MB |
| GC pause rate | 약 0.0013 s/s |

## Before / After

최초 Baseline이므로 After 없음. 이후 실험은 이 표와 같은 스크립트·데이터·VU 단계로 비교한다.

## 분석

- 기능 오류는 거의 없다. 실패 27건은 주로 ramp-down 중 중단된 요청으로 보인다.
- 응답은 평균 십수 ms 수준이다. 100 VU에서도 p95는 약 35 ms였다.
- Hikari 최대 연결 10이 가득 찼고 pending이 40까지 올라갔다. Connection Pool 대기가 latency 꼬리에 영향을 줬을 가능성이 있다.
- 지금은 원인을 고치지 않는다. Phase 3에서 Query/풀/트랜잭션이 실제 병목인지 확인한다.

## 관찰된 현상

- 단일 노트북 + 로컬 Docker MySQL 기준값이다. 하드웨어가 바뀌면 숫자를 다시 뽑아야 한다.
- Grafana `http://localhost:3000` (admin/admin), Prometheus `http://localhost:9090` 에서 동일 구간을 다시 볼 수 있다.

## 결론

Phase 2 완료 조건을 충족하는 Baseline을 확보했다.

- 반복 가능한 k6 시나리오가 있다.
- RPS / p50 / p95 / Error Rate를 기록했다.
- CPU, Heap, GC, DB Connection Pool을 확인할 수 있다.

다음 단계는 Phase 3(DB 병목 분석)이다. Index, 풀 크기 변경, Redis는 아직 적용하지 않는다.
