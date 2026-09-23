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

Migration은 지역 코드를 기준으로 갱신하고 채팅방은 `INSERT IGNORE ... SELECT`로 생성하므로
같은 준비 작업을 반복해도 지역별 방이 중복되지 않습니다. 애플리케이션 시작 시에도
`ChatRoomProvisioningService`가 활성 시·군·구 중 누락된 방만 보충합니다. 사용자 입장 서비스는
채팅방을 생성하지 않습니다.
