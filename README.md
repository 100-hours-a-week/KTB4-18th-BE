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
client ID·서버 UTC 전송 시각과 함께 저장합니다. 실시간 송수신·탐지는 #141로 연결하며, 참여 구간별 내역 조회는 후행 작업입니다.
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
추천 기능의 기본 검색 스토어는 `US`이며 `RECOMMENDATION_ITUNES_COUNTRY`로 변경할 수 있습니다.
음악 기록 검색도 기본 스토어는 `US`이고 `MUSIC_SEARCH_COUNTRY`로 별도 변경할 수 있습니다.
두 기능은 iTunes 요청 제한 시간 설정을 공유합니다.
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
본인 방 `/topic/chat-rooms/{room_id}`와 `/user/queue/chat-events`를 구독한다. 이전 `/user/queue/chat-status`도 허용한다.
CONNECT·SUBSCRIBE에서 사용자·방·이력·제재를 다시 확인하고 임의 broker SEND를 차단한다.
메시지 송수신·ACK·전송 차단·마스킹은 [chat-messaging.md](api/chat-messaging.md)를 따른다.

방 SUBSCRIBE에 `receipt`를 보내면 simple broker 등록 완료 후 `RECEIPT receipt-id`를 반환한다.
프론트는 CONNECTED만으로 준비 상태가 되지 않고 이 receipt까지 기다린다.
재연결마다 새 Bearer 토큰을 전달한다. heartbeat는 양방향 10초이며, 열린 연결의 토큰 만료·참여
권한·제재도 10초마다 확인한다. 승인한 초기 탐지 목록은 [chat-moderation-policy.md](api/chat-moderation-policy.md)를 따른다.
실제 퇴장·지역 이동·로그아웃은 계정의 모든 해당 연결 권한을 종료하고 `4100 CHAT_LEFT`로 닫는다.
제재 연결 정리는 `4101 CHAT_BANNED`, 토큰 만료는 `4102 AUTH_REQUIRED`다.
처리 거부의 STOMP ERROR message는 `CHAT_FULL`, `CHAT_BANNED`, `CHAT_LEFT`, `AUTH_REQUIRED` 중 하나다.
#155부터 욕설·음란성 메시지는 마스킹하며 자동 제재·강제 퇴장을 수행하지 않는다.
기존 자동 제재는 이력을 보존하여 해제한다. 저장 트랜잭션 내부에서 런타임 잠금을 취하지 않는다.

배포는 BE를 먼저 적용한 뒤 FE #133을 적용한다. 프록시의 `/ws` upgrade 전달과 허용 Origin,
26명 동시 진입, 다중 기기 1자리, 다른 방 구독 거부, 전체 퇴장 및 30초 단절을 스테이징에서 확인한다.
롤백은 FE를 먼저 복구하고 BE를 복구한다. DB migration은 추가하지 않았으며 서버 재시작은
연결/예약/유예 메모리를 초기화한다. 활성 DB 이력만으로 접속 자리 또는 과거 메시지 조회를 허용하지 않는다.

## 욕설·음란성 메시지 마스킹 (#155)

`badwordfiltering:1.0.0`과 검토한 추가·제외·허용 파일을 기동 시 로딩한다.
탐지 구간의 입력 code point 수만큼 `*`로 치환하고 저장·방송·ACK에 같은 본문을 사용한다.
공백과 `@ _ - . *` 삽입을 지원하며 URL·개인정보·도배는 원문에서 먼저 검사한다.
FE의 마스킹 ACK 호환성 변경이 선행되어야 한다. 기존 자동 제재 해제는 새 데이터 마이그레이션으로
수행하며 운영 DB를 직접 변경하지 않았다. 구버전 BE와 혼용하거나 자동 롤백하지 않는다.

목록·예문·검사 순서·배포/복구 기준은 [탐지 정책](api/chat-moderation-policy.md),
실제 검증 결과는 [마스킹 검증 기록](api/chat-masking-verification.md)을 참고한다.

## Redis 연결·구독 기반 (P1 2단계)

`feature/chat-redis-foundation`은 최신 `dev`의 지역 채팅에 Redis 연결 기반을 추가한다.
제품 메시지의 서버 간 전달, 공유 정원·참여 상태, Core 인증 연동과 Chat 앱 분리는 후속 작업이다.
현재 제품의 정원·연결 권한은 여전히 로컬 상태에 의존하므로 이 단계만으로 여러 서버를 운영하지 않는다.
MySQL을 최종 원장으로 두고 Redis를 투영·캐시·이벤트 전달에 사용하는 계약을 따른다.

| 환경 변수 | 기본값 / 의미 |
| --- | --- |
| `CHAT_REDIS_ENABLED` | `false`; 비활성일 때 연결과 전용 health bean을 생성하지 않음 |
| `CHAT_REDIS_ENVIRONMENT` | 활성화 시 필수 환경 namespace |
| `CHAT_REDIS_HOST`, `CHAT_REDIS_PORT` | `localhost`, `6379` |
| `CHAT_REDIS_USERNAME`, `CHAT_REDIS_PASSWORD` | `default`, 활성화 시 비밀번호 필수; 앱별 명명 ACL 권장 |
| `CHAT_REDIS_SSL` | `true`; 인증서·호스트명 검증, 자동 평문 전환 없음 |
| `CHAT_REDIS_SSL_CA_CERTIFICATE` | 선택 PEM CA 파일 경로; 지정 시 TLS 필수 |
| `CHAT_REDIS_CONNECT_TIMEOUT`, `CHAT_REDIS_COMMAND_TIMEOUT` | 각각 `1s` |
| `CHAT_REDIS_REQUEST_QUEUE_SIZE` | `1024`; 허용 범위 1~65536 |

운영 비밀값은 배포 환경에서 주입한다. `.env.example`을 Spring이 자동으로 읽지는 않는다.
EC2 Docker Redis의 TLS 제공 여부는 클라우드 팀과 확인해야 한다. 사설 CA는 신뢰 파일로 제공하고
인증서 검증을 우회하지 않는다. 운영 Core/Chat ACL 계정 분리와 Core 네트워크 허용도 별도 조치가 필요하다.

명령 연결과 Pub/Sub 연결을 분리한다. `chatRedis` health와 `chat.redis.connected` 지표는 명령 연결,
`chatRedisSubscriptions`와 `chat.redis.subscriptions.ready`는 실제 구독 ACK 준비 상태다.
구독이 없거나 끊기면 구독 health는 DOWN이며 재접속 후 방·제어 구독 ACK를 모두 받아야 UP이다.
기존 제품 readiness group에 이 항목을 자동 추가하지 않는다. 후속 relay가 관리 구독을 생성하고
복구·배포 계약과 함께 readiness에 연결해야 한다. 사용자 연결 정리·전송 중단도 후속 작업이다.

환경별 채널은 `<env>:chat:room:*`, `<env>:chat:control:v1`이다.
Redis ACL의 `PSUBSCRIBE`는 실제 패턴과 같은 채널 권한을 요구하므로 두 패턴을 명시한다.
Chat은 방·제어 발행/구독과 제한된 키·Lua 권한, Core는 제어 발행만 허용하는 분리를 검증한다.
상태 키 생성기는 `<env>:chat:state:{room:<id>}:e:<epoch>:<component>` 형식만 제공한다.
epoch 발급·원자 상태 전이·복구 알고리즘은 아직 구현하지 않았다.

