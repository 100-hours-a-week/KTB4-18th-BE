# 이메일·비밀번호 로그인 API 테스트 명세서

> ⚠️ 이 문서의 이전 테스트 클래스 경로는 `origin/dev` 통합 전 기준입니다. 현재 테스트 결과와 경로는 [origin/dev 통합 및 로그인 API 검증 보고서](auth-login-origin-dev-integration-test-report.md)를 기준으로 확인합니다.

## 1. 테스트 대상

- API: `POST /api/v1/auth/login`
- 범위: 이메일·비밀번호 로그인 요청, 응답, Cookie, 입력 검증, 인증 실패 은닉, 탈퇴 사용자 차단, 로그인 실패 잠금, 성공 후 잠금 이력 초기화, 공통 오류 응답
- 제외: 회원가입, 토큰 재발급, 로그아웃, 비밀번호 변경·재설정, 다른 API

## 2. 사전 조건

### 테스트 사용자

| 구분 | 이메일 | 비밀번호 상태 | `deleted_at` |
|---|---|---|---|
| 활성 사용자 | `login-test@example.com` | 일치하는 BCrypt 해시 | `NULL` |
| 탈퇴 사용자 | `withdrawn-test@example.com` | 일치하는 BCrypt 해시 | 값 존재 |
| 미존재 사용자 | `not-found@example.com` | DB 레코드 없음 | 해당 없음 |

테스트 비밀번호와 access/refresh token 원문은 테스트 결과에 기록하지 않는다.

### 실행 환경

- 단위·WebMvc 테스트: 외부 DB 없이 실행한다.
- 런타임 테스트: `meomuneum_auth_login_test` 같은 전용 임시 DB만 사용한다.
- 런타임 테스트가 끝나면 서버를 종료하고 임시 DB와 전용 DB 사용자를 삭제한다.
- `application-dev.yaml`과 기존 개발 DB 설정은 수정하지 않는다.

## 3. 공통 판정 규칙

- 성공: HTTP 상태, JSON Body, Header가 기대값과 모두 일치한다.
- 실패: 어떤 기대값 하나라도 다르거나 토큰이 실패 응답에 포함되면 실패다.
- 민감 정보: token 값은 `[REDACTED]`로 마스킹하고, Cookie는 이름·속성만 기록한다.

## 4. 테스트 케이스

### 4.1 성공 응답과 Cookie

| ID | 테스트 절차 | 기대 결과 | 자동화 수준 |
|---|---|---|---|
| LOGIN-01 | 활성 사용자에 유효한 이메일·비밀번호로 POST | `200`, `message=login success` | WebMvc + 런타임 |
| LOGIN-02 | LOGIN-01의 JSON Body 검사 | `data`에는 `access_token`, `expires_in`만 존재 | WebMvc |
| LOGIN-03 | LOGIN-01의 `Set-Cookie` 검사 | `refresh_token`, `HttpOnly`, `Secure`, `SameSite=Lax`, `Path=/api/v1/auth` 포함 | WebMvc |
| LOGIN-04 | LOGIN-01의 JSON Body 문자열 검사 | `refresh_token` 및 refresh token 원문 없음 | WebMvc |
| LOGIN-05 | Authorization 헤더 없이 LOGIN-01 실행 | Security 기본 인증 차단 없이 컨트롤러 응답 반환 | Security 포함 WebMvc |

### 4.2 요청값 검증

| ID | 요청 Body | 기대 결과 |
|---|---|---|
| LOGIN-VALID-01 | `{}` | `400`, `message=invalid request`, `data=null` |
| LOGIN-VALID-02 | `{"email":null,"password":"valid-password"}` | `400 invalid request` |
| LOGIN-VALID-03 | `{"email":"","password":"valid-password"}` | `400 invalid request` |
| LOGIN-VALID-04 | `{"email":"not-an-email","password":"valid-password"}` | `400 invalid request` |
| LOGIN-VALID-05 | `{"email":"login-test@example.com","password":""}` | `400 invalid request` |
| LOGIN-VALID-06 | JSON 문법 오류 | `400 invalid request` |

`LOGIN-VALID-01`부터 `LOGIN-VALID-06`은 parameterized WebMvc 테스트로 작성한다.

### 4.3 인증 실패와 계정 은닉

| ID | 테스트 절차 | 기대 결과 |
|---|---|---|
| LOGIN-AUTH-01 | 미존재 이메일로 요청 | `401`, `invalid credentials` |
| LOGIN-AUTH-02 | 탈퇴 사용자로 요청 | `401`, `invalid credentials` |
| LOGIN-AUTH-03 | 활성 사용자에 잘못된 비밀번호로 요청 | `401`, `invalid credentials` |
| LOGIN-AUTH-04 | LOGIN-AUTH-01과 LOGIN-AUTH-02의 응답 비교 | HTTP 상태·JSON Body 완전 동일 |
| LOGIN-AUTH-05 | LOGIN-AUTH-01~03 응답 검사 | access/refresh token 및 `Set-Cookie` 없음 |

