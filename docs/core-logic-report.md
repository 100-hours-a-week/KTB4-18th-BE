# 이메일 비밀번호 로그인 핵심 로직 리포트

> ⚠️ 이 문서는 `origin/dev` 통합 전의 클래스·경로를 설명하는 이력 문서입니다. 현재 구현은 [origin/dev 통합 및 로그인 API 검증 보고서](auth-login-origin-dev-integration-test-report.md)를 기준으로 확인합니다.

이 문서는 `feature/auth-login`의 코드가 어떤 순서로 움직이고, 어떤 파일이 다른 파일을 참조하는지 쉽게 설명한다. 백엔드가 로그인 결과와 Cookie를 만들고, 프론트엔드가 그 API를 호출해 화면 상태를 바꾸는 흐름까지 다룬다.

## 1. 한눈에 보는 전체 흐름

```text
사용자
  ↓ 이메일·비밀번호 입력
LoginPage (프론트 화면)
  ↓ login() 함수 호출, Cookie 포함 POST 요청
POST /api/v1/auth/login
  ↓ LoginController
  ↓ LoginService
  ├─ LoginAttemptStore: 잠금 여부와 실패 횟수 확인
  ├─ UserRepository: users 테이블에서 이메일 조회
  ├─ PasswordEncoder: BCrypt 비밀번호 해시 비교
  └─ TokenIssuer(JwtTokenIssuer): access/refresh token 생성
  ↓
LoginController: access token은 JSON Body, refresh token은 Set-Cookie로 응답
  ↓
LoginPage: 성공 또는 400·401·500·네트워크 오류 화면 표시
```

## 2. 백엔드 핵심 기능 로직

### 2.1 HTTP 요청을 받는 곳: LoginController

파일: [`LoginController.java`](../src/main/java/com/muse/meomuneum/feature/auth/login/controller/LoginController.java)

이 클래스는 브라우저가 보낸 HTTP 요청을 Java 코드로 연결하는 입구다.

```java
@RestController
@RequestMapping("/api/v1/auth")
public class LoginController {
    @PostMapping("/login")
    public ResponseEntity<ApiResponse<LoginResponse>> login(
            @Valid @RequestBody LoginRequest request) {
```

어노테이션과 참조 관계는 다음과 같다.

| 코드 | 쉬운 설명 | 연결되는 코드 |
| --- | --- | --- |
| `@RestController` | 이 클래스의 반환값을 JSON HTTP 응답으로 보낸다. | Spring Web |
| `@RequestMapping("/api/v1/auth")` | 이 클래스의 기본 주소를 정한다. | 최종 주소는 `/api/v1/auth/login` |
| `@PostMapping("/login")` | POST 요청만 이 메서드로 보낸다. | `LoginSecurityConfiguration`의 permitAll 경로 |
| `@RequestBody` | 요청 JSON을 `LoginRequest` 객체로 바꾼다. | `dto/LoginRequest.java` |
| `@Valid` | `LoginRequest`의 이메일·비밀번호 규칙을 검사한다. | 오류 시 `LoginExceptionHandler`가 400을 만든다. |

`login()` 메서드는 [`LoginService`](../src/main/java/com/muse/meomuneum/feature/auth/login/service/LoginService.java)의 `login(request)`를 호출한다. 서비스가 돌려준 `IssuedTokens`에서 access token만 `LoginResponse`에 담고, refresh token은 `ResponseCookie`로 만든다.

```java
ResponseCookie.from("refresh_token", tokens.refreshToken())
    .httpOnly(true)
    .secure(true)
    .sameSite("Lax")
    .path("/api/v1/auth")
```

- `HttpOnly`: JavaScript가 refresh token을 읽을 수 없다.
- `Secure`: HTTPS 연결에서만 Cookie를 보낸다.
- `SameSite=Lax`: 다른 사이트에서 의도치 않게 Cookie를 보내는 위험을 줄인다.
- `Path=/api/v1/auth`: Cookie를 인증 API 경로에만 보낸다.

### 2.2 실제 로그인 판단: LoginService