끊긴 연결의 신규 명령은 즉시 거절하고 명령 큐와 대기 시간을 제한한다.
이미 전송된 명령의 성공 여부나 Pub/Sub 유실까지 보장하지 않으므로 후속 제품 로직에는
멱등 처리와 MySQL 기반 복구가 필요하다. 종료 시 관리 구독·명령 연결·클라이언트를 정리한다.

```sh
./gradlew test --tests '*ChatRedisConfigurationTest' --no-daemon
./gradlew chatRedisFoundationTest --no-daemon
```

명시적 통합 task는 Docker가 필요하며 기본 Redis 이미지는 `redis:8.10.2`다.
`-PchatRedisImage=redis:<version>`으로 검증 이미지를 바꿀 수 있다.
격리 MySQL 9.7.0·Redis와 실제 두 JVM으로 합성 이벤트 양방향 전달, 환경 격리,
Redis 중단·재구독·정상 종료를 확인한다. 명명 ACL·Lua·권한 거부,
합성 TLS의 CA 신뢰·호스트명 검증과 `noeviction` 메모리 초과 후 복구도 시험한다.
테스트용 probe·인증서·비밀번호는 제품 JAR 및 운영 환경에 주입하지 않는다.
이 검증은 실제 제품의 다중 서버 REST/STOMP나 운영 TLS·부하·배포 검증을 뜻하지 않는다.
Redis 기반만 되돌릴 때는 활성화를 해제한다. 이번 단계에는 DB migration이 없다.

2026-10-23 검증에서 Redis 전용 4개 전부, 채팅 회귀 201개 중 197개가 통과했다
(채팅 조건부 제외 4개, 실패 0개). Spotless·Checkstyle main/test·bootJar도 통과했다.
전체 테스트는 738개 중 705통과·10실패·23조건부 제외다. 실패 10개는 프로필 이미지 테스트의
`AuthService` 구성 누락, 약관·동시 가입 테스트이며 변경 전 `dev` 7406ba5에서도 동일하게 재현했다.
격리 검증 기록은 `build/redis-foundation-reintegration/summary.json`에 보존한다.

## Core·Chat 독립 앱·이미지 (P1 3단계)

같은 저장소의 `core`·`chat` 소스셋을 독립 컴파일한다. 공유 계약·모델은 기존 소스에서 재사용하며
각 JAR에는 선택된 역할의 클래스·리소스·라이브러리만 들어간다. 기존 단일 앱 `bootJar`·`bootRun`은
전환 전 회귀/실행 경로로 유지한다. 새 앱과 기존 단일 앱을 운영 DB에서 함께 실행하지 않는다.

| 구분 | Core | Chat |
| --- | --- | --- |
| 진입점 | `com.muse.apps.core.CoreApplication` | `com.muse.apps.chat.ChatApplication` |
| 산출물 | `build/libs/meomuneum-core.jar` | `build/libs/meomuneum-chat.jar` |
| 소유 API | 인증·사용자·추천·음악·지도·위치 발급 | `/ws`, `/api/v1/chat-rooms/**`, `/api/v1/regions/{id}/chat-room` |
| 배치/초기화 | 기존 인증 스케줄러 | 채팅 초기화·checkpoint·보관 기간 정리 |
| 제외 | 채팅 런타임·WebSocket·마스킹 라이브러리 (4단계부터 인증 알림용 Lettuce 포함) | Core 서비스/컨트롤러·Hazelcast·AI·역지오코딩 클라이언트·Flyway |
| 이미지 | `Dockerfile.core`, FFmpeg 포함 | `Dockerfile.chat`, FFmpeg 제외 |

Core의 위치 토큰 발급과 Chat의 토큰 검증은 같은 계약을 쓴다. 세션 ID 형식 검사는 공통 코드로
옮겨 Core가 채팅 연결 registry에 의존하지 않게 했다. JWT 필터의 사용자 확인도 읽기 인터페이스로
분리해 Chat에서 로그인/BCrypt 서비스를 만들지 않는다. 공통 오류 응답의 예외 타입은 재사용한다.
공개 REST/STOMP 필드·destination·기존 이력/재연결 정책·DB schema는 변경하지 않는다.

```sh
./gradlew coreBootJar chatBootJar bootJar --no-daemon
./gradlew spotlessCheck checkstyleMain checkstyleCore checkstyleChat checkstyleTest --no-daemon
./gradlew appSeparationTest --no-daemon
# 각 앱 실행 (필요한 환경 변수 주입 후):
./gradlew coreBootRun --no-daemon
./gradlew chatBootRun --no-daemon
```

앱별 설정은 `src/core/resources/application.yaml`, `src/chat/resources/application.yaml`이다.
기존 단일 앱의 `application-dev/test/prod.yaml`을 새 앱에 복사하지 않아 역할별 DB 설정을 덮어쓰거나
Chat에서 Core 비밀값을 요구하지 않는다. `.env.example`은 변수 예시이며 자동으로 로딩하지 않는다.

| 설정 | 설명 |
| --- | --- |
| `CORE_DB_URL/USERNAME/PASSWORD`, `CHAT_DB_URL/USERNAME/PASSWORD` | 같은 schema의 앱별 접속 정보; 실제 DB 계정/권한 분리는 클라우드 조치 |
| `CORE_DB_MAXIMUM_POOL_SIZE`, `CHAT_DB_MAXIMUM_POOL_SIZE` | 각각 기본 10; Core + Chat Task 수 × pool + migration/운영 여유분을 협의 |
| `CORE_SERVER_PORT`, `CHAT_SERVER_PORT` | 각각 기본 8080; 같은 호스트에서는 서로 다른 포트 지정 |
| `CORE_SHUTDOWN_TIMEOUT`, `CHAT_SHUTDOWN_TIMEOUT` | 각각 기본 30s; 프레임워크 종료 유예이며 WS drain 실측 완료를 뜻하지 않음 |
| `AUTH_JWT_SECRET`, `LOCATION_TOKEN_SECRET`, JWT issuer/audience·허용 Origin | 양 앱의 토큰 계약 일치; Chat은 Hazelcast/AI 비밀값을 받지 않음 |
| `CORE_FLYWAY_ENABLED` | 기본 false; 합의한 단일 migration 실행 주체만 명시적으로 활성화 |
| `CHAT_RUNTIME_ENABLED` | 기본 false; 남은 공유 상태·복구 P1 완료 전 운영 진입 차단 |

Chat은 Flyway 라이브러리·migration SQL을 패키징하지 않고 Hibernate `validate`만 수행한다.
Core는 migration 원본을 보존하되 평상시 자동 실행을 하지 않는다. 두 앱은 이미 준비된 schema에
접속해야 한다. DB 계정/권한·migration 실행 주체·SSM 매핑은 이번 코드 변경으로 생성되지 않는다.

