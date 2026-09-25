# Meomuneum Backend

Meomuneum 프로젝트의 서버 애플리케이션입니다. Spring Boot 기반이며 추천 API를 개발 중입니다. 환경별 설정과 추천 결과 저장·조회 기능이 준비되어 있습니다.

## 기술 및 실행 환경

| 항목 | 버전 / 구성                                                             |
| --- |---------------------------------------------------------------------|
| Java | JDK 25                                                              |
| Spring Boot | 4.1.1                                                               |
| Gradle | 9.7.1, 저장소의 Wrapper 사용                                              |
| DB | MySQL 9.7.0, 개발용과 테스트용 분리                                           |
| 주요 의존성 | MVC, JPA, Security, Validation, WebSocket, Actuator, Flyway, Lombok |

JDK 25와 MySQL 9.7.0 서버를 설치합니다. Gradle은 별도로 설치할 필요가 없습니다. 최초 실행에는 Wrapper와 의존성 다운로드를 위한 인터넷 연결이 필요합니다.

```sh
java -version
```

아래 명령은 백엔드 디렉토리에서 실행하며 macOS/Linux의 sh 계열 셸 기준입니다. Windows에서는 `./gradlew`를 `gradlew.bat`으로 바꾸고 환경변수는 사용하는 셸에 맞게 설정하세요.

## DB 준비

MySQL 서버를 실행하고 관리자 계정으로 접속합니다.

```sh
mysql -u root -p
```

개발용과 테스트용 데이터베이스 및 사용자를 만듭니다. 아래 비밀번호는 실제 로컬 비밀번호로 바꾸고 `.env`에도 같은 값을 입력하세요. 기존 DB나 사용자가 있다면 해당 설정을 확인하세요.

```sql
CREATE DATABASE meomuneum_dev CHARACTER SET utf8mb4;
CREATE DATABASE meomuneum_test CHARACTER SET utf8mb4;
CREATE USER 'meomuneum_dev'@'localhost' IDENTIFIED BY 'replace_with_dev_password';
CREATE USER 'meomuneum_test'@'localhost' IDENTIFIED BY 'replace_with_test_password';
GRANT ALL PRIVILEGES ON meomuneum_dev.* TO 'meomuneum_dev'@'localhost';
GRANT ALL PRIVILEGES ON meomuneum_test.* TO 'meomuneum_test'@'localhost';
```

이 권한 예시는 로컬 개발용입니다. 컨테이너 또는 원격 DB를 사용한다면 접속 주소와 사용자 허용 호스트를 해당 환경에 맞게 설정하세요. 테스트에는 반드시 별도 DB를 사용합니다.

## 환경변수 설정

```sh
cp .env.example .env
```

`.env`의 비밀번호를 수정한 뒤 현재 터미널에 환경변수를 불러옵니다. **Spring Boot는 `.env`를 자동으로 읽지 않습니다.** 새 터미널에서는 다시 불러오거나 IDE 실행 설정에 환경변수를 등록하세요.

```sh
set -a
. ./.env
set +a
```

| 프로필 | 환경변수 | 기본값 |
| --- | --- | --- |
| `dev` | `DEV_DB_URL`, `DEV_DB_USERNAME`, `DEV_DB_PASSWORD` | URL: `jdbc:mysql://localhost:3306/meomuneum_dev`, 사용자: `meomuneum_dev`; 비밀번호 필수 |
| `test` | `TEST_DB_URL`, `TEST_DB_USERNAME`, `TEST_DB_PASSWORD` | URL: `jdbc:mysql://localhost:3306/meomuneum_test`, 사용자: `meomuneum_test`; 비밀번호 필수 |
| `prod` | `PROD_DB_URL`, `PROD_DB_USERNAME`, `PROD_DB_PASSWORD` | 모두 필수 |

현재 위치 판정에는 `LOCATION_TOKEN_SECRET`과 `LOCATION_KAKAO_REST_API_KEY`가 필요합니다.
토큰 비밀값은 32바이트 이상이어야 하며 인증 JWT와 별도로 관리합니다. 역지오코딩 연결·응답 제한은
`LOCATION_REVERSE_GEOCODING_CONNECT_TIMEOUT`과 `LOCATION_REVERSE_GEOCODING_READ_TIMEOUT`으로
설정하며 기본값은 각각 2초와 3초입니다.

음성 전사는 `SPEECH_TRANSCRIPTION_PROVIDER`로 제공자를 선택합니다. `dev` 프로필의 기본값은
프론트엔드 흐름 확인용 `stub`이며 `SPEECH_TRANSCRIPTION_STUB_TRANSCRIPT`의 문장을 반환합니다.
운영 기본값은 `unavailable`이고 AI 팀의 실제 계약을 연결하기 전에는 502를 반환합니다.

