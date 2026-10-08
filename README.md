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
  "music": {
    "provider": "ITUNES",
    "external_music_id": "456"
  }
}
```

PATCH는 `music`, `custom_place_name`, `emotion_memo`를 각각 선택적으로 받습니다.
음악 교체 시 위치·지역·장소명·메모·생성 시각은 그대로 보존됩니다. 다른 곡이면
iTunes에서 메타데이터를 확인한 뒤 음악과 기록을 트랜잭션으로 갱신합니다. 현재 곡과
같은 곡만 보내면 DB 쓰기 없이 성공하고 `updated_at`도 유지합니다. 변경 시각은 서버가
설정합니다. 다른 사용자의 기록은 403, 존재하지 않거나 삭제된 기록과 iTunes에서 찾지
못한 곡은 404를 반환합니다. [OpenAPI 계약](api/music-record-update.openapi.yaml)에
요청·응답을 기록했습니다.

## 테스트 및 빌드

### 지역 채팅 메시지·자동 제재 저장 기반 (#139)

`ChatMessageStorageService.store`는 호출자가 권한·내용 검사를 마친 정상 메시지를 작성자·방·본문·
client ID·서버 UTC 전송 시각과 함께 저장합니다. 공개 API·WebSocket·탐지·참여 구간별 조회는 후행 작업입니다.
사용자 행 잠금과 실제 `UNIQUE(user_id, client_message_id)`로 동시 재시도 중복을 막고,
같은 방·내용의 재시도에는 기존 ID와 `created=false`를 반환합니다. 후행 전파는 신규 저장 결과에만 적용합니다.
다른 방·내용의 ID 재사용은 거절합니다. ID는 대소문자를 구분하고 작성자별로 구분합니다.

본문은 공백만으로 구성될 수 없고 최대 300 Unicode code point, client ID는 공백이 아닌 최대 100자이며
초과분을 절단하지 않습니다. 시각은 UTC 마이크로초 정밀도로 저장합니다.
`findUnexpiredById`는 보관 기간 필터만 제공하므로 참여 구간 권한 검증 없이 공개 API에서 사용하면 안 됩니다.
물리 삭제 후에는 client ID 기록도 없어져 무기한 멱등성은 제공하지 않습니다.

`ChatBanStorageService.storeAutomaticBan`은 `PROFANITY`·`OBSCENITY` 사유와 시작·7일 뒤 만료 시각을
저장합니다. 시스템 제재의 등록자 FK는 null이며 메시지 본문·탐지 개인정보를 제재 사유에 넣지 않습니다.
동시 제재·재시도는 기존 활성 제재를 반환하고 만료를 연장하지 않습니다. `hasActiveBan`은 만료 시각부터
false가 되며 별도의 삭제 실행을 기다리지 않습니다. 메시지 삭제와 제재 이력은 독립적입니다.

메시지는 전송 시각부터 정확히 24시간에 유효 조회에서 제외합니다. `ChatMessageCleanupJob`은 UTC 매 정시에
만료 행을 트랜잭션으로 물리 삭제합니다. 정상 운영 시 전송 후 최대 25시간 내 삭제를 목표로 합니다.
실패 시 롤백하고 다음 정시 실행에서 남은 만료 행을 다시 처리하며, 조회 만료는 삭제 성공 여부와 무관합니다.
`chat_message_cleanup_success`의 삭제 건수와 `chat_message_cleanup_failed` 이벤트를 감시합니다.
실패 로그는 예외 클래스만 기록하며 SQL·메시지 본문을 남기지 않습니다.
`CHAT_MESSAGE_CLEANUP_ENABLED`는 기본 true이고 통제된 유지보수·테스트에만 false로 설정합니다.
재활성화 후 다음 정시 실행으로 재처리하거나 신뢰된 서버 내부에서 `deleteExpiredMessages()`를 호출합니다.
관리자 삭제 REST API는 제공하지 않습니다.

적용·복구 순서는 다음과 같습니다.

1. 대상 DB와 기존 Flyway 이력을 확인합니다. 기존 Migration은 변경하지 않습니다.
2. 기존 users·chat_rooms 생성 후 `V20261007205113__create_chat_room_messages_table.sql`,
   `V20261007205453__create_chat_bans_table.sql`을 순서대로 적용합니다. 기존 테이블·데이터를 수정하지 않습니다.
3. 메시지 PK·유일 키·작성자/방 FK·만료 인덱스, 제재 FK·기간/사유 CHECK·만료 인덱스와
   Hibernate `ddl-auto=validate` 시작을 확인한 뒤 애플리케이션을 배포합니다. DB/JDBC 시간대는 UTC로 맞춥니다.
4. 테스트 데이터로 동시 저장·만료 경계·물리 삭제를 확인하고 실제 정시 성공 로그를 확인합니다.
5. MySQL DDL 실패 시 실제 테이블·Flyway 실패 이력을 확인하고 보정 Migration/승인된 복구 절차를 사용합니다.
   자동 `clean`·무조건적인 `repair`·기존 파일 변경으로 우회하지 않습니다.
6. 앱 복구 시 삭제 작업을 일시 중단하고 직전 검증 버전으로 복구하되 새 테이블·진행 중인 제재는 보존합니다.
   실제 삭제된 메시지는 앱 롤백으로 복구되지 않습니다. 후행 채팅이 배포된 뒤에는 제재를 검사하지 않는
   구버전으로 되돌리기 전에 채팅 진입을 차단해야 합니다.

제재 만료 후 이력 보관 기간과 로그·백업 사본의 보존·삭제 정책은 별도 결정 대상입니다.
이 메시지 삭제 작업이 백업까지 삭제한다고 간주하지 않습니다. 두 음악 기록 격리 로컬 프로필도
신규 Migration을 포함하므로 IDE 실행 전 해당 `prepareMusicRecord*LocalMigrations`를 실행하세요.

대상 통합 테스트는 Docker의 별도 MySQL 9.7.0에서 Flyway·JPA 매핑·동시 저장·DB 제약·24시간/7일 경계·
물리 삭제 후 제재 유지와 삭제 실패 후 재실행을 검증합니다. Docker가 없어 스킵되면 완료로 간주하지 않습니다.

```sh
./gradlew spotlessCheck checkstyleMain checkstyleTest \
  test --tests '*ChatStorageIntegrationTest' --tests '*ChatMessageCleanupJobTest' \
  bootJar --no-daemon
