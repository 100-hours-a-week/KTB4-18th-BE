# 로그인 실패 잠금 정책 설계

## 목적

이메일 전송이나 비밀번호 재설정 수단이 없는 현재 단계에서는 두 번째 실패 후 영구 잠금을 사용하지 않는다. 대신 활성 계정의 비밀번호 불일치가 10회 누적될 때마다 일시 잠금을 적용하고, 반복될수록 대기 시간을 두 배로 늘린다.

## 정책

| 잠금 단계 | 같은 단계에 도달하는 시점 | 잠금 시간 |
| --- | --- | --- |
| 1 | 첫 10회 실패 | 5분 |
| 2 | 첫 잠금 해제 후 다시 10회 실패 | 10분 |
| 3 | 두 번째 잠금 해제 후 다시 10회 실패 | 20분 |
| 4 | 세 번째 잠금 해제 후 다시 10회 실패 | 40분 |

일반식은 `5분 × 2^(잠금 단계 - 1)`이다. 잠금 시간은 외부 응답에 표시하지 않으며, 잠긴 상태의 요청은 모두 401 `invalid credentials`를 반환한다.

## 정상 로그인 시 초기화

잠금이 해제된 뒤 사용자가 정상 로그인에 성공하면 해당 이메일의 메모리 상태를 제거한다.

- 현재 10회 단위 안의 실패 횟수는 0이 된다.
- 누적 잠금 단계도 0이 된다.
- 따라서 이후 다시 10회 실패하면 첫 번째 단계인 5분 잠금부터 시작한다.

잠긴 5분·10분·20분·40분 안에는 로그인을 허용하지 않으므로, 잠금이 해제된 후의 정상 로그인만 상태를 초기화할 수 있다.

## 구현 위치와 호출 흐름

```text
LoginService.login()
  ├─ LoginAttemptStore.isBlocked(email)
  ├─ BCrypt 불일치 → LoginAttemptStore.recordFailure(email)
  └─ 정상 로그인 → LoginAttemptStore.clearAfterSuccessfulLogin(email)
```

- 구현: [`LoginAttemptStore.java`](../src/main/java/com/muse/meomuneum/feature/auth/login/attempt/LoginAttemptStore.java)
- 호출: [`LoginService.java`](../src/main/java/com/muse/meomuneum/feature/auth/login/service/LoginService.java)
- 시간 제어 테스트: [`LoginAttemptStoreTest.java`](../src/test/java/com/muse/meomuneum/feature/auth/login/attempt/LoginAttemptStoreTest.java)

## 저장과 운영 제약

- 상태는 서버 프로세스 메모리의 `ConcurrentHashMap`에만 저장한다.
- DB, ERD, Flyway migration에는 잠금 관련 컬럼이나 테이블을 추가하지 않는다.
- 서버를 재시작하면 상태가 초기화된다.
- 서버가 여러 대면 각 서버 인스턴스가 별도 상태를 가진다.
- 미가입 또는 탈퇴 이메일은 상태를 만들지 않는다. 이 정책은 임의 이메일을 대량 전송해 메모리를 소모시키는 위험을 줄인다.

## 결정 필요

잠금 시간이 계속 두 배로 증가할 때의 최대 상한은 아직 정해지지 않았다. 현재 구현은 요구사항대로 단계별 두 배 증가를 적용한다. 운영 전에는 최대 잠금 시간과 다중 인스턴스에서의 공유 저장소 도입 여부를 결정해야 한다.