`.env`는 Git 제외 대상입니다. 실제 비밀번호를 예시 파일이나 YAML에 기록하지 마세요. 운영 환경에서는 배포 환경의 환경변수 또는 비밀값 관리 기능으로 값을 주입하고 `SPRING_PROFILES_ACTIVE=prod`를 설정합니다.

## 설치 및 개발 실행

```sh
# 소스 컴파일 및 리소스 준비
./gradlew classes

# DB 준비 및 환경변수 로딩 후 실행
./gradlew bootRun --args='--spring.profiles.active=dev'
```

기본 서버 주소는 `http://localhost:8080`입니다. 아직 서비스 API가 없으므로 루트 주소가 서비스 화면을 제공하지는 않습니다. Spring Security 기본 설정으로 인증이 요구되거나 기본 로그인 화면이 표시될 수 있습니다. 별도 인증 설정 전에는 실행 로그의 기본 생성 비밀번호를 확인하세요.

## 음악 기록 로컬 개발 프로필

음악 기록용 별도 개발 DB는 `dev,music-record-local` 프로필로 실행합니다.
이 프로필은 Gradle이 준비한 마이그레이션 중 공유 `regions`·채팅 기반 테이블을 먼저 생성하고,
후행 도트·음악 기록 테이블을 생성하고 지도 카탈로그의 도트 1,050개를 적재합니다. 일반 `dev` 프로필은 전체 마이그레이션을 실행합니다.
여러 행정구역에 걸친 도트의 `region_id`는 카탈로그의 첫 지역을 대표 지역으로 사용하며, 사용자에게 표시하는 현재 장소는 별도 위치 판정 결과를 사용합니다.

```sh
set -a
. ./.env
set +a
./gradlew bootRun --args='--spring.profiles.active=dev,music-record-local'
```

`bootRun`은 `prepareMusicRecordLocalMigrations`를 먼저 실행합니다. IDE에서 애플리케이션을 직접 실행할 때는
`./gradlew prepareMusicRecordLocalMigrations`를 먼저 실행하고 두 프로필을 모두 활성화하세요.
임시 프로필에서는 일부 마이그레이션만 사용하므로 JPA의 전체 스키마 검증을 끕니다. 음악 기록 SQL의 Flyway 검증은 유지됩니다.
새 음악 기록 마이그레이션을 추가하면 `build.gradle`의 전용 SQL 목록에도 추가해야 합니다.
이 프로필은 로컬 개발 전용이며 통합 검증이나 배포에 사용하지 않습니다.
기존 `V20260923093759__create_music_record_tables.sql`이 적용된 로컬 DB는 보존하고,
병합된 브랜치의 신규 마이그레이션은 별도 빈 DB에서 검증합니다. 기존 DB의 Flyway 이력을
임의로 삭제하거나 수정하지 마세요.

음악 기록 전용 DB 통합 테스트는 기존 테스트 DB와 분리된 로컬 DB에서
`test,music-record-local` 프로필로 실행합니다. 테스트 DB 자격 증명은 실행 환경의
`TEST_DB_URL`, `TEST_DB_USERNAME`, `TEST_DB_PASSWORD`로 주입합니다.
`./gradlew test`가 필요한 마이그레이션을 먼저 준비합니다. 기본 전체 테스트는
일반 `test` 프로필만 실행합니다. 음악 기록 전용 테스트는 별도의 빈 DB를 지정한 뒤
`MUSIC_RECORD_LOCAL_TESTS=true ./gradlew test --tests '*MusicRecordApiDatabaseIntegrationTest' --tests '*MusicRecordTestProfileIntegrationTest' --tests '*RefreshCsrfProtectionIntegrationTest' --tests '*SessionCookieScopeHttpIntegrationTest'`로 실행합니다.
두 테스트 실행의 Flyway 적용 이력이 다르므로 하나의 DB를 공유하지 마세요.

### 회원가입·음악 기록 격리 로컬 프로필

회원가입 약관 ID 1~6 및 음악 기록을 함께 개발할 때는 기존 DB가 아닌
`meomuneum_music_record_signup_dev`를 사용합니다. 해당 스키마에는 전용 계정
`mm_signup_dev`만 권한을 부여합니다. Git에서 제외되는 `.env.music-record-signup-dev`에
`MUSIC_SIGNUP_DB_URL`, `MUSIC_SIGNUP_DB_USERNAME`, `MUSIC_SIGNUP_DB_PASSWORD`,
`MUSIC_SIGNUP_AUTH_JWT_SECRET`, `MUSIC_SIGNUP_AUTH_JWT_ISSUER`를 별도로 둡니다.
기존 `.env`와 이전 로컬 DB의 값은 변경하지 않습니다.
기존 `.env`의 `AUTH_JWT_SECRET`·`AUTH_JWT_ISSUER`·`AUTH_CORS_ALLOWED_ORIGINS`가
프로필 YAML보다 우선하므로 신규 env 파일을 **나중에** 불러와 이 세 값을 전용 값으로
덮어써야 합니다.