`GET /actuator/health/liveness`와 `/actuator/health/readiness`만 인증 없이 health를 확인할 수 있다.
Core readiness는 기존 `authState` 조건을 유지한다. Chat readiness는 DB·런타임 활성화를 확인하고,
기본 상태에서는 liveness 200·readiness 503과 채팅 경로 503을 반환한다. 이때 채팅 초기화·배치를
생성하지 않는다. 상대 앱의 경로는 인증 처리 전에 404로 거절한다.

현재 Chat 런타임 활성화는 **격리 검증 전용**이다. `CHAT_RUNTIME_ENABLED=true`와 `chat-isolated`
profile을 함께 지정해야 하며 `prod`와 함께 사용하면 DB 연결 전에 기동을 거절한다.
기존 JWT/사용자 확인만으로 Core의 폐기 sid를 확인했다고 간주하지 않기 위한 임시 전환 경계다.
4단계에서 내부 인증 readiness를 연결했다. 이 활성화 제한의 교체와 Redis 실제 구독·공유 상태
복구 조건은 남은 P1에서 연결해야 한다. 현 단계만으로 다중 Chat Task를 운영하지 않는다.

```sh
# 커밋된 소스로 빌드한다. 플랫폼은 ECS runtimePlatform과 맞춘다.
docker build -f Dockerfile.core --build-arg BUILD_GIT_COMMIT="$(git rev-parse HEAD)" -t meomuneum-core:app-separation-check .
docker build -f Dockerfile.chat --build-arg BUILD_GIT_COMMIT="$(git rev-parse HEAD)" -t meomuneum-chat:app-separation-check .
./gradlew appSeparationTest -PappSplitImages=true --no-daemon
```

두 이미지는 JDK 25 builder/JRE 25 runtime과 UID/GID 10001을 사용한다. Java를 exec 형식으로
실행해 종료 신호를 전달한다. `.dockerignore`는 소스·빌드 설정만 허용하며 `.git`, `.env`, 로컬
`tools/`·문서·검증 결과를 build context에서 제외한다. 빌드 커밋과 소스 SHA를 JAR에 기록한다.
이미지 검증은 위 로컬 태그와 Docker가 필요하다. 새 library/서비스의 자격 증명을 운영에서 가져오지 않는다.

`appSeparationTest`는 격리 MySQL 9.7.0을 단일 시험 migrator로 준비하고 실제 JAR 프로세스를 실행한다.
Core의 채팅 미초기화·API 경계·위치 토큰 발급, 차단 Chat의 부작용 없음, 단일 활성 Chat의
REST 입장·STOMP 실제 구독/저장 ACK·세션 쿠키 미생성을 확인한다. Core 종료 후 Chat 유지 시험은
앱 프로세스 분리 확인이며 Core 장애 중 인증 허용 정책을 확정하거나 검증한 것은 아니다.
이미지 옵션은 컨테이너 기동·health·비루트 실행·Core FFmpeg/Chat 미포함을 추가 확인한다.
실제 로그인/폐기·Redis 제품 전파·다중 서버 정원·운영 부하·ALB/ECS 배포는 후속 검증이다.

전환은 후속 P1 구현·검증과 클라우드 협의가 끝난 뒤 짧은 채팅 중단을 두고 진행한다.
기존 채팅 처리 중단과 연결/열람 경계 정리 → 단일 주체 migration → 새 Core/Chat 준비 → 라우팅 개방과
smoke 확인을 맞춘다. 동시에 채팅 상태를 쓰는 구/신 앱이나 인증 Task 수 가정을 깨는 배포를 피한다.
클라우드 팀에는 이미지/플랫폼, 경로 목록, 앱별 secret/DB pool, 두 health 경로와 실제 drain 측정값을 전달한다.
롤백은 후속 데이터 변경과 호환되는 버전으로만 수행한다. 이번 단계 자체에는 DB migration이 없다.

2026-10-26 최종 검증: 앱/JAR/이미지 통합 3개와 Redis 기반 회귀 4개 전부 통과·제외 0개.
분리 Chat에서 실제 단절·동일 view 재연결 뒤 history/recovery의 저장 메시지 복원까지 확인했다.
Spotless·Checkstyle main/core/chat/test·기존 및 분리 JAR 생성이 통과했다.
일반 test는 738개 중 705통과·10실패·23조건부 제외이며 채팅은 197통과·4조건부 제외·실패0이다.
실패 10개의 테스트 이름과 원인은 이전에 변경 전 dev에서도 재현한 프로필 이미지·약관·가입 실패와 같다.
로컬 Linux arm64 이미지로 검증했으며 ECS 대상 플랫폼·운영 인증·AWS 배포 결과로 표시하지 않는다.
증빙은 `build/app-separation-verification/`과 `build/app-separation/run-*/`에 보존한다.


## Core 인증 연동 (P1 4단계)

Core가 인증 원장(Hazelcast 세션 + 활성 사용자 DB)을 소유하고 Chat은 내부 API로 확인한다.
Chat에 Hazelcast 클러스터·refresh 쿠키·로그인 서비스를 복제하지 않는다. JWT 서명/만료와 위치 토큰
검증은 유지하고, **서명된 JWT라도 Core에서 폐기된 sid는 허용하지 않는다**.
공개 REST 필드·STOMP destination·DB schema와 migration은 변경하지 않는다.

| 변경 | 동작과 판단 근거 |
| --- | --- |
| Core 내부 검증 | `POST /internal/v1/auth/sessions/validate`; 1~100개의 `(user_id,sid)`를 엄격하게 검증. 사용자 소유·ACTIVE·idle/absolute 만료·활성 계정을 함께 확인해야 폐기/탈퇴가 반영된다. |
| 서비스 인증 | 사용자 JWT와 별도인 32~256바이트 Bearer 현재/다음 키. TLS 인증서/호스트명 검증, 내부 경로 외부 차단은 클라우드에서 적용한다. 양 키의 동시 수용으로 순차 교체한다. |
| 비용 상한 | 요청 32KiB·응답 64KiB·중복 sid/알 수 없는 필드 거절, bounded executor/queue·rate limit·전체 1초 deadline. Chat은 자동 재시도/redirect를 하지 않는다. |
| REST·CONNECT | 매 요청 Core fresh 확인; 같은 pair의 진행 중 요청만 합친다. 기존 캐시를 사용해 새 요청의 Core 장애를 숨기지 않는다. |
| 기존 연결 | 요청 시작 monotonic 시각 기준 `min(4초, 남은 세션 수명)`만 허용. 2초마다 최대100개씩 확인, 250ms watchdog. 긴 GC/대기 후에도 유효기간을 늘리지 않는다. |
| 권한 검사 | SUBSCRIBE/SEND·DB transaction 시작/commit·메시지 전송 및 실행 대기열에서 꺼낼 때 재확인. JWT 자체의 만료도 검사한다. 이미 commit된 데이터나 OS에 전달한 frame을 회수하는 보장은 아니다. |
| 폐기 전파 | Core의 실제 sid 폐기 지점에서 generation을 포함한 이벤트를 발행. Redis 제어 채널 알림은 빠른 경로이고 HTTP 주기 확인이 유실을 보완한다. 늦게 도착한 ACTIVE 응답은 폐기를 되돌리지 못한다. |
| sid별 정리 | 해당 sid의 소켓부터 종료한 뒤 view/참여 정리; DB 실패는 재시도한다. 같은 계정의 다른 sid와 계정 history는 유지한다. 기존 단일 앱도 같은 sid 이벤트를 처리한다. |
| 장애 구분 | INACTIVE는 REST401/소켓4100 계열의 인증 종료. Core 통신/서비스 키 오류·timeout은 REST503·기존 연결1013·readiness DOWN이며 영구 LEFT로 기록하지 않는다. 복구 후 기존 history/recovery로 재연결한다. |

