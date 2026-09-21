# 로그인 API 미검증 항목 테스트 설계·기획서

## 1. 문서 목적

`POST /api/v1/auth/login` 구현에서 아직 런타임 또는 API 계층까지 완전히 확인하지 못한 항목을 자동 테스트로 보완한다.

이 문서는 로그인 API만 다룬다. 회원가입, 토큰 재발급, 로그아웃, 비밀번호 변경·재설정과 다른 도메인 API는 범위에 포함하지 않는다.

## 2. 현재 검증 상태

| 항목 | 현재 상태 | 보완 필요 여부 |
|---|---|---|
| 유효 자격 증명 로그인 | 격리 DB 런타임 테스트 통과 | Cookie 헤더 자동 검증 보완 |
| 누락 요청값 | 런타임 테스트 통과 | 형식 오류 전체 케이스 보완 |
| 미존재·탈퇴 사용자 은닉 | 런타임 테스트 통과 | API 응답 완전 동일성 자동 검증 보완 |
| 10회 실패 후 잠금 | 런타임 테스트 통과 | 성공 후 잠금 이력 초기화 API 흐름 보완 |
| 5→10→20→40분 증가 | 저장소 단위 테스트 통과 | 유지 |
| refresh token Cookie | 컨트롤러 단위 테스트 통과 | 실제 HTTP 헤더 자동 검증 보완 |
| 로그인 URL 비인증 접근 | 런타임 성공으로 확인 | 보안 필터 포함 자동 검증 보완 |
| 예상하지 못한 오류의 500 공통 응답 | 미검증 | 신규 테스트 필요 |

## 3. 목표와 완료 기준

### 목표

- 로그인 API의 성공·입력 오류·인증 실패·잠금·예외 응답이 명세와 일치함을 자동 검증한다.
- 실제 대기 없이 시간 의존 잠금 정책을 결정적으로 검증한다.
- access token과 refresh token 원문이 테스트 로그·리포트에 노출되지 않도록 한다.

### 완료 기준

- 로그인 전용 테스트가 모두 통과한다.
- `500` 공통 응답, 실제 `Set-Cookie`, 보안 필터를 통과한 비인증 접근이 자동 테스트된다.
- 성공 로그인 뒤 실패 이력과 잠금 단계가 초기화됨을 서비스 흐름에서 증명한다.
- 테스트는 외부 DB·실제 이메일·실제 시간 대기를 요구하지 않는다.

## 4. 테스트 전략

### 4.1 HTTP API 계층: MockMvc

컨트롤러, 검증 애너테이션, 예외 처리, JSON 직렬화, Cookie 헤더를 한 요청으로 검증한다.

- 대상: `LoginController`, `LoginExceptionHandler`
- 방식: `@WebMvcTest` 또는 보안 설정을 명시적으로 포함한 MockMvc 테스트
- 의존성: `LoginService`는 Mock으로 대체한다.
- 장점: DB 없이 빠르고, 응답 Body와 헤더를 정확히 검사할 수 있다.

### 4.2 보안 설정 통합 테스트

Spring Security 필터 체인을 실제로 포함해 Authorization 헤더 없이 로그인 요청이 차단되지 않는지 확인한다.

- 대상: `LoginSecurityConfiguration`
- 방식: Security 필터가 활성화된 MockMvc 테스트
- 성공 자격 증명 경로에만 `LoginService` Mock을 사용한다.
- 판정: 인증 헤더가 없어도 컨트롤러까지 진입해 `200` 또는 자격 증명에 따른 `401`이 반환되어야 한다. Spring Security 기본 `401 unauthorized` 또는 로그인 화면 리다이렉트가 나오면 실패다.

### 4.3 서비스·잠금 정책 테스트

`MutableClock`으로 현재 시각을 제어해 대기 시간 없이 잠금 만료와 초기화를 검증한다.

- 대상: `LoginService`, `LoginAttemptStore`
- 방식: 단위 테스트에서 `Clock` 대신 변경 가능한 테스트 Clock 주입
- 의존성: `UserRepository`, `PasswordEncoder`, `TokenIssuer`를 Mock으로 대체한다.
- 장점: 실제 5분·10분·20분·40분을 기다리지 않는다.

## 5. 상세 테스트 케이스

### A. 성공과 응답 계약

| ID | 조건 | 요청 또는 준비 | 기대 결과 |
|---|---|---|---|
| LOGIN-API-01 | 활성 사용자, 비밀번호 일치 | 유효 이메일·비밀번호 | `200`, `message=login success` |
| LOGIN-API-02 | 성공 응답 Body | LOGIN-API-01 응답 | `data` 키는 `access_token`, `expires_in` 두 개만 존재 |
| LOGIN-API-03 | refresh token Cookie | LOGIN-API-01 응답 헤더 | `Set-Cookie`에 `refresh_token`, `HttpOnly`, `Secure`, `SameSite=Lax`, `Path=/api/v1/auth` 포함 |
| LOGIN-API-04 | refresh token Body 미포함 | LOGIN-API-01 응답 Body | `refresh_token` 문자열과 토큰 원문이 없음 |

### B. 요청값 검증

