# 이메일·비밀번호 로그인 핵심 코드·동작 원리 리포트

> ⚠️ 이 문서는 `origin/dev` 통합 전의 클래스·경로를 설명하는 이력 문서입니다. 현재 구현은 [origin/dev 통합 및 로그인 API 검증 보고서](auth-login-origin-dev-integration-test-report.md)를 기준으로 확인합니다.

## 1. 한눈에 보는 요청 흐름

```text
POST /api/v1/auth/login
        │
        ▼
LoginSecurityConfiguration ── 비인증 POST 허용
        │
        ▼
LoginController ── JSON을 LoginRequest로 변환·검증
        │
        ▼
LoginService ── 잠금 확인 → 사용자 조회 → 탈퇴 확인 → BCrypt 비교
        │                                      │
        │                                      ├─ 실패: InvalidCredentialsException
        │                                      └─ 성공: 잠금 이력 삭제 + JwtTokenIssuer
        ▼
LoginController ── access token은 Body, refresh token은 Set-Cookie
        │
        ▼
LoginExceptionHandler ── 400 / 401 / 500 공통 응답 변환
```

## 2. 핵심 파일 지도

| 역할 | 파일 | 핵심 위치 |
|---|---|---|
| 로그인 URL과 HTTP 응답 조립 | [LoginController.java](/Users/bipo/Documents/meomuneum/BE-branch/feature/auth-login/src/main/java/com/muse/meomuneum/feature/auth/login/controller/LoginController.java:30) | 30–45행 |
| 로그인 비즈니스 규칙의 중심 | [LoginService.java](/Users/bipo/Documents/meomuneum/BE-branch/feature/auth-login/src/main/java/com/muse/meomuneum/feature/auth/login/service/LoginService.java:36) | 36–59행 |
| 실패 횟수·잠금 시간·초기화 | [LoginAttemptStore.java](/Users/bipo/Documents/meomuneum/BE-branch/feature/auth-login/src/main/java/com/muse/meomuneum/feature/auth/login/attempt/LoginAttemptStore.java:23) | 23–67행 |
| 이메일로 DB 사용자 조회 | [UserRepository.java](/Users/bipo/Documents/meomuneum/BE-branch/feature/auth-login/src/main/java/com/muse/meomuneum/feature/auth/login/user/UserRepository.java:13) | 13–38행 |
| 사용자 상태 표현 | [UserAccount.java](/Users/bipo/Documents/meomuneum/BE-branch/feature/auth-login/src/main/java/com/muse/meomuneum/feature/auth/login/user/UserAccount.java:5) | 5–9행 |
| JWT access/refresh token 발급 | [JwtTokenIssuer.java](/Users/bipo/Documents/meomuneum/BE-branch/feature/auth-login/src/main/java/com/muse/meomuneum/feature/auth/login/token/JwtTokenIssuer.java:31) | 31–67행 |
| 입력 DTO와 검증 규칙 | [LoginRequest.java](/Users/bipo/Documents/meomuneum/BE-branch/feature/auth-login/src/main/java/com/muse/meomuneum/feature/auth/login/dto/LoginRequest.java:7) | 7–10행 |
| 성공 응답 Body 필드 | [LoginResponse.java](/Users/bipo/Documents/meomuneum/BE-branch/feature/auth-login/src/main/java/com/muse/meomuneum/feature/auth/login/dto/LoginResponse.java:5) | 5–8행 |
| 예외를 HTTP 오류로 전환 | [LoginExceptionHandler.java](/Users/bipo/Documents/meomuneum/BE-branch/feature/auth-login/src/main/java/com/muse/meomuneum/feature/auth/login/response/LoginExceptionHandler.java:15) | 15–30행 |
| 로그인 URL 비인증 허용 | [LoginSecurityConfiguration.java](/Users/bipo/Documents/meomuneum/BE-branch/feature/auth-login/src/main/java/com/muse/meomuneum/feature/auth/login/config/LoginSecurityConfiguration.java:14) | 14–25행 |
| BCrypt·시간·토큰 설정 Bean | [AuthConfiguration.java](/Users/bipo/Documents/meomuneum/BE-branch/feature/auth-login/src/main/java/com/muse/meomuneum/feature/auth/login/config/AuthConfiguration.java:11) | 11–23행 |
| JWT 설정값의 타입 안전성 검증 | [AuthTokenProperties.java](/Users/bipo/Documents/meomuneum/BE-branch/feature/auth-login/src/main/java/com/muse/meomuneum/feature/auth/login/config/AuthTokenProperties.java:9) | 9–15행 |
| 실제 토큰 만료 설정 | [application.yaml](/Users/bipo/Documents/meomuneum/BE-branch/feature/auth-login/src/main/resources/application.yaml:13) | 13–17행 |

