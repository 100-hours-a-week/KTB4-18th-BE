# origin/dev 통합 및 로그인 API 검증 보고서

## 기준

- 기준 커밋: `origin/dev`의 `f3bc376` (`feat(auth): JWT 기반 인증과 토큰 재발급 구현`)
- 작업 브랜치: `feature/auth-login`
- 테스트 DB: 로컬 격리 DB `meomuneum_auth_login_test` (테스트 종료 후 삭제)
- 실행 서버: `127.0.0.1:8081`

## 통합 판단

기존 로그인 구현과 `origin/dev`는 같은 `POST /api/v1/auth/login`, JWT 설정, `PasswordEncoder`, 보안 필터 체인을 각각 등록하고 있었다. 두 구현을 함께 유지하면 중복 Bean 또는 요청 매핑 충돌이 생긴다. 따라서 `origin/dev`의 공통 인증 구조(`AuthController` → `AuthService` → `UserAuthenticationService`)를 단일 구현으로 채택하고, 이 기능 브랜치의 로그인 실패 잠금 정책만 `LoginAttemptStore`와 `UserAuthenticationService`에 통합했다.

직접 충돌한 파일은 `.env.example`, `src/main/resources/application.yaml` 두 개였다. 모두 최신 `auth.jwt` 설정을 기준으로 해결했다.

## 토큰 발급 구현

- `AuthService.login(...)`이 자격 증명 검증 뒤 `issueTokens(...)`를 호출한다.
- `JwtTokenProvider.createAccessToken(user)`의 결과는 `TokenResponse.access_token`으로 응답 Body에만 넣는다.
- 같은 시점에 `JwtTokenProvider.createRefreshToken(user)`으로 refresh token을 만들고, `RefreshTokenCookieFactory.addRefreshTokenCookie(...)`가 `Set-Cookie` 헤더에 넣는다.
- refresh token은 `HttpOnly`, `Secure`, `SameSite=Lax`, `Path=/api/v1/auth` 속성을 사용하며 응답 JSON에는 포함하지 않는다.

## 원문 테스트 결과

```text
./gradlew test --no-daemon
BUILD SUCCESSFUL in 10s
4 actionable tasks: 1 executed, 3 up-to-date

./gradlew bootJar --no-daemon
BUILD SUCCESSFUL in 4s
4 actionable tasks: 2 executed, 2 up-to-date

success=200
malformed=400
success-body:
{"message":"login success","data":{"access_token":"eyJhbGciOiJI…","expires_in":3600}}
refresh-cookie:
Set-Cookie: refresh_token=<redacted>; Path=/api/v1/auth; Max-Age=1209600; Secure; HttpOnly; SameSite=Lax
malformed-body:
{"message":"invalid request","data":null}
```

토큰·Cookie의 실제 값은 민감 인증 정보이므로 원문 보고에 포함하지 않고 앞부분 또는 `<redacted>`로 마스킹했다.

## 해석

- 전체 단위·통합 테스트가 통과했다.
- 올바른 이메일·비밀번호는 200, `access_token`·`expires_in`만 포함한 Body, refresh token Cookie를 동시에 반환했다.
- 잘못된 JSON은 이전에 401이었으나 예외 처리 매개변수 타입을 보완하고 회귀 테스트를 추가하여 400 `invalid request`로 수정했다.
- 기존 HTTP 검증에서 미존재 이메일·탈퇴 사용자·10회 비밀번호 실패 후 올바른 비밀번호가 모두 401 `invalid credentials`임을 확인했다.

## 정리

테스트 전용 DB와 전용 DB 계정은 본 보고서 작성 후 제거한다. `application-dev.yaml` 및 개발 DB 설정은 변경하지 않는다.