| ID | 요청 Body | 기대 결과 |
|---|---|---|
| LOGIN-API-05 | `{}` | `400 invalid request` |
| LOGIN-API-06 | `{"email":null,"password":"password"}` | `400 invalid request` |
| LOGIN-API-07 | `{"email":"","password":"password"}` | `400 invalid request` |
| LOGIN-API-08 | `{"email":"not-an-email","password":"password"}` | `400 invalid request` |
| LOGIN-API-09 | `{"email":"user@example.com","password":""}` | `400 invalid request` |
| LOGIN-API-10 | JSON 문법 오류 | `400 invalid request` |

### C. 인증 실패 은닉

| ID | 조건 | 기대 결과 |
|---|---|---|
| LOGIN-API-11 | 존재하지 않는 이메일 | `401 invalid credentials` |
| LOGIN-API-12 | 탈퇴 사용자(`deleted_at` 존재) | `401 invalid credentials` |
| LOGIN-API-13 | 비밀번호 불일치 | `401 invalid credentials` |
| LOGIN-API-14 | LOGIN-API-11과 12 비교 | HTTP 상태와 JSON Body가 완전히 동일 |
| LOGIN-API-15 | 실패 응답 | access/refresh token 없음 |

### D. 잠금과 초기화

| ID | 시나리오 | 기대 결과 |
|---|---|---|
| LOGIN-LOCK-01 | 같은 활성 사용자 비밀번호 10회 불일치 | 10번째 이후 첫 5분 잠금 |
| LOGIN-LOCK-02 | 첫 잠금 중 올바른 비밀번호 | `401 invalid credentials`, 토큰·Cookie 미발급 |
| LOGIN-LOCK-03 | 첫 잠금 만료 후 10회 불일치 | 두 번째 잠금은 10분 |
| LOGIN-LOCK-04 | 첫 잠금 만료 후 성공 로그인 | 실패 횟수와 잠금 단계 전체 초기화 |
| LOGIN-LOCK-05 | LOGIN-LOCK-04 뒤 다시 10회 불일치 | 새 첫 잠금은 5분 (10분이 아님) |
| LOGIN-LOCK-06 | 이후 10회 단위 실패 반복 | 5분 → 10분 → 20분 → 40분 |

### E. 보안과 예외

| ID | 조건 | 기대 결과 |
|---|---|---|
| LOGIN-SEC-01 | Authorization 헤더 없이 유효 로그인 요청 | `200` 또는 서비스 결과에 따른 응답. Security 기본 인증 차단이 아님 |
| LOGIN-ERR-01 | `LoginService`가 예상치 못한 `RuntimeException` 발생 | `500`, 공통 JSON 형식 |
| LOGIN-ERR-02 | `InvalidCredentialsException` 발생 | `401 invalid credentials`, `500`으로 변환되지 않음 |

## 6. 구현 계획

### 1단계: API 응답 계약 테스트 추가

파일 후보: `src/test/java/com/muse/meomuneum/feature/auth/login/controller/LoginControllerWebMvcTest.java`

- 성공 JSON 구조와 Cookie 헤더를 검증한다.
- 입력 오류 요청들을 parameterized test로 작성한다.
- `LoginService` Mock이 예외를 던질 때 401·500 응답을 각각 검증한다.

### 2단계: Security 필터 포함 테스트 추가

파일 후보: `src/test/java/com/muse/meomuneum/feature/auth/login/config/LoginSecurityConfigurationTest.java`

- 실제 `LoginSecurityConfiguration`을 포함한다.
- Authorization 헤더 없이 POST 요청을 보낸다.
- 로그인 외 URL을 새로 테스트하거나 구현하지 않는다.

### 3단계: 성공 후 잠금 초기화 서비스 테스트 추가

파일 후보: `src/test/java/com/muse/meomuneum/feature/auth/login/service/LoginServiceTest.java`

- 기존 테스트의 `Clock.systemUTC()`를 `MutableClock`으로 교체한다.
- 10회 실패, 5분 경과, 성공, 재실패 10회를 순서대로 실행한다.
- 마지막 잠금이 5분인지 확인한다.

### 4단계: 테스트 실행과 증적 작성

```bash
./gradlew test --tests 'com.muse.meomuneum.feature.auth.login.*' --no-daemon
./gradlew bootJar --no-daemon
```

- Gradle 테스트 리포트와 테스트 콘솔 결과를 남긴다.
- 토큰·Cookie 값은 출력하지 않고 키 이름과 속성만 기록한다.
- 외부 DB를 사용하지 않는 테스트를 우선한다. 런타임 HTTP 재검증이 필요하면 기존과 같이 테스트 전용 임시 DB만 사용하고 즉시 삭제한다.

## 7. 산출물

- 로그인 API WebMvc 테스트
- 로그인 보안 설정 테스트
- 성공 후 잠금 초기화 서비스 테스트
- 갱신된 런타임/자동 테스트 리포트

## 8. 제외 범위

- 다른 API의 인증 인가 정책 검증
- refresh token 재발급·저장·폐기 로직
- 로그아웃과 Cookie 삭제
- 회원가입 및 비밀번호 재설정
- 이메일 전송 또는 계정 잠금 알림
