# 인증 구조 및 로그인 기능 코드 리뷰 리포트

## 1. 검토 결론

현재 `feature/auth-login`은 기존에 별도로 만들었던 로그인 전용 컨트롤러·보안 체인·JWT 발급기를 유지하지 않고, 최신 `origin/dev`의 공통 인증 구조를 **단일 구현**으로 채택했다. 그 위에 이 기능의 로그인 실패 잠금 정책만 통합했다.

따라서 같은 URL 또는 Bean이 두 번 등록되는 문제 없이 다음 요구를 충족한다.

- 로그인 성공 시 access token을 Body에 반환하고, refresh token을 Cookie로 함께 발급한다.
- 이후 보호 API는 `Authorization: Bearer {access token}` 헤더를 통해 인증한다.
- refresh token은 JavaScript가 읽지 못하는 `HttpOnly` Cookie로만 다룬다.
- 미존재 이메일·탈퇴 사용자·비밀번호 불일치·잠금 상태는 모두 동일한 `401 invalid credentials`로 응답한다.
- 활성 계정의 비밀번호 실패는 10회 단위로 5분 → 10분 → 20분 → 40분 잠금되고, 정상 로그인 성공 시 전체 이력이 초기화된다.

## 2. 구조를 가져간 방식

### 통합 전 위험

기존 기능 브랜치와 최신 `origin/dev`는 모두 아래 요소를 별도로 가지고 있었다.

- `POST /api/v1/auth/login` 요청 매핑
- `SecurityFilterChain`, `PasswordEncoder` Bean
- JWT 발급·검증 설정
- `users` 조회 구현

두 구조를 같이 유지하면 Spring이 동일 URL을 어느 컨트롤러로 연결할지 결정할 수 없거나, 동일 타입 Bean을 둘 이상 등록하는 문제가 생긴다.

### 최종 선택

`origin/dev`의 공통 인증 계층을 기준으로 정리했다.

```text
클라이언트
  │  POST /api/v1/auth/login
  ▼
AuthController
  │  @Valid LoginRequest 변환
  ▼
AuthService
  │  1. 사용자 인증 요청
  ▼
UserAuthenticationService
  ├─ UserRepository: 활성 사용자만 조회
  ├─ PasswordEncoder: BCrypt 해시 비교
  └─ LoginAttemptStore: 잠금 조회·실패 기록·성공 초기화
  ▼
AuthService.issueTokens
  ├─ JwtTokenProvider: access / refresh JWT 각각 생성
  ├─ RefreshTokenSessionService: refresh token 해시를 서버 세션에 등록
  └─ RefreshTokenCookieFactory: refresh token Set-Cookie 생성
  ▼
응답 Body: access_token, expires_in
응답 Header: Set-Cookie(refresh_token)
```

## 3. 로그인 및 access token 동작 원리

### 3.1 로그인 요청

[AuthController.java](../src/main/java/com/muse/meomuneum/auth/controller/AuthController.java)의 `login()`은 `POST /api/v1/auth/login`을 받는다. `@Valid @RequestBody LoginRequest`가 JSON을 DTO로 바꾸고 `email`, `password`의 공백·이메일 형식을 먼저 검증한다.

[SecurityConfig.java](../src/main/java/com/muse/meomuneum/global/config/SecurityConfig.java)는 `/api/v1/auth/**`를 `permitAll()`로 설정한다. 따라서 로그인 전에 access token이 없어도 요청할 수 있다. 로그인은 CSRF 예외 경로이기도 하다.

### 3.2 사용자 확인과 잠금 정책

[UserAuthenticationService.java](../src/main/java/com/muse/meomuneum/user/service/UserAuthenticationService.java)의 `authenticate()`가 핵심 비즈니스 규칙을 담당한다.

1. 이메일 앞뒤 공백을 제거하고 소문자로 정규화한다.
2. `LoginAttemptStore.isBlocked()`로 잠금 여부를 먼저 확인한다.
3. `UserRepository.findAllByEmailAndDeletedAtIsNull()`로 탈퇴하지 않은 사용자만 조회한다.
4. 정확히 한 명이 아니거나 `PasswordEncoder.matches()`가 실패하면 `401 invalid credentials`를 던진다.
5. 활성 사용자의 비밀번호 불일치일 때만 실패 횟수를 기록한다.
6. 성공하면 `clearAfterSuccessfulLogin()`으로 이전 실패 수·잠금 단계·잠금 종료 시각을 모두 삭제한다.

[LoginAttemptStore.java](../src/main/java/com/muse/meomuneum/auth/service/LoginAttemptStore.java)는 `ConcurrentHashMap`으로 이메일별 상태를 메모리에 저장한다. DB에 잠금 정보를 넣지 않는다는 기능 결정과 일치한다.

### 3.3 access token·refresh token 동시 발급

[AuthService.java](../src/main/java/com/muse/meomuneum/auth/service/AuthService.java)의 `login()`은 인증 성공 뒤 세션 ID를 바꾸고 `issueTokens()`를 호출한다.

- `createAccessToken(user)`: access JWT를 만들고 `TokenResponse.access_token`으로 반환한다.
- `createRefreshToken(user)`: refresh JWT를 만든다.
- `RefreshTokenSessionService.register()`: refresh token 원문이 아니라 SHA-256 해시를 HTTP 세션에 저장한다.
- `RefreshTokenCookieFactory.addRefreshTokenCookie()`: refresh token을 `Set-Cookie` 헤더에만 추가한다.

즉 로그인 응답 Body에는 `access_token`, `expires_in`만 존재하며 refresh token은 Body에 포함되지 않는다.

### 3.4 이후 보호 API의 Bearer 인증