```sh
set -a
. ./.env
. ./.env.music-record-signup-dev
set +a
SERVER_PORT=8082 ./gradlew bootRun --args='--spring.profiles.active=dev,music-record-signup-local'
```

새 프로필은 `prepareMusicRecordSignupLocalMigrations`의 SQL만 실행하며 원본 migration을
수정하지 않습니다. Flyway 전 URL·설정 계정과 실제 연결 스키마·계정을 검사해 로컬 전용
대상이 아니면 실행을 거부합니다. IntelliJ에서 직접 실행하면 위 Gradle 준비 태스크를 먼저
실행하고 프로필 순서를 `dev,music-record-signup-local`로 지정합니다.
프론트엔드는 기존 `.env.local`을 보존하고
`VITE_API_BASE_URL='' VITE_API_PROXY_TARGET=http://localhost:8082 npm run dev -- --port 5176`
으로 새 백엔드에 연결합니다.

약관 1·2는 필수, 3~6은 선택이며 제출한 ID만 동의 이력에 기록합니다. 로컬 보정 SQL은
새 DB에서만 ERD의 필드 길이와 필수 여부·모순된 시드 문구를 맞춥니다. 운영 약관 문구
적용은 별도의 법률·제품 검토가 필요합니다. 실패해도 기존 DB에 `clean`·`repair`·삭제를
실행하지 마세요.

위치 확인 API는 서버에서 Kakao 로컬 REST API로 좌표를 역지오코딩합니다. `.env.example`을
복사한 `.env`에 **Kakao REST API 키**를 `KAKAO_REST_API_KEY`로 설정하고, Kakao Developers에서
해당 앱의 Kakao Maps API 사용을 활성화하세요. 키는 백엔드의 `Authorization: KakaoAK ...`
헤더에만 사용하며 프론트엔드로 전달하지 않습니다. 기존 위치 API의 요청·응답 형식은 유지됩니다.
Kakao 조회가 성공한 시점부터 위치 토큰의 유효시간은 300초입니다.

### 음악 기록 상세·수정 API

인증된 사용자는 `GET /api/v1/music-records/{record_id}`로 본인 기록의 곡,
지도 도트 ID, 시/도·시/군/구, 사용자 장소명, 감정 메모, 생성·수정 시각을 조회합니다.
`PATCH /api/v1/music-records/{record_id}`는 변경된 필드만 받습니다.

```json
{
  "custom_place_name": "범서네 집",
  "emotion_memo": "비 오는 날의 차분함"
}
```

PATCH는 `custom_place_name`과 `emotion_memo`만 받으며 음악·위치·지역·저장 날짜는
수정하지 않습니다. 값이 모두 기존과 같으면 공통 400을 반환하고 DB를 수정하지
않습니다. 변경 시 `updated_at`은 서버 시각으로 설정합니다. 다른 사용자의 기록은
403, 존재하지 않거나 삭제된 기록은 404를 반환합니다.

## 테스트 및 빌드

DB를 준비하고 환경변수를 불러온 상태에서 실행합니다.

```sh
# contextLoads()는 @ActiveProfiles("test")로 테스트 DB를 사용
./gradlew test

# 테스트를 포함한 전체 빌드
./gradlew clean build

# 실행 가능한 JAR 생성: 테스트는 실행하지 않음
./gradlew bootJar
```

테스트 보고서는 `build/reports/tests/test/index.html`에서 확인합니다. 현재 테스트는 Spring 컨텍스트가 시작되는지 확인하며 기능별 테스트는 추후 추가해야 합니다.

JAR 생성 후 다음과 같이 실행합니다. 버전이 변경되면 파일명도 변경됩니다.

```sh
java -jar build/libs/meomuneum-backend-0.0.1-SNAPSHOT.jar --spring.profiles.active=dev
```

## 스키마 관리

Flyway가 활성화되어 있으며 Hibernate는 `ddl-auto: validate`로 설정되어 있습니다. Hibernate가 테이블을 자동 생성하지 않고 엔티티와 DB 스키마의 일치를 검증합니다.

