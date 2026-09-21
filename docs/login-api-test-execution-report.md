# 이메일·비밀번호 로그인 API 테스트 실행 리포트

> ⚠️ 이 문서는 `origin/dev` 통합 전 실행 이력입니다. 최신 실행 결과는 [origin/dev 통합 및 로그인 API 검증 보고서](auth-login-origin-dev-integration-test-report.md)를 기준으로 확인합니다.

## 1. 범위

- API: `POST /api/v1/auth/login`
- 제외: 로그인 외 API
- 실행 일시: 2026-09-20
- 실행 대상: `feature/auth-login`의 Spring Boot 애플리케이션

## 2. 민감 정보 처리 원칙

원문 보존 요청에 따라 명령 결과와 HTTP 응답을 기록했다. 단, access token, refresh token, JWT Secret, DB 비밀번호, Spring Security 자동 생성 비밀번호는 재사용 가능한 민감 정보이므로 원문 대신 `[REDACTED]`로 마스킹했다.

---

## 3. 자동 테스트

### 3.1 최초 실행 결과 원문

```text
> Task :compileTestJava FAILED
LoginServiceTest.java:97: error: cannot find symbol
    assertTrue(loginAttemptStore.isBlocked("member@example.com"));
    ^
  symbol:   method assertTrue(boolean)

LoginServiceTest.java:108: error: cannot find symbol
    assertFalse(loginAttemptStore.isBlocked("member@example.com"));
    ^
  symbol:   method assertFalse(boolean)

BUILD FAILED in 4s
```

### 3.1 해석

새로 추가한 서비스 테스트에 JUnit 정적 import 세 개(`assertTrue`, `assertFalse`)가 빠져 컴파일에 실패했다. 운영 로그인 코드가 아니라 테스트 코드의 import 누락이므로 import를 추가한 뒤 재실행했다.

### 3.2 재실행 결과 원문

명령:

```bash
./gradlew test --tests 'com.muse.meomuneum.feature.auth.login.*' --no-daemon
```

출력:

```text
> Task :compileJava UP-TO-DATE
> Task :processResources UP-TO-DATE
> Task :classes UP-TO-DATE
> Task :compileTestJava
> Task :processTestResources NO-SOURCE
> Task :testClasses
> Task :test

BUILD SUCCESSFUL in 6s
4 actionable tasks: 2 executed, 2 up-to-date
```

JUnit XML 원문 요약:

```xml
<testsuite name="com.muse.meomuneum.feature.auth.login.attempt.LoginAttemptStoreTest" tests="2" skipped="0" failures="0" errors="0">
  <testcase name="doublesLockoutDurationForEachTenFailures()"/>
  <testcase name="resetsTheFailureAndLockoutCountAfterSuccessfulLogin()"/>
</testsuite>
<testsuite name="com.muse.meomuneum.feature.auth.login.controller.LoginControllerTest" tests="1" skipped="0" failures="0" errors="0">
  <testcase name="returnsOnlyAccessTokenFieldsInBodyAndRefreshTokenInCookie()"/>
</testsuite>
<testsuite name="com.muse.meomuneum.feature.auth.login.controller.LoginControllerWebMvcTest" tests="4" skipped="0" failures="0" errors="0">
  <testcase name="returnsCommonInternalServerErrorForUnexpectedFailure()"/>
  <testcase name="returnsInvalidRequestForMalformedRequestValues()"/>
  <testcase name="returnsCredentialFailureWithoutCookies()"/>
  <testcase name="returnsTheLoginResponseContractAndRefreshTokenCookie()"/>
</testsuite>
<testsuite name="com.muse.meomuneum.feature.auth.login.service.LoginServiceTest" tests="3" skipped="0" failures="0" errors="0">
  <testcase name="issuesTokensForActiveUserWithMatchingPassword()"/>
  <testcase name="returnsSameCredentialFailureForMissingAndDeletedUsers()"/>
  <testcase name="resetsTheFirstLockoutHistoryAfterSuccessfulLogin()"/>
</testsuite>
```

### 3.2 해석

로그인 전용 자동 테스트 10건이 모두 통과했다.

- MockMvc 테스트가 실제 HTTP 형식의 `200`, `400`, `401`, `500`, Body 구조, refresh token Cookie 속성을 검증했다.
- `resetsTheFirstLockoutHistoryAfterSuccessfulLogin`은 10회 실패 후 첫 잠금 만료, 실제 성공 로그인, 다시 10회 실패, 5분 경과 후 잠금 해제를 검증한다. 마지막 5분 검증으로 성공 후 잠금 단계가 10분으로 이어지지 않고 초기화됨을 확인한다.
- 잠금 저장소 테스트는 5분 → 10분 → 20분 → 40분의 증가와 초기화 자체를 검증한다.

---

## 4. 패키징

### 4.1 원문

명령:

```bash
./gradlew bootJar --no-daemon
```

출력:

```text
> Task :compileJava UP-TO-DATE
> Task :processResources UP-TO-DATE
> Task :classes UP-TO-DATE
> Task :resolveMainClassName UP-TO-DATE
> Task :bootJar UP-TO-DATE

BUILD SUCCESSFUL in 3s
4 actionable tasks: 4 up-to-date
```

### 4.2 해석

현재 로그인 구현과 테스트를 포함한 배포용 JAR 패키징이 성공했다.

---

## 5. 격리 DB 런타임 테스트

### 5.1 환경 준비 원문

```text
Database: jdbc:mysql://localhost:3306/meomuneum_auth_login_test (MySQL 9.7)
Tomcat started on port 8080 (http) with context path '/'
Started MeomuneumBackendApplication in 2.735 seconds

email                         CHAR_LENGTH(password_hash)  active
login-test@example.com        60                          1
withdrawn-test@example.com    60                          0
```

### 5.1 해석

개발 DB 대신 테스트 전용 `meomuneum_auth_login_test`에만 연결했다. 활성 사용자와 `deleted_at`이 있는 탈퇴 사용자를 BCrypt 해시 길이 60으로 준비했다.

### 5.2 성공 응답과 Cookie 원문

```text
{"message":"login success","data":{"access_token":"[REDACTED]","expires_in":3600}}
HTTP 200
refresh_token_cookie=1 HttpOnly=1 Secure=1 SameSite=Lax=1 Path=/api/v1/auth=1
```

### 5.2 해석

Authorization 헤더 없이 유효 로그인 요청이 `200`으로 컨트롤러까지 도달했다. Body에는 `access_token`, `expires_in`만 있고 refresh token은 `Set-Cookie`로만 발급됐다. Cookie의 필수 보안 속성도 모두 확인됐다.

### 5.3 입력 검증 원문

```text
missing: {"message":"invalid request","data":null} HTTP=400
invalid_email: {"message":"invalid request","data":null} HTTP=400
malformed_json: {"message":"invalid request","data":null} HTTP=400
```

### 5.3 해석

누락 요청, 이메일 형식 오류, JSON 문법 오류가 모두 동일한 공통 형식의 `400 invalid request`를 반환했다.

### 5.4 자격 증명 은닉 원문

```text
unknown: {"message":"invalid credentials","data":null} HTTP=401
withdrawn: {"message":"invalid credentials","data":null} HTTP=401
wrong_password: {"message":"invalid credentials","data":null} HTTP=401
```

### 5.4 해석

미존재 이메일, 탈퇴 사용자, 비밀번호 불일치가 모두 같은 상태 코드와 Body를 반환했다. 따라서 계정 존재 여부나 탈퇴 상태가 외부에 노출되지 않는다.

### 5.5 첫 잠금 원문

```text
401 401 401 401 401 401 401 401 401
blocked_correct_password: {"message":"invalid credentials","data":null} HTTP=401
```

### 5.5 해석

앞선 `wrong_password` 요청 1회와 위의 9회가 합쳐져 총 10회 실패다. 이후 올바른 비밀번호도 `401 invalid credentials`를 반환하므로 첫 5분 잠금이 적용됐고, 잠금 상태도 일반 자격 증명 실패로 은닉된다. 잠금 중에는 token과 Cookie가 발급되지 않았다.

---

## 6. 테스트 결과

| 항목 | 결과 | 근거 |
|---|---|---|
| 성공 로그인 | PASS | 200 런타임 응답 |
| access token Body 계약 | PASS | MockMvc + 런타임 Body |
| refresh token Cookie 전용 발급 | PASS | MockMvc + 런타임 Header 속성 |
| 요청값 검증 | PASS | MockMvc + 런타임 400 |
| 미존재·탈퇴·비밀번호 불일치 은닉 | PASS | 서비스 테스트 + 런타임 401 비교 |
| 첫 5분 잠금 | PASS | 런타임 10회 실패 후 올바른 비밀번호 401 |
| 5→10→20→40분 증가 | PASS | `LoginAttemptStoreTest` |
| 성공 후 잠금 이력 초기화 | PASS | `LoginServiceTest`의 MutableClock 시나리오 |
| 비인증 로그인 접근 | PASS | Authorization 헤더 없는 런타임 200 |
| 예상하지 못한 오류 500 공통 응답 | PASS | `LoginControllerWebMvcTest` |

## 7. 정리 결과

테스트 종료 뒤 다음을 수행했다.

```text
1. Spring 서버 종료
2. meomuneum_auth_login_test 스키마 삭제
3. auth_login_test@localhost 사용자 삭제
4. application-dev.yaml 무변경 확인
5. port 8080 LISTEN 프로세스 없음 확인
```

정리 명령의 출력은 비어 있었다.

해석: 조회 결과가 없으므로 테스트 전용 스키마와 전용 DB 사용자는 삭제됐다. `lsof` 출력도 비어 있어 8080 포트에서 서버가 실행 중이지 않다. `git diff -- src/main/resources/application-dev.yaml`과 `git diff --check` 역시 출력이 없어, 개발 DB 설정 파일은 변경되지 않았고 공백 오류도 없다.