## 3. 요청을 받는 부분: Controller

### 위치

[LoginController.java:30](/Users/bipo/Documents/meomuneum/BE-branch/feature/auth-login/src/main/java/com/muse/meomuneum/feature/auth/login/controller/LoginController.java:30)

```java
@RequestMapping(value = "/api/v1/auth/login", method = RequestMethod.POST)
public ResponseEntity<ApiResponse<LoginResponse>> login(
        @Valid @RequestBody LoginRequest request)
```

### 동작 원리

- `@RestController`(19행)는 메서드의 반환값을 HTML 화면 이름이 아니라 JSON HTTP 응답으로 보낸다.
- `@RequestMapping`(30행)은 `POST /api/v1/auth/login` 요청을 이 메서드에 연결한다.
- `@RequestBody`는 JSON 요청 Body를 [LoginRequest](/Users/bipo/Documents/meomuneum/BE-branch/feature/auth-login/src/main/java/com/muse/meomuneum/feature/auth/login/dto/LoginRequest.java:7) 객체로 바꾼다.
- `@Valid`는 변환된 객체의 `@NotBlank`, `@Email`, `@Size` 규칙을 검사한다. 규칙 위반은 서비스까지 가지 않고 `MethodArgumentNotValidException`이 된다.

그 뒤 32행에서 [LoginService.login](/Users/bipo/Documents/meomuneum/BE-branch/feature/auth-login/src/main/java/com/muse/meomuneum/feature/auth/login/service/LoginService.java:37)를 호출한다. Controller는 **HTTP 입출력만 담당**하고, 사용자 존재 여부·비밀번호·잠금 같은 판단은 서비스로 넘긴다.

### 성공 응답과 Cookie 분리

32행의 서비스 결과는 [IssuedTokens](/Users/bipo/Documents/meomuneum/BE-branch/feature/auth-login/src/main/java/com/muse/meomuneum/feature/auth/login/token/IssuedTokens.java:3)다. 이 객체는 access/refresh token을 모두 갖지만, Controller는 용도를 분리한다.

- 33–39행: refresh token으로 `ResponseCookie`를 만들고 `HttpOnly`, `Secure`, `SameSite=Lax`, `/api/v1/auth` 경로, 만료 시간을 붙인다.
- 40행: [LoginResponse](/Users/bipo/Documents/meomuneum/BE-branch/feature/auth-login/src/main/java/com/muse/meomuneum/feature/auth/login/dto/LoginResponse.java:5)에 access token과 만료 시간만 넣는다.
- 42–44행: Cookie는 HTTP `Set-Cookie` 헤더로, access token은 JSON Body로 보낸다.

`@JsonProperty("access_token")`, `@JsonProperty("expires_in")`가 [LoginResponse:6–7](/Users/bipo/Documents/meomuneum/BE-branch/feature/auth-login/src/main/java/com/muse/meomuneum/feature/auth/login/dto/LoginResponse.java:6)에 있으므로 Java 필드명은 `accessToken`이어도 API 응답은 snake_case가 된다.

## 4. 핵심 비즈니스 로직: LoginService

### 위치

[LoginService.java:36](/Users/bipo/Documents/meomuneum/BE-branch/feature/auth-login/src/main/java/com/muse/meomuneum/feature/auth/login/service/LoginService.java:36)

`login()` 메서드가 로그인 규칙의 최종 판단자다. `@Service`(17행)는 Spring이 이 클래스를 Bean으로 관리하게 하고, 생성자(25–34행)를 통해 필요한 다른 객체를 주입한다.

### 1) 이메일 표준화

