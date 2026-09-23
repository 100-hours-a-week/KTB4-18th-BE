# 지도 도트 API 리뷰 반영 설계

## 목적

PR #45의 리뷰 요청 사항 중 도트 ID 안정성, 프로젝트 README 복구, 오래된 검증 산출물 제거,
지도 조회 API의 공개 범위 제한을 반영한다. ERD 컬럼과 DB 스키마는 추가하거나 변경하지 않는다.

## 범위

### 포함

- `map_dot_id`를 JSON 배열 순서가 아닌 지도 코드에서 결정하는 방식으로 변경
- 삭제된 프로젝트 기본 `README.md` 복구
- 이전 코드와 맞지 않는 Postman 응답 덤프와 컬렉션 제거
- 익명 접근을 `GET /api/v1/map-dots`로만 제한
- 변경된 동작을 검증하는 단위·통합 테스트 보완

### 제외

- `album_cover_url`, `latest_recorded_at`의 실제 기록 데이터 조회
- 지도 도트·음악 기록 관련 DB 테이블 또는 컬럼 추가
- 도트 선택, 확대, 현재 위치 기반 도트 생성

`album_cover_url`과 `latest_recorded_at`은 별도 작업에서 실제 기록 생성 흐름과 함께 구현한다.
기존 `recommendation_sessions.map_dot_id`에 어떤 시점에 어떤 도트를 저장할지 먼저 확정해야 한다.

## 결정 1 도트 ID와 코드의 결정 규칙

### 결정

`code`를 지도 도트의 변경 불가능한 기준 식별자로 사용하고, `map_dot_id`는 코드 마지막 네 자리에서
계산한다. JSON에 별도 `map_dot_id` 필드를 추가하지 않고, `map_dots` 테이블도 만들지 않는다.

```text
KR-COAST-0001 -> map_dot_id 1
KR-COAST-0104 -> map_dot_id 104
KR-COAST-1050 -> map_dot_id 1050
```

코드와 ID의 표기 규칙은 다음과 같다.

```text
code       = KR-COAST-%04d
map_dot_id = code의 마지막 네 자리 정수값
```

### 이유

현재 구현의 `index + 1`은 JSON 배열이 재정렬되거나 중간에 구역이 삽입될 경우 같은 도트에 다른 ID를
부여한다. 반면 코드에서 계산한 ID는 배열 위치와 무관하다. 기존 `recommendation_sessions.map_dot_id`
컬럼의 `BIGINT` 타입 및 API의 `long map_dot_id`도 그대로 유지할 수 있다.

### 카탈로그 관리 규칙

- 코드 형식은 `KR-COAST-` 뒤에 정확히 네 자리 숫자를 둔다.
- 숫자는 1부터 1050까지의 양의 정수여야 한다.
- 코드와 숫자 ID는 모두 중복될 수 없다.
- 이미 배포된 코드의 숫자 부분은 변경·재사용하지 않는다.
- 새 구역을 추가할 때는 기존 코드의 순서를 바꾸지 않아도 되는 새로운 번호를 부여한다.

### 구현 위치

- `src/main/java/com/muse/meomuneum/map/catalog/MapZoneCatalog.java`
  - `mapDots()`에서 배열 인덱스를 사용하지 않는다.
  - 각 `MapZone.code()`를 검증하고 마지막 네 자리를 `long`으로 변환해 `MapDot`을 생성한다.
  - 시작 시 코드 형식, 숫자 범위, 코드 중복, 파생 ID 중복을 검증한다.

## 결정 2 README 복구

### 결정

`README.md`는 `origin/dev`의 프로젝트 기본 문서를 기준으로 복구한다.

### 이유

README는 지도 기능의 임시 검증 문서가 아니라 저장소의 실행·환경 설정 안내 문서다. 지도 관련 원문
로그를 정리하는 것과 프로젝트 기본 문서를 삭제하는 것은 분리해야 한다.

### 구현 위치

- `README.md`
  - `origin/dev` 버전을 복구한다.
  - 이번 지도 기능을 설명하기 위한 검증 원문이나 민감한 개발 환경 값은 추가하지 않는다.

## 결정 3 오래된 검증 산출물 제거

### 결정

PR과 저장소에서 이전 코드(`KR-COAST-5X5-*`) 또는 존재하지 않는 API(`/api/v1/map-zones`)를 참조하는
Postman 응답 덤프, 헤더 파일, 컬렉션을 제거한다. 대용량 JSON 응답 원문도 PR에 포함하지 않는다.

### 이유

오래된 원문은 현재 API의 검증 근거가 될 수 없고, 대용량 파일은 코드 리뷰를 어렵게 한다. 검증은
자동화 테스트 코드와 PR 본문의 실행 명령·결과로 남긴다.