내부 API는 [OpenAPI](api/chat-core-auth.openapi.json), 이벤트 계약은 [JSON Schema](api/chat-core-auth-events.schema.json)에 기록했다. 인증 외 이벤트는 후속 P1 계약이며 이번 구현 범위가 아니다.
모든 내부 API 응답은 `Cache-Control: no-store`다. 오류 상세·Bearer·비밀번호·sid를 로그/오류 본문으로
전달하지 않는다. 사용자 INACTIVE 응답은 원래 요청 순서/쌍을 보존하며 저장소 실패는 전체503이다.
Core 검증은 읽기 전용이며 refresh를 회전하거나 수명을 연장하지 않는다.

폐기 이벤트는 `<env>:chat:control:v1`에 `AUTH_SESSION_REVOKED`로 발행한다.
`schema_version=1`, UUID event_id, `core:<UUID>` producer_id, UTC occurred_at 및
payload의 user_id/sid/session_generation/reason만 허용하고 epoch/토큰을 포함하지 않는다.
Chat은 설정된 Core producer allowlist와 16KiB 상한을 검사한다.
현재 발행 reason은 `SESSION_REVOKED`이며 로그아웃·개별 세션 삭제·비밀번호 변경·refresh 재사용의
공통 revoke 경로를 사용한다. 정상 refresh 회전은 폐기 알림을 발행하지 않는다.
자동 세션 만료와 사용자 비활성화는 Core HTTP 확인으로 차단한다.
Core Redis 연결은 첫 알림 때 비동기로 열며 publish 실패가 Core 기동/로그아웃 성공을 되돌리지 않는다.

| 앱 | 필수 설정/활성화 |
| --- | --- |
| Core 내부 API | `CORE_CHAT_AUTH_ENABLED=true`, `CHAT_AUTH_SERVICE_TOKEN_CURRENT`; 교체 중 `CHAT_AUTH_SERVICE_TOKEN_NEXT` |
| Chat 검증 | `CHAT_CORE_AUTH_ENABLED=true`, `CORE_INTERNAL_BASE_URL` (HTTPS), `CORE_AUTH_SERVICE_TOKEN` (Core 현재/다음 키와 일치) |
| Core 빠른 알림 | `CORE_AUTH_REDIS_ENABLED=true`, `CORE_AUTH_PRODUCER_ID`, `CORE_AUTH_REDIS_ENVIRONMENT/HOST/PORT/USERNAME/PASSWORD/SSL/CA_CERTIFICATE` |
| Chat 알림 구독 | 기존 `CHAT_REDIS_ENABLED=true` 및 TLS/ACL 설정, `CHAT_AUTH_REDIS_ENABLED=true`, 쉼표로 구분한 `CORE_AUTH_PRODUCER_IDS` |

기본값은 모두 비활성이다. 기존 앱·3단계 실행 경로와 호환되도록 전환을 명시적으로 선택한다.
HTTP plaintext는 `chat-isolated`의 loopback/localhost 시험만 허용한다. 사설 CA는 Chat JVM trust store로
제공한다. 내부 서비스 키는 JWT·위치 토큰·Redis 비밀번호와 구분하고 SSM/ECS에서 주입한다.
교체 순서는 Core current+next 수용 → 모든 Chat next 적용/확인 → Core current 교체/old 제거다.
Core/Chat Redis 사용자도 분리해 Core는 해당 제어 채널 PUBLISH만, Chat은 필요한 채널 구독만 허용한다.
현재 클라우드가 보고한 plaintext/default 공유 Redis만으로 이 조건이 충족됐다고 보지 않는다.

```sh
./gradlew test --tests 'com.muse.meomuneum.chat.auth.*' --no-daemon
# Docker + Testcontainers 필요. 운영 자격 증명/데이터를 사용하지 않는다.
./gradlew coreAuthIntegrationTest appSeparationTest chatRedisFoundationTest --no-daemon
./gradlew spotlessCheck checkstyleMain checkstyleCore checkstyleChat checkstyleTest coreBootJar chatBootJar bootJar --no-daemon
```

인증 단위/계약 테스트 11개가 strict schema·키 교체·상한·전체 deadline·늦은 응답 경쟁·transaction
rollback·전송 대기열 가드를 확인한다. `coreAuthIntegrationTest`는 실제 Core/Chat JAR 프로세스,
실제 Hazelcast 세션, 격리 MySQL9.7.0/Redis8.10.2와 합성 TLS relay를 사용한다.
실제 Core logout 후 두 Chat을 동시에 관찰해, 한쪽은 Core HTTP를 막은 상태에서 Redis 알림으로,
다른 쪽은 알림 구독 없이 polling으로 종료되는 것을 확인했다. 다른 sid는 유지됐다.
TLS 신뢰/호스트명 거부·현재/다음 키·정상 refresh 후 기존 access JWT·Core503/401을 서비스503으로
구분·장애1013·readiness DOWN·재연결 history 복원을 포함한다.
최종 격리 시험의 두 연결 종료까지 2,112ms였다. 이는 운영 부하에서의 5초 SLO 실측이 아니다.
증빙은 `build/core-auth-integration/run-1793015716538/result.json`과 각 역할 로그에 보존한다.

Core readiness의 기존 authState는 유지하며 Chat readiness에 `coreSessionAuth`를 추가했다.
남은 공유 정원·owner/epoch·제품 메시지 relay·Redis 복구·drain이 완료되지 않았으므로
`CHAT_RUNTIME_ENABLED`의 격리 환경 제한과 prod 거부를 유지한다. 두 Chat으로 수행한 시험은
인증 전파만 검증한 것으로 전체 제품의 다중 서버 정합성/운영 개방 완료를 뜻하지 않는다.
클라우드 후속 사항은 내부 API TLS/신뢰 CA·서비스 키 주입/교체·외부 `/internal/*` 차단,
Core Redis SG 접근·TLS 및 앱별 ACL, 운영 부하에서의 deadline/5초 차단 측정이다.

최종 일반 회귀는 749개 중 719통과·7실패·23조건부 제외다. 실패 7개는 변경 전 dev에서도 재현한
프로필 이미지 테스트의 `AuthService` fixture 누락과 동일하며 새 실패는 없다. 기존에 실패했던
약관/가입 3개는 이번 실행에서 통과했다. 채팅 회귀 212개는 208통과·4조건부 제외·실패0이다.
인증 통합1개·Redis4개 전부 통과했고 앱 분리 검증은 JAR2개 통과·이미지1개 조건부 제외다.
이번 단계는 실제 역할 JAR로 검증했으며 3단계 Docker 이미지 재빌드/운영 배포를 수행하지 않았다.
증빙과 기존 dev 실패 이름 대조는 `build/core-auth-verification/summary.json` 및 XML에 보존한다.