스키마 변경은 `src/main/resources/db/migration/`에 `V1__initial_schema.sql` 같은 버전 마이그레이션을 추가해 관리합니다. 현재 마이그레이션 SQL과 서비스 엔티티는 없습니다. 엔티티 추가 시 대응하는 마이그레이션도 작성하세요. 이미 적용된 마이그레이션은 수정하지 않고 새 버전을 추가합니다.

## 디렉토리 구성

```text
src/main/java/com/muse/meomuneum/   애플리케이션 소스
src/main/resources/                공통 및 dev/test/prod 설정
src/main/resources/db/migration/   Flyway SQL 마이그레이션 위치
src/test/java/com/muse/meomuneum/   테스트
gradle/wrapper/                    Gradle Wrapper
.env.example                      환경변수 예시
```

## 실행 문제 확인

- DB 연결 또는 드라이버 선택 오류: 프로필 지정, DB URL, MySQL 실행 여부를 확인합니다.
- 비밀번호 환경변수 해석 오류: `.env`를 현재 터미널에 불러왔는지 확인합니다.
- 테이블 검증 오류: 필요한 Flyway 마이그레이션이 있는지 확인합니다.
- Java 버전 오류: JDK 25와 `JAVA_HOME` 또는 IDE의 Gradle JVM 설정을 확인합니다.
- 포트 충돌: 기존 서버를 종료하거나 실행 인자에 `--server.port=8081`을 추가합니다.

## 현재 위치 판정 기능

`POST /api/v1/locations/resolve`는 Bearer access token으로 인증된 사용자의 위도·경도와
`accuracy_meters`를 받습니다. 위도·경도의 유효 범위를 검사하고 정확도가 100m를 초과하면
외부 API를 호출하지 않고 `400 Bad Request`를 반환합니다.

허용된 좌표는 카카오 좌표→행정구역 API의 법정동 코드로 변환한 뒤 활성 `SIDO`와 `SIGUNGU`
데이터에 매칭합니다. 응답에는 행정구역과 5분 유효한 `location_resolution_token`만 포함하며
원본 좌표는 DB나 토큰에 저장하지 않습니다. 토큰 검증은 서명, 만료, 발급 대상 사용자와
시·도/시·군·구 클레임을 확인합니다. 역지오코딩 장애나 현재 활성 행정구역 데이터와 매칭되지
않는 결과는 API 설계서에 따라 `502 Bad Gateway`로 반환합니다.

현재 지도 도트 데이터 기반은 이 이슈 범위에 포함되지 않아 `map_dot`은 `null`입니다. 이후 도트
판정 기능이 연결되더라도 행정구역 판정과 위치 토큰 검증 경계는 그대로 재사용할 수 있습니다.

## 행정구역 채팅방 조회와 입장

`GET /api/v1/regions/{region_id}/chat-room`은 사전 생성된 활성 시·군·구 채팅방을 조회합니다.
`POST /api/v1/chat-rooms/{room_id}/members`는 `location_resolution_token`의 사용자와 시·군·구가
대상 방과 일치하는지 확인한 뒤 입장합니다. 같은 사용자가 같은 방에 다시 요청하면 기존 활성
membership을 `200 OK`로 반환하고, 최초 입장은 `201 Created`를 반환합니다.

입장 트랜잭션은 사용자와 관련 채팅방을 비관적 잠금으로 직렬화하며 실제 DB의 사용자별 활성
membership UNIQUE 제약도 함께 적용됩니다. 정원은 활성 membership만 계산합니다. 다른 지역으로
이동할 때는 기존 membership을 먼저 종료하며, 새 방이 가득 차 `409 Conflict`가 발생하더라도 기존
membership 종료를 되돌리지 않습니다.

## 텍스트 음악 추천 기능

`POST /api/v1/recommendations`는 `TEXT`와 STT 전사문인 `VOICE`를 같은 경로로 처리합니다.
같은 소유자의 `conversation_key`에 속한 완료된 이전 요청의 `prompt`를 읽고,
각 요청의 입력 문장은 해당 `recommendation_sessions.prompt`에 따로 저장합니다.
AI 팀 연동 전에는 이 문장들을 합쳐 iTunes Search API에서 직접 검색합니다.
이 임시 검색은 감정·상황을 AI로 해석하지 않으며 긴 대화는 최근 250자만 검색어로 사용합니다.

