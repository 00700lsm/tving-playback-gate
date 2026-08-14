# Playback Gate

OTT 재생 가능 여부를 판단하고 Playback Session / Playback Token을 발급하는 API입니다.
영상 재생 화면, DRM, CDN은 없습니다. 기능 확인은 **JUnit**과 **HTTP API**로 합니다.

```text
POST /api/v1/playback/sessions   재생 시작 (세션 + Playback Token)
DELETE /api/v1/playback/sessions/{sessionId}   재생 종료
```

---

## 사전 준비

- Docker (MySQL, 통합 테스트, Grafana)
- Java 21

맥에서 `Unable to locate a Java Runtime`이 나면 프로젝트에 포함된 JDK를 씁니다.

```bash
export JAVA_HOME="$PWD/.jdk/jdk-21.0.12+8/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"
java -version
```

`java -version`에 21이 나와야 `./gradlew`가 동작합니다.

---

## 1. 핵심 기능 테스트 (JUnit)

로그인 UI 없이 재생 정책을 검증합니다. 앱을 따로 띄울 필요는 없고, Docker만 있으면 됩니다.

```bash
./gradlew test
```

브라우저에서 리포트: `build/reports/tests/test/index.html`

| 테스트 | 확인하는 것 |
| --- | --- |
| `PlaybackServiceTest` | 세션·토큰 발급, BLOCKED/탈퇴, 이용권 없음·정지·만료, 요금제, 연령, 공개기간, 동시재생 한도, 세션 종료 |
| `PlaybackIntegrationTest` | HTTP 401, 정상 발급, 종료 후 재시작, 한도 초과, 연령 제한 |
| `ConcurrentPlaybackTest` | BASIC 회원 40 동시 요청 → ACTIVE Session이 1인지 |

성공이면 `BUILD SUCCESSFUL`입니다.

---

## 2. 손으로 API 치기

### 서버 켜기

```bash
docker compose -f docker/docker-compose.yml up -d
./gradlew bootRun --args='--spring.profiles.active=local,load-test'
```

`local` 프로필이 시드 회원/콘텐츠를 넣고, 부팅 로그에 Auth JWT를 출력합니다.

```text
memberId=1 email=premium@example.com status=ACTIVE token=eyJ...
```

`Authorization: Bearer ` 뒤에 이 `token` 전체를 붙입니다. 브라우저 주소창만 열면 헤더가 없어서 `UNAUTHORIZED` / `인증이 필요합니다.` 가 납니다.

### 재생 시작 · 종료

```bash
# 로그의 premium 토큰
TOKEN='eyJ...'

curl -s -X POST http://localhost:8080/api/v1/playback/sessions \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"contentId":1,"deviceId":"iphone-001"}'
```

정상은 HTTP **200**과 `sessionId`, `playbackToken`, `expiresAt`입니다.

```bash
curl -s -o /dev/null -w "%{http_code}\n" \
  -X DELETE "http://localhost:8080/api/v1/playback/sessions/<sessionId>" \
  -H "Authorization: Bearer $TOKEN"
```

정상 종료는 **204**입니다.

### 시드와 기대 결과

회원 (로그의 `memberId` / email):

| email | 용도 |
| --- | --- |
| `premium@example.com` | 정상 재생 |
| `basic@example.com` | BASIC, 동시재생 1 |
| `blocked@example.com` | 재생 거절 |
| `young@example.com` | 연령 제한 |

콘텐츠 `contentId`:

| id | 제목 | 비고 |
| --- | --- | --- |
| 1 | Basic Movie | 전 이용권 |
| 2 | Standard Show | STANDARD 이상, 15세 |
| 3 | Premium Adult | PREMIUM, 18세 |
| 4 | Closed Film | 비공개 |
| 5 | Upcoming Title | 공개 전 |
| 6 | Ended Title | 공개 종료 |

| 토큰 | body | 기대 |
| --- | --- | --- |
| premium | `contentId` 1 | 200 |
| 헤더 없음 | 1 | 401 `UNAUTHORIZED` |
| blocked | 1 | 회원 재생 불가 |
| basic | 2 | 요금제 불가 |
| young | 3 | 연령 제한 |
| basic | 1을 **종료 없이** 두 번 | 두 번째 동시재생 초과 |

BASIC은 한도가 1입니다. 첫 세션을 DELETE한 뒤에는 다시 시작됩니다.

서버 상태만 브라우저로 보려면 http://localhost:8080/actuator/health (`"status":"UP"`).

---

## 3. Grafana (부하 중 그래프)

기능 테스트가 아니라 메트릭 화면입니다.

1. `docker compose`와 `bootRun`이 떠 있어야 합니다.
2. http://localhost:3000 → 로그인 **admin / admin**
3. Dashboards → **Playback Gate Baseline**

패널: Start RPS, Latency, Hikari Pool, JVM Heap.

트래픽이 없으면 그래프가 평평합니다. 아래 k6를 돌린 뒤에 캡처하면 됩니다.

---

## 4. 부하 테스트 (k6)

앱이 `local,load-test`로 떠 있는 상태에서:

```bash
bash load-test/k6/run-baseline.sh
bash load-test/k6/run-phase6.sh playback-load.js
bash load-test/k6/run-phase6.sh playback-stress.js
bash load-test/k6/run-phase6.sh playback-spike.js
```

측정 기록은 [docs/experiments](docs/experiments)입니다.

---

## 문서

| 문서 | 역할 |
| --- | --- |
| [docs/design.md](docs/design.md) | 요구사항 |
| [docs/requirements.md](docs/requirements.md) | 현재 설계 |
| [docs/roadmap.md](docs/roadmap.md) | Phase 순서 |
| [docs/experiments/007-final-comparison.md](docs/experiments/007-final-comparison.md) | Baseline vs 최종 |