```java
String email = normalizeEmail(request.email());
```

38행은 입력 이메일의 앞뒤 공백을 제거하고 소문자로 바꾼다. 실제 처리는 57–59행의 `trim()`과 `toLowerCase(Locale.ROOT)`다. 그래서 ` MEMBER@example.com `과 `member@example.com`을 같은 사용자로 조회한다.

### 2) 잠금 상태를 가장 먼저 확인

```java
if (loginAttemptStore.isBlocked(email)) {
    throw new InvalidCredentialsException();
}
```

39–41행은 [LoginAttemptStore.isBlocked](/Users/bipo/Documents/meomuneum/BE-branch/feature/auth-login/src/main/java/com/muse/meomuneum/feature/auth/login/attempt/LoginAttemptStore.java:23)를 먼저 호출한다. 잠겨 있으면 DB 조회나 비밀번호 비교를 하지 않고 즉시 실패한다.

중요한 점은 사용자에게 "잠겼다"고 알리지 않는다는 것이다. [InvalidCredentialsException](/Users/bipo/Documents/meomuneum/BE-branch/feature/auth-login/src/main/java/com/muse/meomuneum/feature/auth/login/InvalidCredentialsException.java:3)은 [LoginExceptionHandler](/Users/bipo/Documents/meomuneum/BE-branch/feature/auth-login/src/main/java/com/muse/meomuneum/feature/auth/login/response/LoginExceptionHandler.java:20)에서 일반 `401 invalid credentials`로 바뀐다.

### 3) 사용자 조회와 탈퇴 사용자 차단

```java
UserAccount user = userRepository.findByEmail(email).orElse(null);
if (user == null || user.isDeleted()) {
    throw new InvalidCredentialsException();
}
```

43–46행은 [UserRepository.findByEmail](/Users/bipo/Documents/meomuneum/BE-branch/feature/auth-login/src/main/java/com/muse/meomuneum/feature/auth/login/user/UserRepository.java:26)를 호출한다.

- Repository의 SQL(13–18행)은 `users` 테이블에서 `email`로 `id`, `email`, `password_hash`, `deleted_at`을 읽는다.
- `?`(16행)는 `JdbcTemplate`이 이메일 값으로 바인딩하므로 SQL 문자열을 직접 이어 붙이지 않는다.
- 조회 결과는 [UserAccount](/Users/bipo/Documents/meomuneum/BE-branch/feature/auth-login/src/main/java/com/muse/meomuneum/feature/auth/login/user/UserAccount.java:5) record로 바뀐다.
- `user.isDeleted()`는 `deletedAt != null`인지 확인한다([UserAccount:7–8](/Users/bipo/Documents/meomuneum/BE-branch/feature/auth-login/src/main/java/com/muse/meomuneum/feature/auth/login/user/UserAccount.java:7)).

없는 사용자와 탈퇴 사용자가 동일한 예외를 내므로, 외부 응답으로 계정 존재나 탈퇴 상태를 알아낼 수 없다.

### 4) BCrypt 비밀번호 비교와 실패 기록

```java
if (!passwordEncoder.matches(request.password(), user.passwordHash())) {
    loginAttemptStore.recordFailure(email);
    throw new InvalidCredentialsException();
}
```

48–51행이 비밀번호 검증이다.

- `request.password()`는 사용자가 보낸 평문 비밀번호다.
- `user.passwordHash()`는 DB에서 읽은 BCrypt 해시다.
- `PasswordEncoder.matches()`는 평문을 다시 해시한 뒤 저장된 해시와 비교한다. 평문 비밀번호를 DB에 저장하거나 직접 문자열 비교하지 않는다.
- `PasswordEncoder`의 실제 구현체는 [AuthConfiguration:20–22](/Users/bipo/Documents/meomuneum/BE-branch/feature/auth-login/src/main/java/com/muse/meomuneum/feature/auth/login/config/AuthConfiguration.java:20)의 `BCryptPasswordEncoder`다.
- 불일치하면 먼저 `recordFailure()`를 실행하고, 뒤이어 동일한 401 예외를 던진다.

### 5) 성공 시 잠금 이력 완전 초기화와 토큰 발급