```

2026-10-07 검증 결과: 신규 대상 테스트 14개 통과·스킵 0개. 별도 임시 MySQL로 실행한 전체 테스트는
403개 중 384개 통과·실패 0개·기존 격리 로컬 프로필 테스트 19개 스킵입니다.
Spotless·Checkstyle·`bootJar`도 통과했습니다. 운영 배포·실제 정시 실행은 수행하지 않았습니다.

### 전체 백엔드 검증

로컬 품질 검증에는 JDK 25, MySQL 9.7.0 테스트 DB와 Docker daemon이 필요합니다. 일반 통합 테스트는
`TEST_DB_URL`의 MySQL을 사용하고, 채팅방 마이그레이션·동시성 통합 테스트는 Testcontainers로
MySQL 9.7.0 컨테이너를 실행합니다. Docker를 사용할 수 없으면 해당 테스트가 스킵되므로 전체 품질
검증이 완료된 것으로 간주하지 않습니다.

DB를 준비하고 환경변수를 불러온 뒤 Docker 실행 상태를 확인합니다.

```sh
set -a
. ./.env
set +a

docker info
```

코드 포맷과 정적 분석은 다음 명령으로 검증합니다.

```sh
# Java 포맷 검사
./gradlew spotlessCheck --no-daemon

# 포맷 위반 자동 수정
./gradlew spotlessApply --no-daemon

# 운영 코드와 테스트 코드 Checkstyle 검사
./gradlew checkstyleMain checkstyleTest --no-daemon
```

테스트와 패키징은 다음 명령으로 검증합니다.

```sh
# 단위·통합 테스트
./gradlew test --no-daemon

# 테스트를 포함한 전체 빌드
./gradlew clean build --no-daemon

