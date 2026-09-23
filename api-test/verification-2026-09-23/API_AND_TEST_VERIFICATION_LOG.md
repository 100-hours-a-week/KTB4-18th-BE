# 전체 테스트 및 API 검증 원문 로그

검증 대상은 `feature/main-map`이며, 실행 시점은 2026-09-23이다. 기존 공유 테스트 DB의 Flyway 이력이 현재 마이그레이션과 달라, 별도 로컬 DB `meomuneum_test_mainmap_verify_20260923`에서 전체 통합 테스트와 HTTP 검증을 수행했다.

토큰, 세션 쿠키, CSRF 토큰과 검증용 비밀번호는 보안상 `[REDACTED]`로 대체했다. 그 밖의 HTTP 상태·응답 메시지·비민감 응답 데이터는 실제 출력값이다.

## 전체 테스트 원문

### 첫 전체 실행

명령:

```bash
./gradlew test bootJar --no-daemon
```

출력:

```text
> Task :bootJar
> Task :test

MeomuneumBackendApplicationTests > contextLoads() FAILED
    Caused by: org.flywaydb.core.internal.exception.sqlExceptions.FlywaySqlUnableToConnectToDbException

91 tests completed, 13 failed

> Task :test FAILED
BUILD FAILED in 13s
```

기본 `meomuneum_test` 계정으로 DB에 연결할 수 없어서 DB 의존 통합 테스트 13개가 실행 전 중단됐다. `bootJar` 태스크는 이 실행에서 성공했다.

### 권한 있는 로컬 계정 재실행

명령:

```bash
TEST_DB_USERNAME='root' TEST_DB_PASSWORD='' ./gradlew test --no-daemon
```

출력:

```text
> Task :test

MeomuneumBackendApplicationTests > contextLoads() FAILED
    Caused by: org.flywaydb.core.api.exception.FlywayValidateException

Migration checksum mismatch for migration version 1
-> Applied to database : 2071923509
-> Resolved locally    : -890532579
Detected applied migration not resolved locally: 20260919234000.

91 tests completed, 13 failed

> Task :test FAILED
BUILD FAILED in 8s
```

공유 테스트 DB의 오래된 Flyway 이력 문제이며, 기존 DB를 repair하거나 수정하지 않았다.

### 격리 DB 전체 재실행

명령:

```bash
TEST_DB_URL='jdbc:mysql://localhost:3306/meomuneum_test_mainmap_verify_20260923' \
TEST_DB_USERNAME='root' TEST_DB_PASSWORD='' \
./gradlew test --no-daemon
```

출력 원문:

```text
> Task :compileJava UP-TO-DATE
> Task :processResources UP-TO-DATE
> Task :classes UP-TO-DATE
> Task :compileTestJava UP-TO-DATE
> Task :processTestResources NO-SOURCE
> Task :testClasses UP-TO-DATE
> Task :test

BUILD SUCCESSFUL in 11s
4 actionable tasks: 1 executed, 3 up-to-date
```

### 프론트엔드 전체 검증

명령:

```bash
npm run test && npm run lint && npm run build
```

출력 원문:

```text
> meomuneum-frontend@0.0.0 test
> vitest run

Test Files  3 passed (3)
     Tests  7 passed (7)

> meomuneum-frontend@0.0.0 lint
> eslint .

> meomuneum-frontend@0.0.0 build
> tsc -b && vite build

✓ 556 modules transformed.
✓ built in 378ms
```

## 실제 HTTP API 검증 원문

검증 서버는 격리 DB를 바라보는 `http://127.0.0.1:8082`이며, 개발 프로필의 STT 대역과 추천 제공자를 사용했다.

| API | 실제 결과 | 판정 |
| --- | --- | --- |
| `GET /api/v1/map-dots` | `200`, 1,050개 | 성공 |
| `GET /api/v1/map-dots` with `If-None-Match` | `304` | 성공 |
| `GET /api/v1/auth/token/csrf` | `200` | 성공 |
| `POST /api/v1/auth/login` | `200` | 성공 |
| `POST /api/v1/auth/token/refresh` | `403`, 이후 새 세션으로 `401` | 실패, 아래 이슈 참고 |
| `POST /api/v1/auth/logout` | `204` | 성공 |
| `POST /api/v1/recommendations` | `201` | 성공 |
| `GET /api/v1/recommendations/{id}` | `200` | 성공 |
| `POST /api/v1/speech-transcriptions` | `200` | 성공 |

