# ADR 002 - Playback 조회 Cache 전략

## 문제

`POST /sessions`는 요청마다 Member, Content, Subscription을 MySQL에서 읽는다.
Phase 3에서 커넥션 풀을 40으로 올려도 RPS는 거의 그대로였고 p50은 나빠졌다.
병목이 풀 대기에서 DB 조회 횟수 쪽으로 이동했다.

## 대안

### DB Only

추가 인프라 없음. 요청당 조회 4회 + INSERT 1회가 유지된다.

### Caffeine (Local Cache)

단일 프로세스 안에서 Cache Aside. TTL 60초. 서버 간 공유 없음.

### Redis (Distributed Cache)

여러 인스턴스가 같은 캐시를 공유할 수 있다. 현재는 앱이 하나라 공유가 필요 없다.
운영 복잡도(프로세스, 네트워크)가 더 크다.

## 선택

Caffeine Local Cache.

캐시 대상:

- Content (부하 테스트에서 모든 요청이 같은 contentId)
- Member
- Subscription

캐시하지 않음:

- PlaybackSession COUNT / INSERT (동시 재생 제한의 정확성)

## 선택 이유

- 조회 빈도가 높고 수정은 거의 없다.
- 수십 초 지연된 회원/콘텐츠 상태도 이 실험의 부하 시나리오에서는 허용한다.
- 지금은 서버가 하나라 Redis가 필요 없다.
- Session 수는 캐시하면 한도가 깨질 수 있다.

## 장점

- DB round-trip 3회를 hit 시 생략한다.
- 프로세스 하나만으로 동작한다.

## 단점

- 인스턴스가 여러 개가 되면 캐시가 갈라진다.
- TTL 동안 BLOCKED/이용권 변경이 반영되지 않는다.
- JPA 엔티티를 캐시에 두므로 detach 상태를 감수한다.
