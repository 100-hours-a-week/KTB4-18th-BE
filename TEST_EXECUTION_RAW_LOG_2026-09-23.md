# 지도 도트 API 재검증 원문 로그

- 실행 일시: 2026-09-23 Asia/Seoul
- 대상 브랜치: `feature/main-map`
- 테스트 DB: `meomuneum_test_mainmap_review_20260923`
- 비밀값: 환경변수로만 주입했으며 본 문서에 기록하지 않음

## 실행 명령

```text
TEST_DB_URL='jdbc:mysql://127.0.0.1:3306/meomuneum_test_mainmap_review_20260923' TEST_DB_USERNAME='root' TEST_DB_PASSWORD='' ./gradlew clean test --no-daemon
TEST_DB_URL='jdbc:mysql://127.0.0.1:3306/meomuneum_test_mainmap_review_20260923' TEST_DB_USERNAME='root' TEST_DB_PASSWORD='' ./gradlew bootJar --no-daemon
```

## 전체 테스트 원문 출력

```text
To honour the JVM settings for this build a single-use Daemon process will be forked. For more on this, please refer to https://docs.gradle.org/9.7.1/userguide/gradle_daemon.html#sec:disabling_the_daemon in the Gradle documentation.
Daemon will be stopped at the end of the build
> Task :clean

Note: /Users/bipo/Documents/meomuneum/BE-branch/feature/main-map/src/main/java/com/muse/meomuneum/recommendation/provider/ItunesRecommendationProvider.java uses or overrides a deprecated API.
> Task :compileJava
Note: Recompile with -Xlint:deprecation for details.

> Task :processResources
> Task :classes

> Task :compileTestJava
Note: /Users/bipo/Documents/meomuneum/BE-branch/feature/main-map/src/test/java/com/muse/meomuneum/recommendation/SpeechTranscriptionControllerTests.java uses or overrides a deprecated API.
Note: Recompile with -Xlint:deprecation for details.
Note: Some input files use unchecked or unsafe operations.
Note: Recompile with -Xlint:unchecked for details.

> Task :processTestResources NO-SOURCE
> Task :testClasses
OpenJDK 64-Bit Server VM warning: Sharing is only supported for boot loader classes because bootstrap classpath has been appended
2026-09-23T21:17:36.541+09:00  INFO 66866 --- [meomuneum-backend] [ionShutdownHook] j.LocalContainerEntityManagerFactoryBean : Closing JPA EntityManagerFactory for persistence unit 'default'
2026-09-23T21:17:36.542+09:00  INFO 66866 --- [meomuneum-backend] [ionShutdownHook] com.zaxxer.hikari.HikariDataSource       : HikariPool-1 - Shutdown initiated...
2026-09-23T21:17:36.544+09:00  INFO 66866 --- [meomuneum-backend] [ionShutdownHook] com.zaxxer.hikari.HikariDataSource       : HikariPool-1 - Shutdown completed.
2026-09-23T21:17:36.546+09:00  INFO 66866 --- [meomuneum-backend] [ionShutdownHook] j.LocalContainerEntityManagerFactoryBean : Closing JPA EntityManagerFactory for persistence unit 'default'
2026-09-23T21:17:36.546+09:00  INFO 66866 --- [meomuneum-backend] [ionShutdownHook] com.zaxxer.hikari.HikariDataSource       : HikariPool-2 - Shutdown initiated...
2026-09-23T21:17:36.547+09:00  INFO 66866 --- [meomuneum-backend] [ionShutdownHook] com.zaxxer.hikari.HikariDataSource       : HikariPool-2 - Shutdown completed.
2026-09-23T21:17:36.549+09:00  INFO 66866 --- [meomuneum-backend] [ionShutdownHook] j.LocalContainerEntityManagerFactoryBean : Closing JPA EntityManagerFactory for persistence unit 'default'
2026-09-23T21:17:36.549+09:00  INFO 66866 --- [meomuneum-backend] [ionShutdownHook] com.zaxxer.hikari.HikariDataSource       : HikariPool-3 - Shutdown initiated...
2026-09-23T21:17:36.550+09:00  INFO 66866 --- [meomuneum-backend] [ionShutdownHook] com.zaxxer.hikari.HikariDataSource       : HikariPool-3 - Shutdown completed.
> Task :test

[Incubating] Problems report is available at: file:///Users/bipo/Documents/meomuneum/BE-branch/feature/main-map/build/reports/problems/problems-report.html

BUILD SUCCESSFUL in 14s
5 actionable tasks: 5 executed
Consider enabling configuration cache to speed up this build: https://docs.gradle.org/9.7.1/userguide/configuration_cache_enabling.html
```