```java
loginAttemptStore.clearAfterSuccessfulLogin(email);
return tokenIssuer.issue(user);
```

53–54행은 모두 성공한 경우에만 실행된다.

- `clearAfterSuccessfulLogin()`은 해당 이메일의 실패 횟수, 잠금 횟수, 잠금 종료 시각 전체를 메모리에서 제거한다([LoginAttemptStore:36–38](/Users/bipo/Documents/meomuneum/BE-branch/feature/auth-login/src/main/java/com/muse/meomuneum/feature/auth/login/attempt/LoginAttemptStore.java:36)). 따라서 과거에 10분 잠금이 있었더라도 성공 뒤 다음 10회 실패는 다시 첫 5분 잠금이다.
- `tokenIssuer`의 선언 타입은 [TokenIssuer](/Users/bipo/Documents/meomuneum/BE-branch/feature/auth-login/src/main/java/com/muse/meomuneum/feature/auth/login/token/TokenIssuer.java:5) 인터페이스다. 실제 Spring Bean은 [JwtTokenIssuer](/Users/bipo/Documents/meomuneum/BE-branch/feature/auth-login/src/main/java/com/muse/meomuneum/feature/auth/login/token/JwtTokenIssuer.java:17)다. 인터페이스에 의존하므로 테스트에서는 가짜 구현을 쉽게 주입할 수 있다.

`@Transactional(readOnly = true)`(36행)는 DB 조회 중심 작업임을 Spring에 알린다. 현재 잠금 기록은 DB가 아닌 메모리 `ConcurrentHashMap`에서 이뤄지므로 이 트랜잭션 범위에 DB 쓰기는 없다.

## 5. 잠금 규칙: LoginAttemptStore

### 상태 저장 방식

[LoginAttemptStore.java:16](/Users/bipo/Documents/meomuneum/BE-branch/feature/auth-login/src/main/java/com/muse/meomuneum/feature/auth/login/attempt/LoginAttemptStore.java:16)의 `ConcurrentHashMap<String, LoginAttemptState>`는 이메일별 상태를 메모리에 보관한다.

- Key: 표준화된 이메일
- Value: 연속 실패 횟수, 누적 잠금 단계, 잠금 종료 시각
- `ConcurrentHashMap`: 여러 HTTP 요청 스레드가 동시에 실패 기록을 갱신해도 `compute()`(29행)를 통해 해당 키의 변경을 원자적으로 처리한다.

따라서 이 정책은 **DB/ERD에 저장되지 않으며, 서버 프로세스 재시작 시 초기화**된다.

### 10회 단위와 2배 잠금

`MAX_FAILED_ATTEMPTS = 10`(13행), `INITIAL_LOCKOUT_MINUTES = 5`(14행)이다.

`recordFailure()` → 내부 `recordFailure(now)`의 흐름은 다음과 같다.

1. 아직 잠긴 상태이면 47–49행에서 아무것도 변경하지 않는다.
2. 잠기지 않았으면 연속 실패를 1 증가시킨다(51행).
3. 10회 미만이면 종료한다(52–54행).
4. 정확히 10회가 되면 연속 실패를 0으로 되돌리고(56행), 잠금 단계 `lockoutCount`를 1 증가시킨다(57행).
5. `lockedUntil`을 현재 시각부터 잠금 시간만큼 뒤로 설정한다(58행).

잠금 시간은 65–66행에서 계산한다.

```text
잠금 분 = 5 × 2^(잠금 단계 - 1)

1단계: 5분
2단계: 10분
3단계: 20분
4단계: 40분
```

`Clock`은 [AuthConfiguration:15–18](/Users/bipo/Documents/meomuneum/BE-branch/feature/auth-login/src/main/java/com/muse/meomuneum/feature/auth/login/config/AuthConfiguration.java:15)에서 UTC 시스템 시각으로 주입된다. 테스트에서는 가짜 Clock을 주입해 실제로 5분을 기다리지 않고 시간을 전진시킨다.

## 6. 토큰 발급: JwtTokenIssuer

### 위치와 호출 연결