## 전역 정원·참여 상태 (P1 5단계)

여러 Chat Task가 같은 방을 처리할 때 로컬 Map의 인원을 합산하지 않고 **MySQL 원장의 유효 view를
계정별로 중복 제거**해 정원을 판단한다. Redis는 공유 투영이며 자리·권한을 승인하지 않는다.
Redis의 유실/오래된 snapshot이 계정 정원1000을 늘리는 근거가 되지 않도록 하기 위한 선택이다.

| 항목 | 구현·판단 근거 |
| --- | --- |
| DB 원장 | 추가형 migration의 `chat_runtime_control`, `chat_runtime_tasks`, `chat_room_runtime`, `chat_view_runtime`. 기존 membership/view/history는 유지하고 과거 membership을 접속자로 복원하지 않는다. |
| 정원 확보 | READ COMMITTED에서 control → Task UUID순 SHARE → user → room ID순 UPDATE 잠금. 방 잠금 후 유효 view의 DISTINCT user를 조회하고 같은 transaction에서 예약을 저장한다. 별도 account-seat 물리 테이블을 중복 원장으로 두지 않는다. |
| 상태 | RESERVED/CONNECTED/GRACE/OFFLINE/LEFT. 유효 예약·유예30초와 유효 CONNECTED가 계정 한 자리, 실제 구독 준비가 끝난 CONNECTED만 화면 인원이다. DB UTC의 `now < deadline`을 사용한다. |
| 소켓/소유권 | REST에서 받은 view로 어느 Task에도 CONNECT할 수 있다. JWT sid와 membership sid를 직접 비교하고, 유효 owner가 있는 view의 중복 claim을 거절한다. Task UUID·소켓 token·증가 owner_generation을 기록하고 DB 잠금 안에서 현재 소유권을 재확인한다. |
| 단절/재연결 | 정상 관측한 단절만30초 GRACE와 owner release. 이전 소켓의 지연 disconnect는 새 claim을 변경하지 않는다. 만료/OFFLINE에서 새 입장 자리를 다시 검사한다. |
| 탭/sid 격리 | 탭 퇴장은 그 view만 LEFT. 지역 이동·로그아웃은 해당 sid만 종료한다. 목적지가 만원이어도 확인된 이전 방 퇴장을 commit하며 다른 sid는 유지한다. |
| Task lease | DB heartbeat5초/lease15초와 요청 시작 기준 보수적 로컬14초 deadline. 만료한 동일 Task ID는 되살리지 않고 새 UUID로 등록한다. 만료 Task를 FENCED로 바꾸며 해당 열린 구간은 마지막 verified 경계로 닫는다. |
| 만료 | 1초 정리 작업이 물리적으로 늦어져도 deadline/lease가 지난 view는 정원 계산에서 즉시 제외한다. 만료 후 재연결이 과거 부재 구간을 연장하지 않는다. |
| Redis 투영 | 환경/room hash tag/epoch namespace에 인원·view 상태를 기록한다. 방과 view의 DB version을 각각 Lua CAS해 역순 갱신을 막고24시간 TTL을 사용한다. 원문 메시지·인증 토큰·소켓 객체는 넣지 않는다. |
| 실패 | 잠금/statement 초기1초·transaction2초 예산과 commit 전 Task/인증 확인. timeout은 전체 rollback 후503. DB commit 후 투영 실패는 예약을 가짜 rollback하지 않고 Task를 차단·소켓1013/readiness DOWN으로 처리한다. |

설정은 Chat의 `CHAT_DISTRIBUTED_PRESENCE_ENABLED=true`로 명시적으로 선택한다(기본false).
`CHAT_CORE_AUTH_ENABLED=true`, `CHAT_REDIS_ENABLED=true`와 namespace·접속 정보가 필요하다.
`CHAT_RUNTIME_ENABLED`의 **chat-isolated 전용·prod 거부를 유지**하므로 이번 작업만으로 운영 Task를
증설하지 않는다. 기존 단일 앱과 분산 모드가 같은 채팅 DB 상태를 동시에 수정하지 않게 한다.
공개 REST 경로/필드·STOMP destination은 유지한다. 분산 모드 CONNECT에는 최신 REST 입장에서 발급한
`view_session_id`가 필요하며 다른 탭의 view를 임의로 선택하지 않는다.

새 Task는 다른 정상 Task의 열린 구간을 재시작으로 닫지 않는다. 주기 checkpoint는 DB 소유권/lease를
확인한 transaction에서만 수행한다. 분산 모드 조회는 임의로 now까지 checkpoint하지 않고 마지막
검증 경계를 사용한다. **다른 Task로 배정된 REST history/recovery의 현재 view 권한 확인, 메시지와
구간의 room_seq 경계 매핑·전파는 후속6/7단계다.** 이번 room_seq는 상태/투영 버전이며 완성된
분산 메시지 순서 계약으로 표시하지 않는다. Redis 상태 유실의 전역 epoch 복구·outbox/relay·drain도
후속이다. DB/Redis 실패로 차단된 Task는 이 단계에서 자동 개방하지 않으며 재기동/후속 복구 절차가 필요하다.

새 SQL은 `V20261027002610__create_chat_participation_runtime.sql`이다. 기존 적용 SQL은 변경하지 않는다.
실제 운영 적용 전에 지정한 단일 migrator·Chat 계정의 네 테이블 CRUD/locking read 권한·DB 연결 수를
클라우드와 맞춘다. Core는 migration 원본을 패키징하지만 평소 Flyway=false, Chat은 migration을 실행하지 않는다.
전환은 채팅 중단·구 처리 종료 → 단일 migration → 새 앱 확인 순서다. rollback도 채팅을 중단하고
새 영속 상태와 호환되는 버전으로 수행한다. 테이블 삭제나 구 단일 서버로 단순 재라우팅은 자동 수행하지 않는다.

Redis Chat ACL은 기존 권한에 환경별 `~<env>:chat:state:*`와 `EVAL/HGET/HSET/EXPIRE`가 필요하다.
Core는 상태 key 권한을 받지 않는다. TLS·앱별 사용자/secret·Core 망 허용 등 기존 클라우드 준비 사항은
여전히 별도이며 보고된 default 공유/plaintext 구성으로 충족됐다고 보지 않는다.

```sh
# Docker + Testcontainers, 합성 자격 증명/격리 DB만 사용한다.
./gradlew chatParticipationTest --no-daemon
./gradlew coreAuthIntegrationTest appSeparationTest chatRedisFoundationTest --no-daemon
./gradlew spotlessCheck checkstyleMain checkstyleCore checkstyleChat checkstyleTest coreBootJar chatBootJar bootJar --no-daemon
```

`chatParticipationTest`는 실제 MySQL9.7.0/Redis8.10.2로 상태 전이·999→마지막 자리 동시 경합,
동일 계정 중복 제거·다른 sid 거절·탭 퇴장·이동 목적지 만실·만료 경계·옛 소켓/Task 차단,
실제 DB lock timeout rollback·commit 후 투영 실패·실제 Lua 역순/키 유실을 검증한다.
추가로 실제 Core/두 Chat JAR를 TLS로 연결해 REST/WS 교차 배정·live view 이후 새 Task 기동·폐기/장애/history
복구를 확인한다. 계정 상태999개 fixture와 동시 요청 검증이며 실제1000 WebSocket 부하/SLO 시험은 아니다.

