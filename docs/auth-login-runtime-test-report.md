# 이메일·비밀번호 로그인 런타임 테스트 리포트

## 환경

- 실행 대상: `feature/auth-login`의 배포용 Spring Boot JAR
- API: `POST /api/v1/auth/login`
- 데이터베이스: `meomuneum_auth_login_test` (테스트 전용 임시 MySQL 스키마)
- 실행 프로필: `dev` — 단, `DEV_DB_URL`, 사용자명, 비밀번호는 프로세스 환경 변수로만 임시 DB에 덮어썼다.
- 도구: Postman Desktop, `curl`, Gradle
- 민감값 정책: access token, refresh token, DB 비밀번호, 자동 생성 Spring Security 비밀번호는 `[REDACTED]`로 대체했다.

## 빌드와 서버 기동 원문

```text
> Task :bootJar

BUILD SUCCESSFUL in 4s
4 actionable tasks: 3 executed, 1 up-to-date

Tomcat started on port 8080 (http) with context path '/'
Started MeomuneumBackendApplication in 2.616 seconds
```

해석: 배포용 JAR가 격리 DB에 연결된 상태로 8080 포트에서 정상 기동했다. 개발 DB 설정 파일을 수정하지 않았다.

## Postman Desktop 실행 원문

```text
POST http://localhost:8080/api/v1/auth/login
Body: {"email":"login-test@example.com","password":"password"}
Response shown by Postman: 500 Internal Server Error
```

해석: Postman Desktop은 이전 요청부터 `500`을 계속 표시했다. 같은 시점에 동일 로컬 서버로 직접 보낸 `curl`은 아래처럼 정상 응답을 반환했고, 서버 로그에도 요청이 도달했다. 따라서 이 Postman 상태는 Desktop Agent/로컬 요청 경로의 응답 캐시 또는 프록시 문제로 판단되며, 서버 기능 판정에는 직접 HTTP 결과를 사용했다. Postman 화면에는 토큰을 표시하지 않았다.

## 실제 HTTP 테스트 원문

### 1. 유효 자격 증명

```text
{"message":"login success","data":{"access_token":"[REDACTED]","expires_in":3600}}
HTTP 200
```

해석: 성공 메시지와 `access_token`, `expires_in`만 Body에 포함됐다. 토큰 값은 보안상 마스킹했다.

### 2. 요청값 누락

```text
missing: {"message":"invalid request","data":null} HTTP=400
```

해석: 이메일과 비밀번호가 없는 요청은 명세대로 `400 invalid request`다.

### 3. 미존재·탈퇴 계정 은닉

```text
unknown: {"message":"invalid credentials","data":null} HTTP=401
withdrawn: {"message":"invalid credentials","data":null} HTTP=401
```

해석: 존재하지 않는 이메일과 `deleted_at`이 있는 계정 모두 동일한 `401 invalid credentials`를 반환하므로 계정 존재 여부를 노출하지 않는다.

### 4. 10회 실패 후 첫 잠금

```text
401 401 401 401 401 401 401 401 401 401
blocked-correct-password: {"message":"invalid credentials","data":null} HTTP=401
```

해석: 활성 계정에 잘못된 비밀번호를 10회 입력하면 모두 동일한 401로 응답한다. 직후 올바른 비밀번호도 401이므로 첫 5분 잠금이 적용됐음을 확인했다. 잠금 응답도 일반 자격 증명 실패와 동일하여 잠금 상태를 외부에 노출하지 않는다.

## 단위 테스트 원문

```text
./gradlew test --tests 'com.muse.meomuneum.feature.auth.login.*' bootJar --no-daemon
BUILD SUCCESSFUL
```

해석: 로그인 단위 테스트와 JAR 빌드가 통과했다. `LoginAttemptStoreTest`는 10회 단위 잠금 시간이 5분, 10분, 20분, 40분으로 증가하고 성공 로그인 초기화 뒤 다시 5분부터 시작하는 것을 검증한다.

## 범위와 미검증 항목

- refresh token의 실제 `Set-Cookie` 헤더는 컨트롤러 단위 테스트에서 `HttpOnly`, `Secure`, `SameSite=Lax`, Path 및 Body 미포함을 검증했다. 런타임 성공 응답의 토큰·쿠키 값은 기록하지 않았다.
- 5분, 10분, 20분, 40분을 실제로 대기하는 E2E 테스트는 수행하지 않았다. 시간 증가와 성공 후 초기화는 단위 테스트로 검증했다.
- 예상하지 못한 예외를 의도적으로 발생시키는 500 런타임 테스트는 수행하지 않았다.

## 정리 결과

```text
Spring server: stopped (port 8080 has no LISTEN process)
Temporary schema lookup: no rows
Temporary DB-user lookup: no rows
git diff -- src/main/resources/application-dev.yaml: no output
git diff --check: no output
```

해석: 서버를 정상 종료했고 `meomuneum_auth_login_test` 및 전용 로컬 DB 사용자를 삭제했다. `application-dev.yaml`은 테스트 전후 변경하지 않았으며, 남아 있는 변경은 로그인 기능 소스·테스트·문서와 공용 토큰 설정뿐이다.