`LoginService.login()` 54행 → `TokenIssuer.issue(user)` → [JwtTokenIssuer.issue](/Users/bipo/Documents/meomuneum/BE-branch/feature/auth-login/src/main/java/com/muse/meomuneum/feature/auth/login/token/JwtTokenIssuer.java:31)

### 동작 원리

- 33행에서 발급 시각을 `Clock`으로 얻는다.
- 34–37행에서 같은 사용자 ID로 access와 refresh JWT를 각각 만든다. `typ` 값만 `access`와 `refresh`로 다르다.
- 46–52행은 JWT의 `header.payload.signature` 구조를 만든다.
  - header: 알고리즘 `HS256`, 타입 `JWT`
  - payload: `sub`(사용자 ID), `typ`, `iat`(발급 시각), `exp`(만료 시각)
  - signature: header와 payload를 합친 문자열에 HMAC-SHA256 서명을 붙인다.
- 59–66행은 Java의 `Mac`으로 HMAC 서명을 생성한다.

비밀키·만료 시간은 코드에 직접 쓰지 않는다. [application.yaml:13–17](/Users/bipo/Documents/meomuneum/BE-branch/feature/auth-login/src/main/resources/application.yaml:13) → [AuthTokenProperties:9–14](/Users/bipo/Documents/meomuneum/BE-branch/feature/auth-login/src/main/java/com/muse/meomuneum/feature/auth/login/config/AuthTokenProperties.java:9) → `JwtTokenIssuer`로 전달된다.

- `AUTH_TOKEN_SECRET`이 없으면 `@NotBlank`가 애플리케이션 시작 시 설정 오류를 만든다.
- 만료 값이 0 이하이면 `@Min(1)`가 막는다.
- 현재 설정: access 3,600초(1시간), refresh 1,209,600초(14일).

## 7. 보안 필터: 로그인만 비인증 허용

### 위치

[LoginSecurityConfiguration.java:14](/Users/bipo/Documents/meomuneum/BE-branch/feature/auth-login/src/main/java/com/muse/meomuneum/feature/auth/login/config/LoginSecurityConfiguration.java:14)

### 동작 원리

- `@Bean`은 `SecurityFilterChain`을 Spring Security에 등록한다.
- `@Order(0)`은 여러 보안 체인이 있을 때 가장 먼저 이 체인을 검사하게 한다.
- `securityMatcher("/api/v1/auth/login")`(18행)은 이 체인을 로그인 URL에만 적용한다.
- `csrf.disable()`(19행)은 토큰 없이 호출하는 로그인 POST에 CSRF 검사를 요구하지 않는다.
- `SessionCreationPolicy.STATELESS`(20행)는 서버 세션을 만들지 않는 토큰 기반 로그인임을 뜻한다.
- `requestMatchers(POST, "/api/v1/auth/login").permitAll()`(22행)은 Authorization 헤더 없이도 이 POST 요청이 Controller까지 갈 수 있게 한다.
- 같은 URL에 대한 다른 HTTP 메서드는 `denyAll()`(23행)로 막는다.

## 8. 오류 응답이 만들어지는 연결

[LoginExceptionHandler](/Users/bipo/Documents/meomuneum/BE-branch/feature/auth-login/src/main/java/com/muse/meomuneum/feature/auth/login/response/LoginExceptionHandler.java:12)은 `@RestControllerAdvice`로 등록돼 로그인 과정에서 발생한 예외를 JSON 응답으로 변환한다.

| 발생 지점 | 예외 | 처리 위치 | HTTP 응답 |
|---|---|---|---|
| `@Valid`, JSON 변환 | `MethodArgumentNotValidException`, `HttpMessageNotReadableException` | [15–18행](/Users/bipo/Documents/meomuneum/BE-branch/feature/auth-login/src/main/java/com/muse/meomuneum/feature/auth/login/response/LoginExceptionHandler.java:15) | `400 invalid request` |
| 잠금, 미존재, 탈퇴, 불일치 | `InvalidCredentialsException` | [20–24행](/Users/bipo/Documents/meomuneum/BE-branch/feature/auth-login/src/main/java/com/muse/meomuneum/feature/auth/login/response/LoginExceptionHandler.java:20) | `401 invalid credentials` |
| 그 밖의 예외 | `Exception` | [26–30행](/Users/bipo/Documents/meomuneum/BE-branch/feature/auth-login/src/main/java/com/muse/meomuneum/feature/auth/login/response/LoginExceptionHandler.java:26) | `500 internal server error` |