### 4.4 실패 잠금과 성공 후 초기화

시간 대기는 금지한다. `MutableClock`을 사용해 테스트 시각을 전진시킨다.

| ID | 테스트 절차 | 기대 결과 |
|---|---|---|
| LOGIN-LOCK-01 | 활성 사용자 비밀번호를 10회 틀림 | 첫 잠금 5분 |
| LOGIN-LOCK-02 | LOGIN-LOCK-01 직후 올바른 비밀번호 요청 | `401 invalid credentials`, 토큰·Cookie 없음 |
| LOGIN-LOCK-03 | 첫 잠금 만료 후 다시 10회 틀림 | 두 번째 잠금 10분 |
| LOGIN-LOCK-04 | 첫 잠금 만료 후 올바르게 로그인 | `200 login success`, 실패 횟수·잠금 단계 삭제 |
| LOGIN-LOCK-05 | LOGIN-LOCK-04 후 다시 10회 틀림 | 첫 잠금과 동일한 5분 |
| LOGIN-LOCK-06 | 잠금 만료 후 10회 실패를 반복 | 잠금 시간 5분 → 10분 → 20분 → 40분 |

`LOGIN-LOCK-04`와 `LOGIN-LOCK-05`는 반드시 `LoginService`를 통해 실행한다. `LoginAttemptStore`만 직접 호출하면 실제 로그인 성공 시 초기화 호출이 누락돼도 테스트가 통과할 수 있다.

### 4.5 예외 응답

| ID | 준비 | 기대 결과 |
|---|---|---|
| LOGIN-ERR-01 | `LoginService` Mock이 `RuntimeException` 발생 | `500`, 공통 JSON 응답 형식 |
| LOGIN-ERR-02 | `LoginService` Mock이 `InvalidCredentialsException` 발생 | `401 invalid credentials` |
| LOGIN-ERR-03 | LOGIN-ERR-01 Body 검사 | 내부 예외 메시지·스택 트레이스·token 없음 |

## 5. 테스트 구현 위치

| 구분 | 파일 | 책임 |
|---|---|---|
| HTTP 계약 | `src/test/java/com/muse/meomuneum/feature/auth/login/controller/LoginControllerWebMvcTest.java` | JSON, Cookie, 400·401·500 |
| Security 접근 | `src/test/java/com/muse/meomuneum/feature/auth/login/config/LoginSecurityConfigurationTest.java` | 비인증 POST 허용 |
| 서비스 정책 | `src/test/java/com/muse/meomuneum/feature/auth/login/service/LoginServiceTest.java` | 성공 후 잠금 이력 초기화 |
| 잠금 시간 | `src/test/java/com/muse/meomuneum/feature/auth/login/attempt/LoginAttemptStoreTest.java` | 5→10→20→40분 계산 |

## 6. 실행 절차

### 자동 테스트

```bash
./gradlew test --tests 'com.muse.meomuneum.feature.auth.login.*' --no-daemon
./gradlew bootJar --no-daemon
```

1. 테스트 종료 코드가 `0`인지 확인한다.
2. Gradle 결과가 `BUILD SUCCESSFUL`인지 확인한다.
3. 실패한 테스트가 있으면 해당 케이스 ID, 기대값, 실제값을 테스트 리포트에 기록한다.

### 런타임 스모크 테스트

1. 테스트 전용 DB로 서버를 기동한다.
2. LOGIN-01, LOGIN-VALID-01, LOGIN-AUTH-01~03, LOGIN-LOCK-01~02를 HTTP 클라이언트로 실행한다.
3. Body는 token 값을 마스킹한 상태로, 상태 코드·메시지·키 구조·Cookie 속성만 기록한다.
4. 서버를 종료하고 임시 DB·DB 사용자를 삭제한다.

## 7. 테스트 결과 기록 양식

| 케이스 ID | 실행 일시 | 결과 | 실제 HTTP 상태 | 실제 메시지 | 비고 |
|---|---|---|---|---|---|
| LOGIN-01 |  | PASS / FAIL |  |  | token은 마스킹 |
| LOGIN-VALID-01 |  | PASS / FAIL |  |  |  |
| LOGIN-AUTH-01 |  | PASS / FAIL |  |  |  |
| LOGIN-AUTH-02 |  | PASS / FAIL |  |  |  |
| LOGIN-LOCK-05 |  | PASS / FAIL |  |  |  |
| LOGIN-ERR-01 |  | PASS / FAIL |  |  |  |

## 8. 최종 통과 조건

- 4장의 모든 케이스가 PASS다.
- 자동 테스트와 `bootJar`가 모두 성공한다.
- 런타임 스모크 테스트 후 테스트 서버·임시 DB·전용 DB 사용자가 남아 있지 않다.
- 실패 응답과 문서 어디에도 access token, refresh token, DB 비밀번호가 원문으로 남지 않는다.
