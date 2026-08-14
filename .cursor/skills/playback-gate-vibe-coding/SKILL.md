---
name: playback-gate-vibe-coding
description: >-
  Playback Gate 바이브코딩의 기본 규칙과 진행 순서. 기능 구현, 리팩터링, 최적화,
  "만들어줘", "추가해줘", "개선해줘" 요청에 사용한다. Redis/Cache/Lock을
  먼저 넣지 않고, 측정 없이 성능을 추측으로 고치지 않는다.
---

# Playback Gate 바이브코딩

코드를 작성하기 전에 이 스킬을 따른다. 상세 규칙은 작업 종류에 맞는 스킬을 추가로 읽는다.

| 작업 | 읽을 스킬 |
| --- | --- |
| V1 기능/API/패키지 구현 | [playback-gate-implement](../playback-gate-implement/SKILL.md) |
| 재생 정책·에러 코드·검증 순서 | [playback-gate-domain](../playback-gate-domain/SKILL.md) |
| Unit/Integration Test | [playback-gate-test](../playback-gate-test/SKILL.md) |
| 부하 테스트·성능 개선 | [playback-gate-experiment](../playback-gate-experiment/SKILL.md) |
| 구조 변경·최적화 도입 | [playback-gate-design-change](../playback-gate-design-change/SKILL.md) |

기준 문서: `rules/requirements.md`(설계), `rules/design.md`(요구사항).
스킬과 문서가 충돌하면 **문서가 우선**이다.

## 이 프로젝트의 목적

완성된 OTT를 만드는 것이 아니다.

- 재생 가능 여부를 판단한다
- Playback Session과 Playback Token을 발급한다
- 이후 부하·동시성 문제를 **측정으로** 경험한다

영상을 전송하지 않는다. CDN, DRM, 스트리밍은 범위 밖이다.

## 하드 제약 (위반 금지)

다음을 **요청에 없어도** 넣지 않는다. "성능상 필요할 것 같아서"는 이유가 되지 않는다.

**기능 제외**

- 회원가입 UI, 로그인 UI, 관리자 페이지
- 실제 결제, 실제 영상 스트리밍/업로드/인코딩
- DRM, CDN, 추천, 검색, 댓글, 좋아요, 시청 기록 분석

**초기(Baseline) 기술 제외**

- Redis, Caffeine, Kafka
- Distributed Lock, Redis Lock
- 비동기 이벤트, 별도 Microservice, Kubernetes
- Query/Local Cache, 선제 DB Lock, Batch 처리
- 성능용 Composite Index (PK/FK/명백한 Unique만)
- Session EXPIRED 일괄 갱신 Scheduler
- 불필요한 인터페이스·추상화 계층

데이터는 전부 MySQL에서 직접 조회한다. 앱은 Spring Boot 하나다.

## 진행 순서

단계를 건너뛰지 않는다. 최적화는 Baseline 완료 + 측정 이후에만 한다.

```text
1. Baseline 구현
2. Unit / Integration Test
3. 기능 완료 조건 확인
4. k6 Baseline 측정
5. 병목 분석
6. 설계 변경 정리 + ADR
7. 구현
8. 동일 조건 재측정
```

## 구현할 때

1. 요청이 제외 기능/선제 최적화인지 확인한다. 맞으면 구현하지 않고 이유를 말한다.
2. 해당 기능 스킬과 도메인 규칙을 읽는다.
3. 설계된 API, 검증 순서, 에러 코드, 패키지 구조를 그대로 따른다.
4. 필요한 파일만 추가한다. 미래 확장용 코드는 작성하지 않는다.
5. 테스트 스킬의 해당 케이스를 함께 작성한다.

## 성능·동시성을 만질 때

1. 추측으로 Redis, Lock, Cache를 넣지 않는다.
2. Race Condition은 코드 작성 단계에서 "예방"하지 않는다. 테스트로 재현한 뒤 후보를 비교한다.
3. 구조 변경 전에 [playback-gate-design-change](../playback-gate-design-change/SKILL.md)를 따른다.

## 답변 방식

- 왜 이 구조인지, 왜 넣지 않았는지를 짧게 설명한다.
- "나중에 필요할 수 있어서" 추가한 코드가 있으면 되돌려 제거한다.
- 한국어로 설명한다.
