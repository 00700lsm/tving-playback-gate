# ADR 003 - 동시 재생 제한 제어

## 문제

`COUNT → 검증 → INSERT`는 트랜잭션을 써도 같은 회원의 동시 요청이 서로 COUNT=0을 볼 수 있다.

재현 (`ConcurrentPlaybackTest`, BASIC 한도 1, 40 스레드):

- Lock 없음: HTTP 200이 **40건**, ACTIVE Session **40건**
- 허용은 1건이다

## 대안

### A. DB Pessimistic Lock (`SELECT … FOR UPDATE` on `subscription`)

같은 회원의 요청을 InnoDB 행 락으로 직렬화한 뒤 COUNT + INSERT.

- 정확성: 다중 인스턴스에서도 DB가 락 소유자
- 인프라: MySQL만
- 단점: 같은 회원 경합 시 커넥션이 락을 붙들고 대기

`plan`은 엔티티가 아니라 enum 컬럼이라 조인으로 plan 테이블을 잠그지 않는다.

### B. JVM Lock (`ConcurrentHashMap<memberId, ReentrantLock>`)

프로세스 안에서 회원 단위로 직렬화. 구현은 짧다.

- 정확성: **이 JVM에서만** 보장
- 인스턴스를 나누면 다시 Race
- 락 대기는 DB 커넥션 밖에서 끝날 수 있어 풀에는 유리할 수 있다

### C. Redis Distributed Lock / Atomic

여러 인스턴스에서 회원 단위 락 또는 카운터.

- 지금은 앱이 하나라 공유 락이 필요 없다
- Redis와 MySQL Session을 맞추면 이중 쓰기 문제가 생긴다
- 프로세스·네트워크가 늘어난다

## 선택

**A. Subscription 행 Pessimistic Lock + `start()` 트랜잭션 isolation `READ_COMMITTED`.**

Member/Content는 Caffeine을 유지한다. Subscription은 캐시하지 않는다. 락을 걸려면 살아 있는 행을 읽어야 하고, 캐시 hit면 그 조회가 빠진다.

MySQL 기본 `REPEATABLE READ`만 쓰면, 트랜잭션 앞의 Member/Content SELECT가 스냅샷을 만든다. 그 다음 `FOR UPDATE`로 줄을 세워도 COUNT는 스냅샷의 0을 본다. `READ_COMMITTED`에서 COUNT는 커밋된 최신 행을 본다.

## 선택 이유

- Race가 난 단위가 회원(이용권)이라 그 행을 잠그는 것이 맞다
- Redis를 이 문제를 위해 새로 두지 않는다
- JVM Lock은 지금 앱이 하나여도, 인스턴스를 나누는 순간 한도가 다시 깨진다
- 부하 테스트는 회원 2000명이라 회원당 경합이 거의 없다. 락은 같은 계정 동시 요청을 막을 때 의미가 있다

## 장점

- 한도가 테스트로 보장된다
- 추가 인프라 없음
- 다른 회원끼리는 기다리지 않는다

## 단점

- 같은 회원이 동시에 두드리면 두 번째부터 락/한도 대기가 생긴다
- Subscription 조회는 캐시하지 못하므로 요청마다 MySQL 1회가 남는다
- 트랜잭션이 끝날 때까지 행 락과 커넥션을 붙든다
- `start()`만 `READ_COMMITTED`라 이 메서드의 COUNT는 커밋 직후 행을 본다. 나머지 기본 isolation은 MySQL 기본값이다