검색에서 중복을 제거한 1~5곡을 얻으면 모두 저장한 뒤 `201 Created`와
`COMPLETED` 응답으로 반환합니다. 0곡 또는 iTunes 장애는 `503 Service Unavailable`,
요청 제한 시간 초과는 `504 Gateway Timeout`으로 반환합니다. DB 저장 실패는
`500 Internal Server Error`이며 부분 저장은 트랜잭션으로 롤백합니다.
기본 iTunes 요청 제한 시간은 8초이고 `RECOMMENDATION_ITUNES_TIMEOUT`으로 변경할 수 있습니다.
기본 검색 스토어는 `US`이며 `RECOMMENDATION_ITUNES_COUNTRY`로 변경할 수 있습니다.
실제 API 확인 시 `KR` 스토어는 검색 결과가 없었고 `US` 스토어에서는 한국어 곡도 검색됐습니다.
측정 지표 `recommendation.provider.duration`과 `recommendation.request.duration`은
각각 제공자 호출과 전체 요청의 시간·성공·실패·시간 초과를 구분합니다.
첫 추천 결과는 POST 응답으로 받고, GET은 저장된 결과 재조회에 사용합니다.

## 음성 전사 기능

`POST /api/v1/speech-transcriptions`는 `multipart/form-data`의 `audio` 파일을 받습니다.
WebM 또는 MP4만 허용하며 최대 10MB, 최대 60초로 제한합니다. 파일 시그니처와 컨테이너의
재생 시간을 서버에서 검증하며 원본 음성과 transcript를 저장하지 않습니다. 전사 성공 응답의
`data.transcript`는 프론트엔드 입력창에 표시되고 사용자가 확인·수정한 뒤 별도 추천 요청의
`prompt`와 `input_type=VOICE`로 전달됩니다.

AI 팀 연동 전에는 `SpeechToTextProvider`의 개발용 대역을 사용합니다. 실제 AI 연동 시에는
이 인터페이스의 운영 어댑터를 추가하고 URL·인증 정보·제한 시간을 환경 변수로 주입합니다.

전사 실패 응답은 공통 `{ message, data }` 형식을 유지하며 `data`는 `null`입니다. 별도의
Custom Code를 추가하지 않고 다음 HTTP 상태를 프론트엔드의 복구 동작 판별 코드로 사용합니다.

| 상태 | 조건 | 클라이언트 처리 |
| --- | --- | --- |
| `400 Bad Request` | 파일 누락, 지원하지 않는 형식, 손상된 파일, 60초 초과 | 다시 녹음 |
| `413 Payload Too Large` | 10MB 초과 | 더 짧게 다시 녹음 |
| `502 Bad Gateway` | 전사 서비스 장애, 전사 결과 없음 | 동일 녹음 재시도 또는 텍스트 입력 |
| `504 Gateway Timeout` | 전사 서비스 제한 시간 초과 | 동일 녹음 재시도 또는 텍스트 입력 |
| `500 Internal Server Error` | 분류되지 않은 서버 오류 | 재시도 또는 텍스트 입력 |

전사 API는 음성과 transcript를 저장하지 않으며 추천 서비스나 추천 저장소를 호출하지 않습니다.
전사 성공 후 사용자가 transcript를 확인·수정하고 별도의 추천 요청을 보내야만 추천 세션과
추천곡이 저장됩니다. 따라서 전사 실패 응답에서는 추천 세션, prompt, 추천곡 저장이 발생하지
않습니다.

## 행정구역 채팅방 사전 준비

채팅방은 활성 시·군·구(`SIGUNGU`)마다 하나씩 미리 준비합니다. 초기 지역 데이터는
[행정표준코드관리시스템](https://www.code.go.kr/stdcode/regCodeL.do)의 법정동 코드 전체자료 중
2026-09-17 스냅샷을 사용합니다. 2026-09-23에 내려받은 원본 ZIP의 SHA-256은
`44b96f4a86ad102057463a05aae8842f1d706d3e9e69d2dfc409023bf75ca56b`입니다.

원본에서 `존재` 상태인 코드만 사용하며, 시·도 코드는 앞 2자리, 시·군·구 코드는 앞 5자리로
정규화했습니다. 세종특별자치시는 공식 코드 `3611000000`을 시·도 `36`과 시·군·구 `36110`으로
각각 표현합니다. 이 기준으로 활성 시·도 16개와 시·군·구 269개를 고정 데이터 Migration에
포함했습니다.

Migration은 지역 코드를 기준으로 갱신하고 초기 채팅방 데이터를 생성합니다. 애플리케이션 시작 시
`ChatRoomProvisioningService`는 활성 시·군·구 중 기존 방이 없는 지역만 보충한 뒤 누락된 방이
없는지 다시 검증합니다. 다른 데이터·제약 오류나 검증 후 누락이 있으면 시작을 실패시켜 불완전한
준비 상태를 숨기지 않습니다. 사용자 입장 서비스는 채팅방을 생성하지 않습니다.