### 지도 도트 API

```text
HTTP/1.1 200
ETag: "2026-09-07"
```

응답 원문은 99,744 바이트이며 SHA-256은 다음과 같다.

```text
150a3752e1f737a461ec4e78af252ce0f87b755069d6bb52504b78e98a554735
```

전체 1,050개 항목 원문은 기존 [map-dots 200 응답 파일](../postman/map-dots/001-map-dots-200.response.json)에 보관되어 있다. 이번 HTTP 응답의 확인 값은 다음과 같다.

```json
{
  "message": "map dots retrieved",
  "itemCount": 1050,
  "first": {
    "map_dot_id": 1,
    "code": "KR-COAST-5X5-0001",
    "album_cover_url": null,
    "latest_recorded_at": null
  },
  "last": {
    "map_dot_id": 1050,
    "code": "KR-COAST-5X5-1050",
    "album_cover_url": null,
    "latest_recorded_at": null
  }
}
```

조건부 요청 원문:

```text
GET /api/v1/map-dots
If-None-Match: "2026-09-07"

HTTP/1.1 304
```

### 인증 API

```json
GET /api/v1/auth/token/csrf
HTTP/1.1 200
{
  "message": "csrf token issued",
  "data": { "csrf_token": "[REDACTED]" }
}
```

```json
POST /api/v1/auth/login
{ "email": "api.verify@example.test", "password": "[REDACTED]" }

HTTP/1.1 200
{
  "message": "login success",
  "data": { "access_token": "[REDACTED]", "expires_in": 3600 }
}
```

```text
POST /api/v1/auth/logout
HTTP/1.1 204
```

토큰 갱신의 실제 흐름은 아래와 같았다.

```json
POST /api/v1/auth/token/refresh
X-CSRF-TOKEN: [REDACTED]
Cookie: [REDACTED]

HTTP/1.1 403
{ "message": "request rejected", "data": null }
```

로그인 이후 새 CSRF 세션 쿠키를 반영해 다시 호출한 결과도 다음과 같았다.

```json
HTTP/1.1 401
{ "message": "invalid refresh token", "data": null }
```

### 추천 API

```json
POST /api/v1/recommendations
{
  "input_type": "TEXT",
  "trigger_type": "CHATBOT",
  "conversation_key": "11111111-1111-1111-1111-111111111111",
  "prompt": "rainy drive music"
}

HTTP/1.1 201
{
  "message": "recommendation completed",
  "data": {
    "recommendation_id": 22,
    "status": "COMPLETED",
    "conversation_key": "11111111-1111-1111-1111-111111111111",
    "items_count": 5
  }
}
```

```json
GET /api/v1/recommendations/22

HTTP/1.1 200
{
  "message": "recommendation retrieved",
  "data": {
    "recommendation_id": 22,
    "status": "COMPLETED",
    "items_count": 5
  }
}
```

생성·조회 응답 모두 `recommendation_id=22`, `COMPLETED`, 5개 음악 항목으로 일치했다.

### 음성 전사 API

```text
POST /api/v1/speech-transcriptions
Authorization: Bearer [REDACTED]
Content-Type: multipart/form-data
audio: 30초 WebM 테스트 바이트

HTTP/1.1 200
```

```json
{
  "message": "speech transcription completed",
  "data": {
    "transcript": "비 올 때 듣기 좋은 노래를 추천해줘"
  }
}
```

## 확인이 필요한 이슈

`POST /api/v1/auth/token/refresh`의 정상 로그인 후 갱신 흐름은 실제 HTTP에서 성공하지 않았다. 로그인 응답에는 `refresh_token`만 설정되고 세션 식별자 갱신을 반영하는 `JSESSIONID` 응답 쿠키가 없었다. 기존 세션·CSRF 값으로 갱신하면 `403 request rejected`, 새 CSRF 세션을 사용하면 refresh session 상태를 찾지 못해 `401 invalid refresh token`이 반환됐다.

전체 테스트는 통과하지만 현재 테스트는 CSRF 통과 후 **refresh session 상태가 없는** `401`까지만 검증한다. 실제 로그인 → 갱신 성공 흐름을 재현하는 통합 테스트를 추가하고, 세션 ID 갱신 후 클라이언트에 전달되는 쿠키 흐름을 수정해야 한다.