2026-10-27 최종 검증: 전역 참여7개 + 실제 Core/Chat 프로세스1개 = `chatParticipationTest`8개 전부 통과.
기존 인증 통합1개·Redis4개 전부 통과, 앱 분리 JAR2개 통과·이미지1개 조건부 제외다.
전체 회귀749개는718통과·8실패·23조건부 제외였다. 실패는 이전 dev에서도 재현한 프로필 이미지
AuthService fixture 누락7개와 인증 scheduler 진단 로그 집계1개다. 후자는 분리 재실행에서 통과했으며
그때 함께 실행한 채팅212개도208통과·4조건부 제외·실패0이었다. 전체 회귀 최초 실패를 삭제하거나
전체green으로 표시하지 않는다. 인증 서비스/해당 진단 테스트 소스는 이번에 변경하지 않았다.
최종 Spotless·Checkstyle main/core/chat/test·세 JAR 생성 통과. 증빙은
`build/participation-verification/summary.json`과 full-test/targeted-regression/각 전용 task XML이다.
이번 Docker 이미지 재빌드·push·운영 migration·AWS/배포는 수행하지 않았다.


## 탭 소유권·열람 구간·복구 분산 처리 (P1 6단계)

REST와 WebSocket이 서로 다른 Chat Task에 배정돼도 같은 권한·열람 경계를 사용한다.
로컬 소켓 등록부는 실제 연결을 찾는 용도로 유지하고, **현재 view의 권한과 과거 열람 범위는 MySQL 원장**에서
확인한다. 원격 REST 요청이 소켓 소유자의 접속 증거를 대신 만들지 않도록 하기 위한 선택이다.
아래 내용은 앞선5단계 문서의 후속6단계 설명을 갱신한다. 제품 메시지 relay는 여전히7단계다.

| 변경 | 동작과 판단 근거 |
| --- | --- |
| 현재 탭 소유권 | Task UUID·소켓 token·claim 때 캡처한 generation을 함께 검증한다. REST는 현재 view의 계정/membership/sid/epoch/CONNECTED·소유 Task lease를 공유 DB에서 확인한다. 다른 Task의 로컬 소켓 등록이 없다는 이유로 정상 조회를 거절하지 않는다. |
| 방 순서와 시간 | 메시지 저장·구독·checkpoint·종료가 기존 control/Task/user/room 잠금 순서와 같은 transaction을 사용한다. 메시지에는 room_seq와 DB UTC를 부여한다. ID가 발급된 시점과 commit 순서의 역전, Task 시계 차이로 부재 구간이 열리는 것을 막는다. |
| 열람 구간 | 소유자만 checkpoint를 갱신한다. 원격 조회는 열린 구간의 마지막 verified 경계까지만 읽는다. 기존5초 checkpoint 때문에 최신 메시지의 REST 노출이 그 주기만큼 늦을 수 있으며, REST 조회가 이를 임의로 확장하지 않는다. |
| history/recovery | history는 같은 계정·방의 과거 유효 구간 합집합이므로 재로그인 후에도 복원한다. recovery는 현재 view의 구간만 사용한다. 과거 구간을 읽으려 해도 현재 탭/sid의 정상 권한은 먼저 필요하다. 다른 탭·sid의 현재 권한으로 대체하지 않는다. |
| 고정 페이지 | 최초 조회 시 DB snapshot에 열람 구간·room_seq·as_of를 복사한다. 다음 페이지 중 다른 탭의 checkpoint가 갱신돼도 그 snapshot 범위가 늘지 않는다. 각 페이지는 현재 권한·DB 만료·24시간 보존 범위를 다시 검사한다. |
| cursor | 서명에 기존 필드와 snapshot ID/epoch/generation을 포함하고 DB snapshot과 대조한다. 다른 계정/방/view/mode, 이전 generation/epoch, 만료 snapshot은 거절한다. 분산 snapshot의 만료는 DB UTC가 기준이며 JVM 시각으로 대체하지 않는다. |
| 단절과 강제 종료 | 관측한 단절만30초 유예를 만든다. 기한과 같은 시각은 만료다. Task 강제 종료처럼 단절 증거가 없으면 lease 만료 후 마지막 verified 경계에서 닫고 OFFLINE으로 둔다. 다른 Task의 새 claim은 새 generation/구간으로 시작하며 부재를 소급해서 열지 않는다. |
| 늦은 전송 | outbound enqueue/handler에서 현재 소유권과 메시지 room_seq가 현재 구독 구간에 속하는지 재확인한다. unsubscribe 후 새 구독으로 늦게 도착한 과거 frame을 전달하지 않는다. |
| 물리 삭제 | 메시지가24시간 정리돼도 기존 verified ID 경계가 역행하지 않는다. 실제 메시지 내용은 기존 테이블에만 저장하고 순서 테이블에는 식별자만 기록한다. |

공개 REST 경로·응답 필드·STOMP destination은 유지한다. cursor는 opaque 값이지만 **분산 모드 전환 또는
새 소유권 획득 후에는 이전 cursor를 이어 쓰지 않고 재연결 ready/fresh 조회로 시작**해야 한다.
연결 유지 중 신규 FE 자동 누락 복구/sync는 P2이며 이번에 추가하지 않았다.

추가형 migration은 `V20261027131057__add_distributed_chat_read_boundaries.sql`이다.
기존 migration/열람 구간은 삭제하거나 다시 작성하지 않고, interval에 환경/epoch/owner/순서 경계를 추가한다.
`chat_message_order`는 방 메시지 순서, `chat_query_snapshots`와 `chat_query_snapshot_ranges`는 고정 페이지 범위를
저장한다. snapshot은24시간 뒤 만료되며 Task별60초 정리 작업이 한 번에 최대1000개 header와 하위 범위를 삭제한다.
정리가 늦어져도 조회 경로의 만료 검사가 권한을 연장하지 않는다.

설정은 기존 `CHAT_DISTRIBUTED_PRESENCE_ENABLED=true`를 사용한다. 기본false·Core 인증/Redis 의존과
`CHAT_RUNTIME_ENABLED`의 chat-isolated 전용·prod 거부를 유지한다. 이 단계만으로 운영 다중 Task를 개방하지 않는다.
분산 receipt/정책/outbox/PubSub 제품 전파, Redis 유실의 전역 epoch 복구, 배포 drain은 남은 P1 작업이다.

클라우드 협의와 전환 절차:

1. 채팅 일시 중단 → 기존 처리 Task 종료 → 지정한 단일 migrator로 새 SQL 적용 → 새 앱 격리 검증 순서를 지킨다.
   구 단일 앱과 새 분산 앱이 같은 상태를 동시에 쓰지 않는다. Chat 앱 자체는 Flyway를 실행하지 않는다.