파일: [`LoginService.java`](../src/main/java/com/muse/meomuneum/feature/auth/login/service/LoginService.java)

`LoginService.login()`이 로그인 기능의 중심이다. 컨트롤러는 HTTP 형식만 다루고, 이 서비스가 “로그인해도 되는가?”라는 업무 규칙을 처리한다.

1. 이메일을 공백 제거·소문자로 정규화한다.
2. 해당 이메일이 현재 잠겼는지 확인한다.
3. `users` 테이블에서 사용자를 찾는다.
4. 사용자가 없거나 탈퇴 상태면 동일한 `InvalidCredentialsException`을 던진다.
5. BCrypt 해시 비교가 실패하면 실패 횟수를 기록하고 동일한 예외를 던진다.
6. 성공하면 연속 실패 횟수를 초기화하고 토큰 발급기를 호출한다.

```java
UserAccount user = userRepository.findByEmail(email).orElse(null);
if (user == null || user.isDeleted()) {
    throw new InvalidCredentialsException();
}

if (!passwordEncoder.matches(request.password(), user.passwordHash())) {
    loginAttemptStore.recordFailure(email);
    throw new InvalidCredentialsException();
}

loginAttemptStore.clearAfterSuccessfulLogin(email);
return tokenIssuer.issue(user);
```

참조 관계:

| LoginService가 사용하는 것 | 하는 일 | 파일 |
| --- | --- | --- |
| `UserRepository` | 이메일로 DB 사용자 한 명을 찾는다. | [`user/UserRepository.java`](../src/main/java/com/muse/meomuneum/feature/auth/login/user/UserRepository.java) |
| `UserAccount` | DB에서 읽은 id·email·passwordHash·deletedAt을 담는다. | [`user/UserAccount.java`](../src/main/java/com/muse/meomuneum/feature/auth/login/user/UserAccount.java) |
| `PasswordEncoder` | 평문 비밀번호와 BCrypt 해시가 일치하는지 확인한다. | Spring Security, Bean은 [`config/AuthConfiguration.java`](../src/main/java/com/muse/meomuneum/feature/auth/login/config/AuthConfiguration.java) |
| `LoginAttemptStore` | 실패 횟수와 잠금 상태를 메모리에 보관한다. | [`attempt/LoginAttemptStore.java`](../src/main/java/com/muse/meomuneum/feature/auth/login/attempt/LoginAttemptStore.java) |
| `TokenIssuer` | 성공한 사용자에 대한 두 토큰을 만든다. | [`token/TokenIssuer.java`](../src/main/java/com/muse/meomuneum/feature/auth/login/token/TokenIssuer.java) |

`@Transactional(readOnly = true)`는 이 메서드의 DB 작업이 조회 중심임을 Spring에 알린다. 로그인 실패 횟수는 DB가 아니라 별도 메모리 저장소에서 바뀌므로 이 트랜잭션과 분리되어 있다.

### 2.3 실패 10회 단위 지수 백오프 잠금: LoginAttemptStore

파일: [`LoginAttemptStore.java`](../src/main/java/com/muse/meomuneum/feature/auth/login/attempt/LoginAttemptStore.java)

이 클래스가 “10회 실패 → 5분 잠금 → 다음 10회 실패 → 10분 잠금 → 다시 두 배 증가” 정책을 담당한다.

```text
활성 사용자 비밀번호 불일치
  ↓ recordFailure(email)
1~9회: 계속 시도 가능
10회: lockedUntil = 현재 시각 + 5분
  ↓ 5분 경과
다시 10회 실패: lockedUntil = 현재 시각 + 10분
  ↓ 잠금 해제 뒤 다시 10회 실패
20분, 40분처럼 잠금 시간이 두 배씩 증가
  ↓ 잠금 해제 후 정상 로그인 성공
clearAfterSuccessfulLogin(email)으로 실패 횟수와 잠금 단계 전체 초기화
```

중요한 메서드:

| 메서드 | 하는 일 | LoginService와의 관계 |
| --- | --- | --- |
| `isBlocked(email)` | 현재 단계의 시간 잠금 안인지 확인한다. | 로그인 시작 직후 호출한다. |
| `recordFailure(email)` | 실패 수를 원자적으로 증가시키고 10회째마다 5·10·20·40분 잠금을 만든다. | BCrypt 비교 실패 때 호출한다. |
| `clearAfterSuccessfulLogin(email)` | 정상 로그인 때 실패 횟수와 누적 잠금 단계를 모두 삭제한다. | 로그인 성공 때 호출한다. |

`ConcurrentHashMap.compute()`은 같은 이메일로 요청이 거의 동시에 들어와도 한 번에 한 상태만 수정하도록 돕는다. 즉, 동시에 여러 번 실패해도 카운트가 쉽게 유실되지 않게 한다.

잠금 상태는 DB에 저장하지 않는다. 그래서 서버 재시작 시 사라지고, 서버가 여러 대면 각 서버가 각자 상태를 가진다. 또한 존재하지 않거나 탈퇴한 이메일은 메모리에 기록하지 않아 임의 이메일로 메모리를 계속 소비시키는 문제를 줄였다.

### 2.4 사용자 DB 조회: UserRepository

파일: [`UserRepository.java`](../src/main/java/com/muse/meomuneum/feature/auth/login/user/UserRepository.java)

`findByEmail(email)`은 아래 SQL을 실행해 `users` 테이블의 로그인에 필요한 네 컬럼만 읽는다.

```sql
SELECT id, email, password_hash, deleted_at
FROM users
WHERE email = ?
LIMIT 1
```

`?`는 전달받은 이메일 값으로 안전하게 바인딩된다. 문자열을 이어 붙이지 않으므로 SQL injection 위험을 줄인다.

조회 결과는 `UserAccount` record가 된다. `deleted_at`이 `null`이 아니면 `UserAccount.isDeleted()`가 `true`를 반환하고, `LoginService`가 인증 실패로 처리한다.

### 2.5 토큰 만들기: JwtTokenIssuer

파일: [`JwtTokenIssuer.java`](../src/main/java/com/muse/meomuneum/feature/auth/login/token/JwtTokenIssuer.java)

`TokenIssuer`는 “토큰을 발급한다”는 약속(인터페이스)이고, `JwtTokenIssuer`가 실제 구현체다.

```text
LoginService
  ↓ TokenIssuer.issue(user)
JwtTokenIssuer
  ↓ AuthTokenProperties의 secret·만료 시간 사용
IssuedTokens(access token, refresh token, 각각의 만료 시간)
```

`JwtTokenIssuer`는 사용자 id를 subject(`sub`)로 넣고, access/refresh 구분(`typ`)과 발급·만료 시각을 포함한 JWT를 HMAC SHA-256으로 서명한다. secret과 만료 시간은 [`application.yaml`](../src/main/resources/application.yaml)과 환경 변수 `AUTH_TOKEN_SECRET`에서 읽는다. 실제 secret은 저장소에 넣지 않으며, 예시값은 [`.env.example`](../.env.example)에만 둔다.

### 2.6 인증 없이 로그인 경로만 열기: LoginSecurityConfiguration

파일: [`LoginSecurityConfiguration.java`](../src/main/java/com/muse/meomuneum/feature/auth/login/config/LoginSecurityConfiguration.java)

```java
@Bean
@Order(0)
SecurityFilterChain loginSecurity(HttpSecurity http)
```

- `@Bean`: Spring이 이 보안 규칙을 애플리케이션 시작 시 등록한다.
- `@Order(0)`: 기존 추천 API 보안 체인보다 먼저 검사한다.
- `securityMatcher("/api/v1/auth/login")`: 로그인 주소에만 이 체인을 적용한다.
- `requestMatchers(HttpMethod.POST, ...).permitAll()`: 로그인 POST는 access token 없이도 접근 가능하다.
- `anyRequest().denyAll()`: 같은 주소의 의도하지 않은 다른 HTTP 메서드는 허용하지 않는다.
- `csrf.disable()`: JSON 기반 로그인 POST가 CSRF 토큰 때문에 막히지 않도록 이 경로에서만 해제한다.