모든 Body는 [ApiResponse](/Users/bipo/Documents/meomuneum/BE-branch/feature/auth-login/src/main/java/com/muse/meomuneum/feature/auth/login/response/ApiResponse.java:3)의 `message`, `data` 형태다. 실패에는 `ApiResponse.failure()`가 호출돼 `data`가 `null`이 된다.

## 9. 핵심 흐름을 보장하는 테스트 위치

| 검증 내용 | 테스트 파일 | 핵심 위치 |
|---|---|---|
| HTTP Body·Cookie·400·401·500 | [LoginControllerWebMvcTest.java](/Users/bipo/Documents/meomuneum/BE-branch/feature/auth-login/src/test/java/com/muse/meomuneum/feature/auth/login/controller/LoginControllerWebMvcTest.java:40) | 40–104행 |
| 사용자 조회·탈퇴 차단·성공 토큰 발급 | [LoginServiceTest.java](/Users/bipo/Documents/meomuneum/BE-branch/feature/auth-login/src/test/java/com/muse/meomuneum/feature/auth/login/service/LoginServiceTest.java:55) | 55–111행 |
| 실제 성공 후 잠금 단계 초기화 | [LoginServiceTest.java](/Users/bipo/Documents/meomuneum/BE-branch/feature/auth-login/src/test/java/com/muse/meomuneum/feature/auth/login/service/LoginServiceTest.java:85) | 85–110행 |
| 5→10→20→40분 및 저장소 직접 초기화 | [LoginAttemptStoreTest.java](/Users/bipo/Documents/meomuneum/BE-branch/feature/auth-login/src/test/java/com/muse/meomuneum/feature/auth/login/attempt/LoginAttemptStoreTest.java:14) | 14–46행 |

## 10. 읽는 순서 제안

처음 코드를 읽을 때는 아래 순서가 가장 이해하기 쉽다.

1. [LoginController](/Users/bipo/Documents/meomuneum/BE-branch/feature/auth-login/src/main/java/com/muse/meomuneum/feature/auth/login/controller/LoginController.java:30) — 어떤 URL을 받고 무엇을 반환하는지 확인한다.
2. [LoginService](/Users/bipo/Documents/meomuneum/BE-branch/feature/auth-login/src/main/java/com/muse/meomuneum/feature/auth/login/service/LoginService.java:36) — 성공과 실패를 가르는 비즈니스 규칙을 읽는다.
3. [LoginAttemptStore](/Users/bipo/Documents/meomuneum/BE-branch/feature/auth-login/src/main/java/com/muse/meomuneum/feature/auth/login/attempt/LoginAttemptStore.java:23) — 잠금 정책을 확인한다.
4. [UserRepository](/Users/bipo/Documents/meomuneum/BE-branch/feature/auth-login/src/main/java/com/muse/meomuneum/feature/auth/login/user/UserRepository.java:13)와 [UserAccount](/Users/bipo/Documents/meomuneum/BE-branch/feature/auth-login/src/main/java/com/muse/meomuneum/feature/auth/login/user/UserAccount.java:5) — DB 데이터를 어떻게 읽고 탈퇴를 판단하는지 본다.
5. [JwtTokenIssuer](/Users/bipo/Documents/meomuneum/BE-branch/feature/auth-login/src/main/java/com/muse/meomuneum/feature/auth/login/token/JwtTokenIssuer.java:31) — 성공 뒤 토큰이 만들어지는 방식을 읽는다.
6. [LoginExceptionHandler](/Users/bipo/Documents/meomuneum/BE-branch/feature/auth-login/src/main/java/com/muse/meomuneum/feature/auth/login/response/LoginExceptionHandler.java:15)와 [LoginSecurityConfiguration](/Users/bipo/Documents/meomuneum/BE-branch/feature/auth-login/src/main/java/com/muse/meomuneum/feature/auth/login/config/LoginSecurityConfiguration.java:14) — 오류 응답과 접근 허용 범위를 확인한다.