2. Chat DB 계정에 새 세 테이블 CRUD와 기존 interval/room runtime 추가 열의 읽기·쓰기/locking 권한이 필요하다.
   snapshot의 페이지 수·구간 수에 따른 DB 저장량/청소 속도·풀 예산을 확인한다. 읽기도 권한 잠금과 snapshot 쓰기가
   필요하므로 read replica/읽기 전용 DB 계정으로 보내지 않는다.
3. 최초 room 잠금에서 기존 메시지를 ID순 room_seq에 매핑하고 기존 interval ID 경계를 연결한다.
   원래 ID/시각/종료 경계는 유지하며 매핑 완료 표시와 같은 transaction으로 commit한다.
   방별 메시지/구간 수가 큰 경우 기존1초 statement/2초 transaction 예산 안에 들어오는지 전환 전 측정한다.
   초과하면 rollback·서비스503이며 자동으로 권한을 넓히거나 예산을 무제한 늘리지 않는다.
4. rollback도 채팅을 중단하고 새 DB 상태와 호환되는 버전을 선택한다. 테이블 삭제나 구 cursor 재사용으로 우회하지 않는다.

검증 명령은 앞선5단계와 같다. `chatParticipationTest`는 실제 MySQL/Redis 도메인13개와 실제 Core/두 Chat JAR의
프로세스1개로 구성된다. 원격 history/recovery/by-client-id, 원격 checkpoint 금지, 고정 페이지·다른 epoch/generation·
DB 만료·JVM 시계 차이, 재로그인 계정 합집합·부재·24시간, 메시지 commit/구독 경합, 지연 outbound,
기존 구간 매핑·물리 삭제 후 경계 보존, Task lease 만료 복구를 포함한다.
격리 fixture와 제한된 경합 시험이며 운영1000 WebSocket 부하/SLO·운영 migration 완료를 의미하지 않는다.


2026-10-27 검증 결과: 전용 분산 시험14개, 기존 Core 인증1개·Redis4개는 전부 통과했고,
앱 분리 JAR2개 통과·이미지1개는 조건부 제외다. 실제 소유 Chat 프로세스를 강제 종료해
lease 만료→마지막 verified 경계 폐쇄/OFFLINE→조회403→다른 Task 재연결/조회200도 확인했다.
전체 회귀749개는717통과·9실패·23제외였다. 이전 단계에서도 재현한 프로필 이미지 AuthService
fixture 누락7개와 Hazelcast 진단 로그 집계/채팅 차단 각1개이며, 후자2개를 포함한 분리 재실행은
213개 중209통과·4제외·실패0이었다. 최초 전체 실패를 보존하고 전체green으로 표시하지 않는다.
관련 없는 인증/프로필 이미지 소스는 수정하지 않았다. Spotless·Checkstyle main/core/chat/test·세 JAR를
검증하며 증빙은 `build/distributed-view-verification/summary.json`과 각 task XML,
`build/core-auth-integration/run-*/cross-task-read.json`에 보존한다.
Docker 이미지 재빌드·push/PR·운영 DB/Redis/AWS/배포는 수행하지 않았다.


## 메시지 저장·Redis 전파 (P1 7단계)

여러 Task에서 전송해도 메시지·정책·재시도 결과가 일치하도록 DB를 기준으로 처리한다.
**DB commit은 저장 성공, Redis publish는 참조 알림 발행**이다. 어느 쪽도 모든 사용자의 수신 완료를 의미하지 않는다.
발신 Task도 직접 방송하지 않고 다른 Task와 같은 Pub/Sub 경로로 `CHAT_MESSAGE`를 받는다.
개인 `CHAT_ACK`만 원 요청 소켓에 보내므로 다른 탭의 ACK로 전송 상태를 잘못 바꾸지 않는다.

| 항목 | 구현과 판단 근거 |
| --- | --- |
| 원자적 저장 | 기존 control/Task/user/room 잠금 안에서 메시지·room_seq·전송 제한·receipt·outbox를 같은 transaction으로 commit한다. Redis·Core·WS 네트워크 호출은 DB transaction 밖에서 수행한다. rollback이면 정책 횟수와 전파 알림도 남지 않는다. |
| 재시도 | 환경/user/client UUID receipt를 조회해 같은 membership·방·trim한 원문 HMAC이면 원래 message_id로 ACK한다. 카운터·메시지·outbox를 다시 생성하지 않는다. 내용/문맥 충돌은 CLIENT_ID_CONFLICT, 보존 만료는 RETRY_UNAVAILABLE이다. 현재 socket 권한은 재시도에도 필요하다. |
| 전송 제한 | 계정 전역1초 간격과 membership별 동일 원문 세 번째 거절을 DB로 유지한다. 다른 Task·재시작·정상 재연결·키 교체로 초기화하지 않는다. 분산 모드에서 임의의 짧은 minimum-interval 설정은 거절한다. |
| 내용 지문 | 원문과 무키 SHA digest는 영속 저장하지 않는다. 별도 HMAC-SHA256 key ring의 ID와 fingerprint만 저장하며 공개 메시지에는 마스킹 결과만 저장한다. 마스킹 결과가 같아도 서로 다른 원문은 다른 지문이다. |
| outbox claim | 방별 DB token·5초 lease로 가장 이른 pending room_seq를 claim하고 DB commit 후 발행한다. 발행 직전 token/만료/epoch/Task를 재확인하며 성공 표시는 같은 token 조건으로만 한다. 늦은 이전 worker가 새 claim을 완료하거나 해제하지 못한다. |
| 재발행 | poll200ms·회당 최대100건·bounded worker/queue. 실패는0.5→1→2→최대5초와 jitter로 다시 시도한다. 발행 후 성공 표시 전 장애는 같은 event_id 재발행이 가능하다. 구독자 수0은 발행 성공일 수 있으며 사용자 수신 성공으로 해석하지 않는다. |
| 참조 이벤트 | 기존 schema_version=1의 CHAT_MESSAGE_CREATED를 사용한다. event_id/producer/UTC occurred_at/epoch/room_id/room_seq/message_id만 보내고 본문·nickname·토큰은 보내지 않는다. 수신은16KiB·필드·중복 JSON키·UUID·safe integer·채널 일치·지원 버전을 엄격히 검사한다. |
| 발행자 검증 | DB Task 등록과 같은 환경/epoch가 동적 Chat producer allowlist다. committed outbox의 event_id/producer/방/순서/메시지/시각과 모두 일치해야 한다. 종료된 발행 Task가 남긴 commit 알림을 다른 worker가 발행할 수 있어 원 발행자의 현재 ACTIVE 여부로 과거 저장을 지우지 않는다. Redis ACL이 서비스 인증 경계이며 producer 문자열 자체는 인증 수단이 아니다. |
| 수신 권한 | Task마다 마스킹된 DTO를 한 번 읽고, 로컬 후보 socket의 최신 DB 권한을 묶음 조회한다. 활성 계정/제재·현재 epoch/소유권/lease·CONNECTED·구독 시작 순서·Core 확인·JWT 만료를 검사한다. 로컬 처리 직렬화와 기존 outbound guard가 실제 적재/handler 때도 재검사한다. |
| 중복·역순 | 최대10000항목·10분의 로컬 event/message LRU로 중복을 줄이고 최종 화면 병합은 기존 message_id를 사용한다. 더 높은 room_seq를 먼저 받았어도 낮은 순서의 다른 메시지를 버리지 않는다. room_seq 차이를 누락 메시지 수로 해석하지 않는다. |
| 실패 | 명령 또는 Pub/Sub 단절을 알림 callback에서 즉시 latch해 새 동작/적재를 막는다. readiness DOWN·WS1013으로 정리하며 큐 포화도 조용히 버리지 않고 서비스 중단으로 처리한다. 재접속 ACK만으로 자동 재개하지 않는다. 전역 marker/epoch 복구·자동 재개는 후속8단계다. |