### 2.7 실패 응답 하나로 통일하기: LoginExceptionHandler

파일: [`LoginExceptionHandler.java`](../src/main/java/com/muse/meomuneum/feature/auth/login/response/LoginExceptionHandler.java)

`@RestControllerAdvice`는 여러 컨트롤러에서 발생한 예외를 한 곳에서 HTTP 응답으로 바꾸는 Spring 기능이다.

| 발생 상황 | 예외 또는 검증 결과 | 반환값 |
| --- | --- | --- |
| 이메일 형식·필수값·JSON 오류 | `MethodArgumentNotValidException`, `HttpMessageNotReadableException` | 400 `invalid request` |
| 미가입·탈퇴·비밀번호 불일치·잠금 | `InvalidCredentialsException` | 401 `invalid credentials` |
| 예상 밖 오류 | 그 외 `Exception` | 500 `internal server error` |

이 구조 덕분에 `LoginService`는 HTTP 상태 코드를 직접 알 필요 없이 “인증 실패”만 표현하고, 응답 형식은 한 곳에서 관리한다.

## 3. 프론트엔드 핵심 기능 로직

### 3.1 화면 상태와 사용자 입력: LoginPage

파일: [`LoginPage.tsx`](../../../FE-branch/feature/auth-login/src/features/auth-login/components/LoginPage.tsx)

`LoginPage`는 화면에 보이는 이메일·비밀번호 입력과 버튼의 상태를 관리한다.

| state | 의미 |
| --- | --- |
| `email`, `password` | 사용자가 입력한 값 |
| `fieldErrors` | 이메일 또는 비밀번호 자체가 잘못되었을 때의 문구 |
| `requestError` | 서버가 400·401·500을 반환했거나 네트워크가 끊겼을 때의 문구 |
| `isPasswordVisible` | 비밀번호 입력을 보이게 할지 정한다. |
| `isSubmitting` | 요청 중인지 표시하고 중복 클릭을 막는다. |
| `isLoginSuccessful` | 현재 화면에서 성공 메시지를 보여 준다. |

`handleSubmit()`은 아래 순서로 움직인다.

1. 브라우저의 기본 form 전송을 막는다.
2. 이미 요청 중이면 아무것도 하지 않는다.
3. `validate()`로 이메일 형식·빈 비밀번호를 먼저 확인한다.
4. 값이 유효하면 [`login()`](../../../FE-branch/feature/auth-login/src/features/auth-login/api/loginApi.ts) 함수를 호출한다.
5. 결과에 따라 성공 메시지 또는 오류 메시지를 표시한다.
6. 요청 완료 후 `isSubmitting`을 `false`로 되돌린다.

JSX의 `label`과 `htmlFor`는 입력창을 연결한다. 오류 문구의 `role="alert"`는 화면 읽기 프로그램이 오류를 바로 알릴 수 있게 하고, 성공 문구의 `role="status"`는 상태 변화를 알린다.

### 3.2 API 호출과 오류 변환: loginApi

파일: [`loginApi.ts`](../../../FE-branch/feature/auth-login/src/features/auth-login/api/loginApi.ts)

`login(request)`은 프론트엔드에서 백엔드로 연결되는 유일한 로그인 API 함수다.

```ts
fetch('/api/v1/auth/login', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  credentials: 'include',
  body: JSON.stringify(request),
})
```

- `credentials: 'include'`: 서버가 보낸 refresh token Cookie를 브라우저가 받도록 한다.
- `LoginSuccessResponse`: Body에 `access_token`, `expires_in`만 있다고 타입으로 표현한다.
- `LoginRequestError`: HTTP 상태 코드 또는 네트워크 실패를 화면에 전달하는 전용 오류다.
- refresh token은 응답 Body로 읽지 않고, localStorage·sessionStorage·React state에 저장하지 않는다.

`LoginPage`는 이 오류를 다시 사용자 문구로 바꾼다.

| 상태 | 사용자에게 보이는 문구 |
| --- | --- |
| 400 | 입력값을 확인해 주세요. |
| 401 | 이메일 또는 비밀번호를 확인해 주세요. |
| 500 | 잠시 후 다시 시도해 주세요. |
| 네트워크 오류 | 네트워크 상태를 확인한 뒤 다시 시도해 주세요. |