### 제거 대상

- `api-test/postman/map-dots/`
- `api-test/postman/map-zones/`
- `api-test/verification-2026-09-23/raw/`

PR 본문에서 삭제된 `POST_MERGE_TEST_AND_BROWSER_LOG.md` 링크도 함께 제거하거나, 실제로 존재하는
검증 방법으로 바꾼다.

### PR 검증 자료 처리 기준

PR에는 전체 도트 1,050개가 담긴 JSON 응답 원문, 응답 헤더 덤프, 이전 Postman 실행 결과를 포함하지
않는다. 특히 `KR-COAST-5X5-*` 코드나 존재하지 않는 `/api/v1/map-zones` 경로가 포함된 자료는 최신
코드의 검증 근거가 될 수 없으므로 모두 제거한다.

검증 근거는 아래의 자동화 테스트와 실행 결과로 대체한다.

- `GET /api/v1/map-dots`의 `200 OK` 응답
- ETag가 일치할 때의 `304 Not Modified` 응답
- 도트 개수 1,050개
- 첫 도트의 `map_dot_id: 1`, `code: KR-COAST-0001`
- 코드에서 파생한 도트 ID 규칙
- 비로그인 `POST /api/v1/map-dots` 차단

Postman 컬렉션은 팀에서 재현 가능한 수동 검증에 실제로 사용한다는 합의가 있을 때만 현재 API 계약에
맞춘 요청 정의 파일만 남긴다. 응답 JSON과 헤더 파일은 커밋하지 않는다.

PR 본문은 제거된 검증 로그 파일을 연결하지 않고, 아래 명령의 실제 실행 결과만 간결하게 기록한다.

```text
./gradlew clean test --no-daemon
./gradlew bootJar --no-daemon
```

## 결정 4 지도 API의 익명 접근 범위

### 결정

익명 접근은 현재 제공하는 목록 조회 API인 `GET /api/v1/map-dots`에만 허용한다.

```java
.requestMatchers(HttpMethod.GET, "/api/v1/map-dots").permitAll()
```

`/api/v1/map-dots/**` 와일드카드와 HTTP 메서드 없는 `permitAll()` 설정은 사용하지 않는다.

### 이유

향후 상세 조회·생성·수정·삭제 API가 같은 경로 아래에 생겨도 자동으로 공개되지 않게 한다. 새 API는
요구사항에 맞춰 인증 정책을 명시적으로 추가해야 한다.

### 구현 위치

- `src/main/java/com/muse/meomuneum/global/config/SecurityConfig.java`
  - 현재의 지도 API 경로 전체 공개 matcher를 GET 단일 경로 matcher로 교체한다.

## 테스트 설계

### 카탈로그 단위 테스트

- `KR-COAST-0001`, `KR-COAST-0104`, `KR-COAST-1050`이 각각 1, 104, 1050으로 변환되는지 검증한다.
- 카탈로그 배열 순서가 바뀌어도 동일한 코드의 `map_dot_id`가 유지되는지 검증한다.
- 코드 형식 오류, 숫자 범위 오류, 중복 코드, 중복 파생 ID에서 애플리케이션 시작 검증이 실패하는지 확인한다.

### 컨트롤러 테스트

- `GET /api/v1/map-dots` 응답의 `map_dot_id`와 `code`가 위 규칙을 따르는지 검증한다.
- ETag의 정상 응답과 `304 Not Modified` 동작이 유지되는지 검증한다.

### 보안 통합 테스트

- 비로그인 `GET /api/v1/map-dots`가 `200 OK`인지 검증한다.
- 비로그인 `POST /api/v1/map-dots`가 인증 단계에서 차단되는지 검증한다.

## 호환성 및 롤백

- API 경로와 응답 필드명은 변경하지 않는다.
- `map_dot_id`의 값은 현재 코드 숫자와 동일하게 유지한다.
- DB 마이그레이션과 데이터 백필은 없다.
- 문제 발생 시 `SecurityConfig`와 카탈로그 ID 파생 로직을 이전 커밋으로 되돌리면 된다.

## 완료 기준

- `README.md`가 복구되어 있다.
- PR diff에 오래된 `5X5` 코드, `/api/v1/map-zones`, 대용량 응답 덤프가 없다.
- 동일한 `code`는 JSON 정렬과 관계없이 동일한 `map_dot_id`를 반환한다.
- 익명 사용자는 지도 목록만 조회할 수 있고, 지도 경로의 쓰기 요청은 인증 없이 통과하지 않는다.
- `./gradlew clean test --no-daemon` 및 `./gradlew bootJar --no-daemon`이 통과한다.