추가형 `V20261027142734__create_chat_message_delivery_state.sql`은 `chat_send_policy`,
`chat_send_repetitions`, `chat_message_receipts`, `chat_message_outbox`와 방별 dispatch token/deadline을 추가한다.
기존 migration·원문·열람 구간을 변경하지 않는다. receipt는 메시지의24시간 보존까지 유지하며,
끝난 membership의 반복 지문·만료 receipt/outbox를60초마다 최대1000개씩 정리한다.
메시지가 만료되거나 epoch가 지난 pending 알림은 EXPIRED로 끝내고 메시지 본문을 DLQ에 복제하지 않는다.
새 snapshot/조회 권한을 부여하거나 계정 전송 정책을 복구 때 초기화하지 않는다.

설정·호환성·클라우드 전달 사항:

- `CHAT_MESSAGE_RELAY_ENABLED=true`는 기존 distributed presence/Core auth/Redis가 준비된 격리 Chat에서만 사용한다.
  기본false와 기존 chat-isolated 전용·prod 거부를 유지한다. 전체 P1/운영 다중Task 개방 완료가 아니다.
- `CHAT_MESSAGE_FINGERPRINT_KEYS`는 `key-id:base64-secret`을 쉼표로 연결한1~8개 key ring이다.
  각 secret은32바이트 이상이며 첫 항목이 새 기록에 사용된다. JWT/서비스 Bearer/Redis 비밀번호와 별도다.
  `.env.example`에는 실제 키를 넣지 않는다. 클라우드는 별도 SSM SecureString/ECS 주입·동일 ring 배포를 준비한다.
- 키 교체는 모든 Task에 기존+새 키 검증 능력을 먼저 배포한 뒤 새 키를 첫 항목으로 승격한다.
  구키는 해당 receipt가 모두 만료되고 해당 지문을 가진 membership이 모두 끝나며 구키를 쓰는 Task도 종료된 뒤 제거한다.
  미보유 key ID가 남아 있으면 시작 또는 SEND를 거절한다. 원문 없이 기존 지문을 새 키로 변환하거나 반복 제한을 초기화하지 않는다.
- Chat DB 계정에 새 네 테이블 CRUD와 room runtime dispatch 열/locking 권한이 필요하다.
  Chat Redis ACL에는 기존 채널 범위의 PUBLISH가 추가로 필요하다. Core에 Chat 방/상태 권한을 확대하지 않는다.
- 공개 REST·STOMP 경로와 기존 `type/data`, ACK의 `membership_id/message`를 유지하고 선택 필드
  `deduplicated`만 추가한다. [wire schema](api/chat-message-delivery.schema.json)와 기존 Redis schema를 따른다.
  저장 ACK 유실 시 같은 UUID를 유지한다. client UUID가 충돌하는 기존 메시지에 새 receipt를 추정 생성하지 않으며,
  현재 권한을 통과한 기존 by-client-id/history로 확인한다.
- 전환은 채팅 중단·구 Task 종료 → 단일 migrator → 동일 key ring·ACL 준비 → 격리 검증 순서다.
  구 단일 앱과 동시에 같은 상태를 쓰거나 구 로컬 정책으로 재라우팅하지 않는다. rollback은 새 DB 상태와 호환되는 버전으로 수행한다.
- 방별 DB 저장/claim 비용, 수신 Task당 조회 및 socket별 outbound 재검사, 큐/청소 처리량·DB 풀/연결 예산을
  운영 부하로 측정해야 한다. 이 단계의 제한된 경합 시험은 실제1000소켓·운영 처리량/SLO 검증이 아니다.
- 분산 퇴장·제재/상태 알림, 전역 epoch 복구·배포 drain은 후속 P1이다. 기존 DB 권한/소유권·인증 polling은 유지한다.
  연결 유지 중 누락 자동 sync는 P2이며 기존 재연결 history/recovery를 사용한다.

검증 명령:

```sh
./gradlew chatMessageRelayTest --no-daemon
./gradlew coreAuthIntegrationTest appSeparationTest chatRedisFoundationTest --no-daemon
./gradlew spotlessCheck checkstyleMain checkstyleCore checkstyleChat checkstyleTest coreBootJar chatBootJar bootJar --no-daemon
```

`chatMessageRelayTest`는 실제 MySQL/Redis 도메인19개와 실제 Core/두 Chat JAR/TLS 프로세스1개다.
같은 UUID의 두 Task 경합·owner 변경 재시도·다른 내용/문맥 충돌·계정 간격/세 번째 반복·HMAC 키 교체,
저장 중 및 outbox insert 실패 rollback·commit 후 pending 보존·claim lease 만료/이전 token 거절·재시도/만료,
실제 Redis 중복·역순·잘못된 참조/구epoch·새 구독 부재 경계를 검증한다.
실제 두 Chat은 발신/상대 Task WS 전달·원 소켓 ACK·다른 Task 재시도 ACK·Core 폐기/장애·소유 Task 강제 종료/history,
명령 PING 정상 중 Pub/Sub만 강제 종료→1013/readiness DOWN을 검증한다.
분산 모드 저장·만료 정리도 DB UTC를 사용하며 Task 시계가48시간 빨라도 유효 메시지를 조기 삭제하지 않는다.
SQL 오류 주입용 trigger는 binlog를 끈 격리 테스트 DB에서만 사용한다. 운영 DB 설정을 바꾸지 않는다.


2026-10-27 최종 검증: 메시지 전용20개 전부 통과·제외0. 기존 Core 인증1개·Redis4개 전부 통과,
앱 분리 JAR2개 통과·이미지1개 조건부 제외다. 전체 회귀749개는719통과·7실패·23조건부 제외였다.
실패7개는6단계 보존 증빙과 이름이 동일한 프로필 이미지 AuthService fixture 누락/동일 context 실패다.
최종 채팅 회귀212개는208통과·4조건부 제외·실패0이다. 전체green으로 표시하지 않고 최초 full XML도 보존한다.
초기 trigger fixture 권한·하네스 임시 포트 경합·코드 줄 길이 실패는 보완 후 재실행으로 해소했다.
wire schema는 기존 FE의 AJV6.15.0으로 compile하고 정상4/오류1 예시를 확인했으며 FE 코드는 수정하지 않았다.
Spotless·Checkstyle main/core/chat/test·세 JAR를 확인하며 증빙은
`build/message-relay-verification/summary.json`과 각 task XML,
`build/core-auth-integration/run-*/message-relay.json`·`pubsub-interruption.json`이다.
Docker 이미지 재빌드·push/PR·운영 migration·운영 Redis/AWS/배포는 수행하지 않았다.