401은 미가입·탈퇴·잠금·비밀번호 불일치 모두 같은 문구다. 그래서 사용자가 다른 사람의 계정 상태를 알아내기 어렵다.

### 3.3 디자인 시스템과 SEED

파일: [`LoginPage.tsx`](../../../FE-branch/feature/auth-login/src/features/auth-login/components/LoginPage.tsx), [`App.css`](../../../FE-branch/feature/auth-login/src/App.css), [`index.css`](../../../FE-branch/feature/auth-login/src/index.css)

- `ActionButton`은 `@seed-design/react`에서 가져온 당근 SEED 버튼이다.
- `brandSolid`, `large`, `loading` 속성으로 기본 행동 버튼과 제출 중 상태를 표현한다.
- `text-title1-bold`, `text-body1-normal-regular` 같은 클래스는 기존 typography 토큰을 사용한다.
- `var(--color-...)` 값은 기존 semantic color 토큰을 사용한다.
- `@import '@seed-design/css/all.css'`는 SEED 컴포넌트 스타일을 포함한다.

## 4. 설계 문서와 구현의 일치 여부

### 구현된 항목

- 로그인 API, 요청 검증, 이메일 조회, BCrypt 비교, 탈퇴 차단
- access token Body와 refresh Cookie 분리
- 400·401·500 통일 응답
- 메모리 기반 10회 실패 단위의 5·10·20·40분 지수 백오프 잠금과 정상 로그인 시 초기화
- 로그인 경로 비인증 허용
- React·TypeScript 로그인 UI, SEED 버튼, 스타일 토큰, Cookie 포함 API 요청
- 이메일 검증, 비밀번호 표시 전환, 중복 제출 방지, 오류 접근성 속성

### 아직 완료 조건을 충족하지 못한 항목

1. **통합 테스트가 부족하다.** 로그인 API의 실제 HTTP 요청, `SecurityFilterChain`, 400·401·500 JSON, Cookie를 MockMvc 또는 실제 테스트 DB로 검증하는 테스트가 없다.
2. **프론트엔드 자동 테스트가 없다.** 현재 프로젝트에는 `npm run test` 스크립트와 테스트 러너가 없다. 설계 문서의 입력 검증·오류 UX·Cookie 포함 요청 테스트는 아직 작성되지 않았다.
3. **users 스키마와 테스트 데이터가 선행 의존성이다.** `UserRepository`는 ERD의 `users` 테이블을 조회하지만, 이 기능에서는 해당 테이블 migration을 추가하지 않았다.
4. **잠금 시간의 최대 상한이 미결정이다.** 현재는 요구사항대로 10회 실패 단위마다 두 배 증가한다.
5. **성공 이후 이동 경로가 없다.** 프론트엔드는 로그인 성공 메시지만 보인다. 보호 화면 경로와 access token 사용 위치가 결정되면 그 흐름을 연결해야 한다.

## 5. 테스트 결과

| 검증 | 결과 | 설명 |
| --- | --- | --- |
| 백엔드 로그인 단위 테스트 | 통과 | 실패 제한, 로그인 서비스, Cookie 응답 테스트 통과 |
| 백엔드 `bootJar` | 통과 | 배포 JAR 생성 가능 |
| 프론트엔드 `npm run build` | 통과 | TypeScript와 Vite production build 통과 |
| 프론트엔드 `npm run lint` | 통과 | ESLint 통과 |
| 백엔드 전체 테스트 | 환경 실패 | `TEST_DB_*` MySQL 연결 불가로 기존 context·추천 API 테스트 6건이 시작 전에 실패 |
| 프론트엔드 컴포넌트 테스트 | 미실행 | test 스크립트와 테스트 러너가 없음 |

## 6. 관련 설계 문서

- [백엔드 설계 문서](auth-login-design.md)
- [프론트엔드 설계 문서](../../../FE-branch/feature/auth-login/docs/auth-login-design.md)