# 실행 가능한 JAR 생성: 테스트는 실행하지 않음
./gradlew bootJar --no-daemon
```

병합 전에는 아래 명령으로 포맷, 정적 분석, 전체 테스트와 패키징을 순서대로 확인합니다.

```sh
./gradlew clean spotlessCheck checkstyleMain checkstyleTest test --no-daemon
./gradlew bootJar --no-daemon
```

모든 태스크가 `BUILD SUCCESSFUL`로 종료되고 테스트 실패나 Docker 미실행에 따른 스킵이 없어야 전체
검증이 완료된 것으로 판단합니다. 테스트에는 인증, 추천, 음성 전사, 지도, 위치 판정, 채팅방 입장과
Flyway 마이그레이션 관련 단위·통합 테스트가 포함됩니다. 테스트 보고서는
`build/reports/tests/test/index.html`에서 확인합니다.

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
각 요청의 입력 문장은 해당 `recommendation_sessions.prompt`에 따로 저장합니다.
공개 요청의 `prompt`는 비어 있지 않은 최대 200자로 검증하며,
앞뒤 공백을 제거한 현재 입력 전체를 절단 없이 `POST /v1/chat/messages`에 전달합니다.
이전에 저장한 prompt는 AI 메시지에 합치지 않으며,
`conversation_key`를 `thread_id`로 사용하고 요청마다 `request_id`를 생성합니다.

추천 제공자는 `RECOMMENDATION_PROVIDER`로 선택하며 기본값은 모든 프로필에서 `ai`입니다.
AI 서버가 반환한 곡 정보를 서비스 음악 모델로 변환합니다. `itunes` 제공자는 로컬 확인용으로만 선택할 수 있습니다.
AI 서버 주소와 연결·읽기 제한 시간은 각각 `RECOMMENDATION_AI_BASE_URL`,
`RECOMMENDATION_AI_CONNECT_TIMEOUT`, `RECOMMENDATION_AI_READ_TIMEOUT`으로 주입합니다.
V1의 백엔드와 AI 간 내부 요청에는 별도의 `Authorization` 헤더를 보내지 않습니다.

`POST /api/v1/recommendations`는 세션을 `PROCESSING`으로 저장하고 `202 Accepted`와
`recommendation_id`를 즉시 반환합니다. 프론트엔드는 소유권이 확인되는
`GET /api/v1/recommendations/{recommendation_id}/events`에 연결해 `text`, `tracks`,
`done`, `error` 이벤트를 받습니다. 백엔드는 AI 내부 SSE의 `text`와 `tracks`를 전달하고,
AI의 `done` 이후 곡을 저장한 다음 공개 SSE에 `done`을 보냅니다. 곡이 0개여도 빈 목록으로
`COMPLETED` 처리하며, 오류 또는 완료 전 연결 종료는 `FAILED`(제한 시간 초과는 `TIMEOUT`)로
저장하고 `error` 이벤트를 보냅니다. 기존 GET 상세 조회는 완료 결과 재조회에 사용합니다.
기본 iTunes 요청 제한 시간은 8초이고 `RECOMMENDATION_ITUNES_TIMEOUT`으로 변경할 수 있습니다.
기본 검색 스토어는 `US`이며 `RECOMMENDATION_ITUNES_COUNTRY`로 변경할 수 있습니다.
음악 기록 검색과 텍스트 음악 추천은 이 국가·요청 제한 시간 설정을 공유합니다.
실제 API 확인 시 `KR` 스토어는 검색 결과가 없었고 `US` 스토어에서는 한국어 곡도 검색됐습니다.
측정 지표 `recommendation.provider.duration`과 `recommendation.request.duration`은
각각 선택된 제공자 호출과 전체 요청의 시간·성공·실패·시간 초과를 구분합니다. 인증 정보,
AI 내부 오류 본문과 사용자의 전체 입력 문장은 로그에 기록하지 않습니다.
첫 추천 결과는 POST 응답으로 받고, GET은 저장된 결과 재조회에 사용합니다.

## 음성 전사 기능

`POST /api/v1/speech-transcriptions`는 `multipart/form-data`의 `audio` 파일을 받습니다.
WebM 또는 MP4만 허용하며 업로드는 최대 10MB로 제한합니다. 파일 시그니처·MIME과 전체 음성의
디코딩 가능 여부를 검증한 뒤, 앞부분을 최대 60초 이내로 준비합니다. 60초를 초과한 정상 음성도
길이만으로 거절하지 않습니다. 인코딩 패딩을 고려해 최대 59.9초 분량을 MP4/AAC 16kHz 모노로
변환하고, 결과를 다시 디코딩하여 실제 재생 길이·용량·형식을 검증합니다. 원본 음성과 transcript는
영구 저장하지 않으며 임시 파일은 처리 성공·실패·시간 초과 후 삭제합니다. 전사 성공 응답의
`data.transcript`는 프론트엔드 입력창에 표시되고 사용자가 확인·수정한 뒤 별도 추천 요청의
`prompt`와 `input_type=VOICE`로 전달됩니다.

`SpeechToTextProvider` 구현은 `SPEECH_TRANSCRIPTION_PROVIDER`로 선택합니다. 개발 환경은
고정 transcript를 반환하는 `stub`, 운영 프로필은 기본적으로 `ai`를 사용합니다. AI 어댑터는
검증된 음성 파일을 `audio` 파트의 `multipart/form-data`로 구성해 `POST /v1/transcriptions`에
전달하며, 파일 파트의 `Content-Type`으로 MIME 타입을 보냅니다. 원본 음성·transcript를 저장하거나
로그에 기록하지 않습니다.

AI 서버 주소와 연결·읽기 제한 시간은 `SPEECH_TRANSCRIPTION_AI_BASE_URL`,
`SPEECH_TRANSCRIPTION_AI_CONNECT_TIMEOUT`, `SPEECH_TRANSCRIPTION_AI_READ_TIMEOUT`으로 주입합니다.
AI의 형식 오류는 서비스 `400`, 크기 초과는 `413`, 서비스 장애는 `502`, 시간 초과는 `504`로
변환하며 내부 오류 본문은 공개 응답에 노출하지 않습니다.
V1의 백엔드와 AI 간 내부 요청에는 별도의 `Authorization` 헤더를 보내지 않으며 자동 재시도도
수행하지 않습니다. 운영 타임아웃, `request_id` 및 재시도 정책은 운영 환경 배포 전에
AI·클라우드 팀과 확정합니다.
AI로 전달하는 실제 음성은 최대 60초입니다. 서비스 업로드의 길이 초과는 앞부분을 자르는
전처리로 처리하며, AI 내부 API의 제한과 원본 계약 문서는 변경하지 않습니다.

전사 실패 응답은 공통 `{ message, data }` 형식을 유지하며 `data`는 `null`입니다. 별도의
Custom Code를 추가하지 않고 다음 HTTP 상태를 프론트엔드의 복구 동작 판별 코드로 사용합니다.

| 상태 | 조건 | 클라이언트 처리 |
| --- | --- | --- |
| `400 Bad Request` | 파일 누락, 지원하지 않는 형식, MIME 불일치, 손상된 파일 또는 AI 형식 오류 | 공개 `message`에 따라 다시 녹음 |
| `413 Payload Too Large` | 10MB 초과 | 더 짧게 다시 녹음 |
| `502 Bad Gateway` | FFmpeg 실행 불가·결과 검증 실패, 전사 서비스 장애·결과 없음 | 동일 녹음 재시도 또는 텍스트 입력 |
| `503 Service Unavailable` | 동시에 처리 가능한 음성 준비 요청 초과 | 잠시 후 재시도 |
| `504 Gateway Timeout` | 음성 준비 또는 전사 서비스 제한 시간 초과 | 동일 녹음 재시도 또는 텍스트 입력 |
| `500 Internal Server Error` | 분류되지 않은 서버 오류 | 재시도 또는 텍스트 입력 |

### FFmpeg 실행 환경과 배포 순서

로컬·CI·운영 서버 모두 `ffmpeg` 실행 파일과 AAC 디코더/인코더를 설치해야 합니다.
macOS는 `brew install ffmpeg`, Debian/Ubuntu는 `apt-get install ffmpeg`로 준비할 수 있습니다.
`SPEECH_AUDIO_FFMPEG_PATH`는 실행 파일 경로(기본 `ffmpeg`), `SPEECH_AUDIO_PROCESSING_TIMEOUT`은
각 입력 검증·인코딩·결과 검증 단계의 제한 시간(기본 `15s`)입니다. 최대 두 요청을 동시에 처리하며 각 프로세스의
코덱 스레드를 하나로 제한합니다. 입력을 먼저 출력 파일 없이 전체 디코딩하여 검증하고, 인코딩과 결과 검증을 별도로 수행합니다.
입력 손상은 `400`, 인코딩·저장·결과 검증 실패는 `502`입니다. 세 단계에 각각 제한 시간이 적용됩니다.
`SPEECH_AUDIO_TEMPORARY_DIRECTORY`는 기존의 쓰기 가능한 임시 디렉터리이며 기본값은 JVM의
`java.io.tmpdir`입니다. 사용자 파일명은 프로세스 인자나 임시 경로에 사용하지 않습니다.
FFmpeg 출력·원본 음성·전사문은 로그에 남기지 않습니다.

배포 시 FFmpeg와 임시 디렉터리 권한을 먼저 준비하고 백엔드 #119, 프론트엔드 #122를 적용합니다.
짧은 음성과 60초 경계 WebM·MP4의 실제 AI 전사를 스테이징에서 확인합니다. 자동화 테스트는
실제 FFmpeg 전처리와 stub 전사 제공자를 사용하며 실제 AI 서버·브라우저 마이크 검증을 대체하지 않습니다.
이 변경을 되돌리면 60초 초과 파일을 다시 거절합니다. DB 변경은 없습니다.

`SpeechAudioProcessorIntegrationTest`는 실제 FFmpeg를 사용하며 설치하지 않으면 실패합니다.
다음 명령으로 59초·60초·60초 초과 파일과 손상·도구 누락·시간 초과 시 정리를 검증합니다.

```bash
./gradlew test --tests '*SpeechAudioProcessorIntegrationTest' --no-daemon
```

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

## 음악 기록 삭제

`DELETE /api/v1/music-records/{record_id}`는 인증된 사용자의 기록만 소프트 삭제합니다.
성공은 본문 없는 `204`, 미인증은 `401`, 타인의 활성 기록은 `403`, 없는 기록과
이미 삭제된 기록은 `404`입니다. 오류는 기존 `{message, data}` 형식을 유지합니다.
[OpenAPI 계약](api/music-record-delete.openapi.yaml)에 요청·응답을 기록했습니다.

기존 `deleted_at`을 사용하므로 새 Migration은 없습니다. 음악 데이터와 다른 사용자의
기록은 유지하고, 기존 목록·상세 조회는 삭제 기록을 제외합니다. 지도 대표 음악 조회는
별도 `feature/map-latest-album-cover` 작업의 통합 후 최신 기록·마지막 기록 삭제 시
이전 커버 선택·커버 제거·ETag 변경을 확인해야 합니다. 현재 dev 지도는 정적 카탈로그입니다.

BE API를 먼저 배포한 뒤 FE 삭제 UI를 배포합니다. 삭제 오류나 권한 위반이 발견되면
FE UI와 BE 코드를 되돌립니다. 이미 삭제된 데이터는 코드 롤백만으로 복원되지 않으며
필요한 기록만 승인된 운영 절차로 복구합니다. Feature Flag는 없습니다.

삭제 단위·HTTP·보안 필터 테스트와 전용 테스트 DB의
`MUSIC_RECORD_LOCAL_TESTS=true ./gradlew test --tests '*MusicRecordApiDatabaseIntegrationTest' --no-daemon`으로
소유권·삭제 후 조회·반복 요청·공용 음악과 타인 기록 보존을 검증합니다. 실제 테스트 DB
환경변수는 기존 실행 기준을 따르고 운영 DB를 사용하지 않습니다.
전체 Migration이 이미 적용된 전용 테스트 DB에서는
`SPRING_FLYWAY_LOCATIONS=classpath:db/migration`도 설정해 같은 Migration 목록으로 검증합니다.
단건 삭제 작업의 검증은 실제 DB 통합 테스트 3개와 Docker 의존 테스트를 제외한 회귀 테스트
252개가 통과했습니다. 조건부 테스트 17개는 회귀 실행에서 건너뛰었으며, Docker
의존 테스트 3개 클래스는 실행하지 않았습니다.

### 음악 기록 일괄 삭제 (#101)

`DELETE /api/v1/music-records`에 JSON `{ "record_ids": [7, 8] }`를 전달합니다.
양수 ID 1~100개를 받으며 중복 ID는 한 번만 처리합니다. 성공은 본문 없는 `204`입니다.
잘못된 입력은 `400`, 미인증은 `401`, 타인의 활성 기록은 `403`, 없거나 이미 삭제된
기록은 `404`입니다. 하나라도 실패하면 트랜잭션 전체를 롤백합니다. ID를 정렬해 잠금
순서를 맞추며 공용 음악과 타인 기록을 보존합니다. 기존 단건 API는 유지합니다.

새 Migration은 없습니다. FE #75보다 서버 API를 먼저 배포하고 배포 환경의 프록시가
DELETE JSON 본문을 전달하는지 확인합니다. 시간 초과는 서버 처리 여부를 확정하지
못하므로 목록을 다시 조회합니다. 반복 삭제의 `404`를 성공 응답으로 간주하지 않습니다.
롤백과 지도 앨범 커버 작업의 의존성은 위 단건 삭제 기준과 동일합니다.
실제 DB 테스트는 테스트 자체의 트랜잭션 밖에서 HTTP 요청을 보내 요청별 커밋·전체
롤백을 검증하고, 생성한 테스트 데이터만 정리합니다.
일괄 삭제 작업은 단위·HTTP·보안·실제 DB 테스트 32개(실제 DB 4개 포함), Docker 제외
회귀 테스트 257개와 포맷·Checkstyle·빌드가 통과했습니다. 회귀 실행에서 조건부
테스트 18개는 건너뛰었으며 Docker 의존 3개 클래스는 실행하지 않았습니다.


## 긴 아티스트명 저장 정책 및 배포 (#103)

`music.artist_name`은 새 마이그레이션
`V20261002133615__expand_music_artist_name_length.sql`로 `VARCHAR(1000) NOT NULL`까지 확대합니다.
기존 V1 마이그레이션과 저장된 원문은 유지합니다. 음악 기록과 AI 추천이 같은 테이블을 사용하며,
두 저장 경로 모두 제목 255자, 아티스트명 1000자를 저장 전에 검증합니다.
길이는 Unicode 코드 포인트 기준이며 공백을 제외하거나 문자열을 잘라 저장하지 않습니다.
빈 값과 한도 초과값은 외부 음악 메타데이터 오류로 처리합니다.

음악 기록 생성 시 비정상 메타데이터는 HTTP 502와 다음 공통 응답으로 반환합니다.

```json
{ "message": "music metadata invalid", "data": null }
```

프론트엔드는 다른 곡 선택을 안내하고 작성한 장소명과 메모를 유지합니다.
정상 생성 요청은 기존과 같이 음악 식별자와 위치 토큰만 전송합니다.
아티스트명을 프론트엔드에서 전송하도록 계약을 변경하지 않습니다.
AI 추천은 잘못된 tracks를 공개하기 전에 검증하고 세션을 FAILED로 전환하며,
공개 SSE error 이벤트에서 다른 곡 추천을 안내합니다. 성공 done 이벤트는 보내지 않습니다.
로그에는 실패 필드, 길이, 한도만 남기고 외부 메타데이터 원문은 기록하지 않습니다.

배포와 복구 절차:

1. 실제 대상 DB의 `SHOW CREATE TABLE music`과 Flyway 적용 이력을 확인하고 백업을 확보합니다.
2. 스테이징에서 마이그레이션 실행 시간과 DDL 잠금을 확인합니다. 자동 롤백이 보장되지 않으므로
   트래픽이 적은 시간에 적용합니다. 백엔드의 기본 및 두 로컬 프로필에 새 마이그레이션이 포함됩니다.
3. DB 확대를 먼저 적용하거나 백엔드 기동 시 Flyway 성공을 확인한 뒤 요청을 받습니다.
   이후 새 실패 안내를 포함한 프론트엔드를 배포합니다.
4. 제보된 Les Nations 곡의 저장·상세 조회와 AI 추천 저장을 확인합니다.
   아티스트명 원문 보존, 기존 곡 저장, 502 응답 및 프론트엔드 입력 보존을 확인합니다.
5. 실패율 증가, Flyway 실패 또는 지속적인 DB 잠금 발생 시 배포를 중단합니다.
   앱은 이전 버전으로 복구할 수 있지만 확대된 DB 컬럼은 유지합니다.
   긴 값 저장 이후에는 컬럼을 바로 100자로 축소하지 않습니다.
   축소가 필요한 경우 `CHAR_LENGTH(artist_name) > 100`인 데이터의 보존·복구 방안을 먼저 결정하고
   별도의 검토된 마이그레이션을 작성합니다. 기존 Flyway 이력은 변경하지 않습니다.

Docker가 실행 중인 환경에서 다음 테스트로 기존 VARCHAR(100) 오류 재현,
Flyway 업그레이드와 기존 데이터 보존, 음악 기록 API 저장·조회,
AI 추천 INSERT/UPDATE, Unicode 1000자 경계 및 비정상 데이터의 502 응답을 확인합니다.
음악 공급자와 인증·위치 판정은 테스트 대역을 사용하며 MySQL과 Flyway는 실제로 실행합니다.

```sh
./gradlew test --tests '*MusicMetadata*' --tests '*ItunesMusicSearchClientHttpTest' --no-daemon
```

실제 배포 DB와 운영 로그는 이번 로컬 재현으로 확인한 대상이 아니므로,
운영 장애 원인 확정과 이슈 종료에는 스테이징 또는 운영 검증이 추가로 필요합니다.
관련 프론트엔드 이슈: https://github.com/100-hours-a-week/KTB4-18th-FE/issues/80


## 챗봇 추천 입력 한도 (#109)

공개 `POST /api/v1/recommendations`는 TEXT와 사용자가 확정한 VOICE 전사문에
같은 200자 한도를 적용합니다. 빈 값, 공백만 있는 값, 201자 이상 요청은 세션 생성이나 AI 호출 전에
HTTP 400과 `{ "message": "입력 내용과 요청 형식을 확인해 주세요. (최대 200자)", "data": null }`을 반환합니다.
정상 요청은 기존 POST 202 및 GET SSE 흐름을 유지합니다.
공개 요청 계약은 `api/recommendation-input.openapi.yaml`에 기록했습니다.

길이는 Java `String.length()` / Jakarta `@Size`의 UTF-16 코드 단위 기준입니다.
프론트엔드의 `String.length` 및 textarea `maxLength`와 동일하며, 공백과 줄바꿈도 포함합니다.
일반적인 한글은 1자, 보조 평면 이모지는 2자로 계산합니다. 요청 길이는 원본을 기준으로 검증하고
AI에는 앞뒤 공백만 제거해 전달합니다. 자동 절단·요약은 하지 않습니다.
STT 결과 자체는 길이로 제한하거나 전사 실패 처리하지 않습니다.
`recommendation_sessions.prompt`의 TEXT 타입과 기존 저장 이력은 유지하며 DB 마이그레이션은 없습니다.

호환성 변경: 기존 201~1000자 요청은 이제 HTTP 400으로 거절됩니다.
프론트엔드 #104의 200자 제한·초과 전사문 수정 안내를 먼저 배포하고 백엔드를 적용합니다.
이전 클라이언트에서 거절된 요청도 조용히 절단하지 않고 수정 안내를 제공합니다.
배포 후 199·200자 정상 완료, 201자 거절, 텍스트·음성 원문 전달과 SSE 흐름을 스테이징에서 확인합니다.
오류 증가 시 배포를 중단하고 양쪽 앱 버전을 함께 검토합니다. 이전 백엔드로 롤백하면
자동 절단 문제가 다시 발생하므로 200자 프론트 제한을 유지합니다. DB 복구는 필요하지 않습니다.

`RecommendationApiTests`는 한글·줄바꿈·이모지를 포함한 TEXT/VOICE 경계값,
DB prompt 보존, AI 전달 내용 일치, 초과 요청의 세션 생성 및 AI 호출 차단을 검증합니다.
AI 제공자는 테스트 대역이며 실제 AI 서버와 운영 배포 환경은 별도 검증 대상입니다.
관련 프론트엔드 이슈: https://github.com/100-hours-a-week/KTB4-18th-FE/issues/104

## 지역 채팅 입퇴장과 접속 정원 (#140)

REST 계약은 [chat-participation.openapi.yaml](api/chat-participation.openapi.yaml)을 따른다.
위치 토큰은 기존 발급자의 서명·소유자·5분 유효기간과 SIGUNGU를 검증한다.
입장 시 활성 사용자·방·제재를 확인하고, 신규 참여는 201, 같은 활성 참여는 200을 반환한다.
퇴장은 `DELETE /api/v1/chat-rooms/{room_id}/members/me?membership_id={membership_id}`다.
자신의 참여 이력을 확인하며 종료된 이전 이력을 다시 퇴장해도 새 이력에 영향을 주지 않는다.
확정된 지역 이동은 이전 참여를 먼저 종료하므로 새 방 정원 초과에도 되돌리지 않는다.

단일 서버 메모리에서 연결·30초 예약·30초 유예의 사용자 합집합을 정원으로 계산한다.
REST 응답 직후 예약하고 첫 연결에서 접속으로 전환한다. 같은 사용자의 여러 연결은 1명이다.
마지막 연결 종료에서만 유예가 시작되며, 예약·유예 경계 이후 다음 정원 계산에서 즉시 자리를 회수한다.
이때 DB 참여 이력은 종료하지 않는다. 기한 후 재연결도 활성 이력과 제재·정원을 새로 확인한다.
DB 트랜잭션이 실제 커밋된 다음 메모리를 반영하며 서버 내부에서 이 전환들을 직렬화한다.
여러 애플리케이션 인스턴스 또는 Redis로 확장하는 용도로 사용할 수 없다.

WebSocket은 네이티브 STOMP 1.2 `/ws`다. handshake URL에 토큰을 넣지 않는다.
CONNECT 헤더는 `Authorization: Bearer {access_token}`, `room_id`, `membership_id`다.
HTTP upgrade만 허용하고 실제 참여 인증은 CONNECT에서 처리한다. Origin은 기존
`AUTH_CORS_ALLOWED_ORIGINS` 목록으로 제한한다. 수신 토큰 헤더는 즉시 제거해 오류 로그에 남기지 않는다.
본인 방 `/topic/chat-rooms/{room_id}`와 `/user/queue/chat-status`만 구독할 수 있다.
CONNECT·SUBSCRIBE에서 사용자·방·이력·제재를 다시 확인하고 임의 broker SEND를 차단한다.
메시지 송수신은 후속 #141 범위로 아직 제공하지 않는다.

방 SUBSCRIBE에 `receipt`를 보내면 simple broker 등록 완료 후 `RECEIPT receipt-id`를 반환한다.
프론트는 CONNECTED만으로 준비 상태가 되지 않고 이 receipt까지 기다린다.
재연결마다 새 Bearer 토큰을 전달한다. heartbeat는 양방향 10초이며, 열린 연결의 토큰 만료·참여
권한·제재도 10초마다 확인한다. 별도의 자동 탐지 목록/판정은 이번 작업에 없다.
실제 퇴장·지역 이동·로그아웃은 계정의 모든 해당 연결 권한을 종료하고 `4100 CHAT_LEFT`로 닫는다.
제재 연결 정리는 `4101 CHAT_BANNED`, 토큰 만료는 `4102 AUTH_REQUIRED`다.
처리 거부의 STOMP ERROR message는 `CHAT_FULL`, `CHAT_BANNED`, `CHAT_LEFT`, `AUTH_REQUIRED` 중 하나다.
메시지 처리 단계에서 제재를 확정하는 #141은 저장 커밋 후 `leaveAll(userId, 4101, "CHAT_BANNED")`를
호출해 즉시 모든 연결을 종료해야 한다. 저장 트랜잭션 내부에서 런타임 잠금을 취하지 않는다.

배포는 BE를 먼저 적용한 뒤 FE #133을 적용한다. 프록시의 `/ws` upgrade 전달과 허용 Origin,
26명 동시 진입, 다중 기기 1자리, 다른 방 구독 거부, 전체 퇴장 및 30초 단절을 스테이징에서 확인한다.
롤백은 FE를 먼저 복구하고 BE를 복구한다. DB migration은 추가하지 않았으며 서버 재시작은
연결/예약/유예 메모리를 초기화한다. 활성 DB 이력만으로 접속 자리 또는 과거 메시지 조회를 허용하지 않는다.