- 종료 코드: `0`

## JAR 패키징 원문 출력

```text
To honour the JVM settings for this build a single-use Daemon process will be forked. For more on this, please refer to https://docs.gradle.org/9.7.1/userguide/gradle_daemon.html#sec:disabling_the_daemon in the Gradle documentation.
Daemon will be stopped at the end of the build
> Task :compileJava UP-TO-DATE
> Task :processResources UP-TO-DATE
> Task :classes UP-TO-DATE
> Task :resolveMainClassName
> Task :bootJar

BUILD SUCCESSFUL in 4s
4 actionable tasks: 2 executed, 2 up-to-date
Consider enabling configuration cache to speed up this build: https://docs.gradle.org/9.7.1/userguide/configuration_cache_enabling.html
```

- 종료 코드: `0`

## 테스트 DB 상태 원문 출력

```text
9.7.2
meomuneum_test_mainmap_review_20260923
chat_room_members
chat_rooms
flyway_schema_history
music
recommendation_items
recommendation_sessions
regions
terms
terms_agreements
users
```

- 종료 코드: `0`

## Git 변경 상태 원문 출력

```text
 D api-test/postman/map-dots/001-map-dots-200.response.headers.txt
 D api-test/postman/map-dots/001-map-dots-200.response.json
 D api-test/postman/map-dots/002-map-dots-304.response.headers.txt
 D api-test/postman/map-dots/map-dots.postman_collection.json
 D api-test/postman/map-zones/001-map-zones-200.response.headers.txt
 D api-test/postman/map-zones/001-map-zones-200.response.json
 D api-test/postman/map-zones/002-map-zones-304.response.body
 D api-test/postman/map-zones/002-map-zones-304.response.headers.txt
 D api-test/postman/map-zones/003-sheet-map-dots.response.body
 D api-test/postman/map-zones/003-sheet-map-dots.response.headers.txt
 D api-test/postman/map-zones/map-zones.postman_collection.json
 D api-test/verification-2026-09-23/raw/map-dots-200.headers.txt
 D api-test/verification-2026-09-23/raw/map-dots-200.response.json
 M src/main/java/com/muse/meomuneum/global/config/SecurityConfig.java
 M src/main/java/com/muse/meomuneum/map/catalog/MapZoneCatalog.java
 M src/test/java/com/muse/meomuneum/map/MapDotSecurityIntegrationTest.java
 M src/test/java/com/muse/meomuneum/map/catalog/MapZoneCatalogTests.java
?? MAP_REVIEW_RESOLUTION_DESIGN.md
?? README.md
 .../map-dots/001-map-dots-200.response.headers.txt | 12 -----
 .../map-dots/001-map-dots-200.response.json        |  1 -
 .../map-dots/002-map-dots-304.response.headers.txt |  7 ---
 .../map-dots/map-dots.postman_collection.json      | 29 ------------
 .../001-map-zones-200.response.headers.txt         | 12 -----
 .../map-zones/001-map-zones-200.response.json      |  1 -
 .../map-zones/002-map-zones-304.response.body      |  0
 .../002-map-zones-304.response.headers.txt         |  7 ---
 .../map-zones/003-sheet-map-dots.response.body     |  0
 .../003-sheet-map-dots.response.headers.txt        | 11 -----
 .../map-zones/map-zones.postman_collection.json    | 37 ---------------
 .../raw/map-dots-200.headers.txt                   | 16 -------
 .../raw/map-dots-200.response.json                 |  1 -
 .../meomuneum/global/config/SecurityConfig.java    |  5 +-
 .../muse/meomuneum/map/catalog/MapZoneCatalog.java | 46 ++++++++++++++-----
 .../map/MapDotSecurityIntegrationTest.java         |  8 ++++
 .../meomuneum/map/catalog/MapZoneCatalogTests.java | 53 +++++++++++++++++++++-
 17 files changed, 95 insertions(+), 151 deletions(-)
```

- 종료 코드: `0`