[JwtAuthenticationFilter.java](../src/main/java/com/muse/meomuneum/global/security/JwtAuthenticationFilter.java)는 `OncePerRequestFilter`다. 로그인·refresh·logout·CSRF 발급 경로를 제외한 요청에서 `Authorization` 헤더를 읽는다.

```http
Authorization: Bearer {access_token}
```

정상 access token이면 `JwtTokenProvider.parseAccessToken()`이 서명, 만료 시각, issuer, audience, `ACCESS` 타입, 역할 claim을 확인한다. 검증된 사용자 ID와 역할은 `SecurityContextHolder`에 넣고, 뒤의 보호 API는 이를 인증된 사용자로 인식한다. 토큰이 없으면 다음 필터로 넘기며, 보호 API의 인가 단계가 401을 반환한다. 형식·서명·만료가 잘못된 Bearer token은 필터에서 즉시 401로 처리한다.

### 3.5 JWT와 refresh Cookie의 보안 분리

[JwtTokenProvider.java](../src/main/java/com/muse/meomuneum/global/security/JwtTokenProvider.java)는 HS256으로 토큰을 서명하며 최소 32바이트의 `AUTH_JWT_SECRET`을 강제한다. 토큰에는 issuer, audience, 사용자 ID, 역할, 만료 시각, UUID 기반 `jti`, `ACCESS` 또는 `REFRESH` 타입을 넣는다.

[RefreshTokenCookieFactory.java](../src/main/java/com/muse/meomuneum/auth/service/RefreshTokenCookieFactory.java)는 refresh Cookie에 `HttpOnly`, `Secure`, `SameSite=Lax`, `Path=/api/v1/auth`를 설정한다. 브라우저 JavaScript가 refresh token 원문을 읽지 못하게 하고, refresh 관련 경로에만 자동 전송되도록 범위를 좁힌다.

## 4. 오류 처리

[GlobalExceptionHandler.java](../src/main/java/com/muse/meomuneum/global/exception/GlobalExceptionHandler.java)는 다음을 공통 응답 형식으로 변환한다.

| 상황 | 상태·메시지 | 처리 경로 |
| --- | --- | --- |
| 누락·형식 오류·잘못된 JSON | `400 invalid request` | `MethodArgumentNotValidException`, `HttpMessageNotReadableException` |
| 미존재·탈퇴·비밀번호 불일치·잠금 | `401 invalid credentials` | `AuthenticationFailedException` |
| 예상 밖 오류 | 공통 `500` | 최종 `Exception` handler |

잘못된 JSON이 이전에 401로 보이던 문제는 예외 처리 메서드가 두 예외 타입 중 하나만 매개변수로 받을 수 있던 문제였다. 매개변수를 공통 `Exception`으로 바꾸고 컨트롤러 회귀 테스트를 추가하여 400으로 검증했다.

## 5. 검증 근거

- `origin/dev`를 조상으로 포함한 상태에서 3-way merge 시뮬레이션 성공
- `git diff --check origin/dev...HEAD` 성공
- `./gradlew test --no-daemon` 성공
- `./gradlew bootJar --no-daemon` 성공
- 실제 HTTP 검증 성공
  - 유효 로그인: 200, access token Body 반환, refresh `Set-Cookie` 발급
  - 잘못된 JSON: 400 `invalid request`
  - 미존재 이메일·탈퇴 사용자·잘못된 비밀번호: 401 `invalid credentials`
  - 활성 사용자 10회 실패 뒤 올바른 비밀번호: 401 `invalid credentials`

자세한 실행 원문은 [통합 및 테스트 보고서](auth-login-origin-dev-integration-test-report.md)에 있다.

## 6. 발견 사항과 후속 권고

### 해결 완료

| 항목 | 상태 |
| --- | --- |
| 로그인 전용 구조와 최신 공통 인증 구조의 중복 매핑·Bean 위험 | 공통 `AuthController`·`AuthService`·`SecurityConfig`만 사용하도록 해소 |
| 잘못된 JSON의 잘못된 401 응답 | 400 `invalid request`로 수정, 회귀 테스트 추가 |
| 성공 이후 과거 잠금 단계가 남는 문제 | 성공 시 `LoginAttemptStore` 상태 전체 삭제로 해소 |

### 현재 기능 범위 밖의 권고

| 우선순위 | 항목 | 이유·권고 |
| --- | --- | --- |
| P2 | 다중 서버 환경의 잠금 상태 | 메모리 `ConcurrentHashMap`은 서버 재시작 또는 여러 인스턴스에서 공유되지 않는다. 현재의 “DB 저장 안 함” 결정에는 부합하지만, 다중 인스턴스가 필요해지면 Redis 같은 공유 TTL 저장소를 별도 이슈로 검토한다. |
| P2 | 잠금 단계 상한 | 잠금 횟수가 매우 많이 누적되면 비트 시프트 기반 두 배 계산이 순환할 수 있다. `lockoutCount` 또는 최대 잠금 시간을 상한 처리하는 후속 개선이 안전하다. |
| P3 | 이메일 대소문자 데이터 정합성 | 조회 전 이메일을 소문자로 바르므로 가입 과정도 동일 정규화를 보장해야 한다. 그렇지 않으면 대문자를 보존한 기존 사용자 데이터를 찾지 못할 수 있다. |
| P3 | 타이밍 기반 계정 추측 | 응답은 동일하지만, 미존재 계정은 BCrypt 비교를 생략한다. 고위험 환경에서는 더미 BCrypt 해시 비교를 추가해 처리 시간 차이를 줄일 수 있다. |

위 네 항목은 현재 로그인 API의 완료 조건을 막지 않으며, 범위를 넓히지 않기 위해 이번 브랜치에서는 구현하지 않았다.
