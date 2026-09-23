# 병합 후 전체 테스트 및 Chrome 검증 원문 로그

검증 일시: 2026-09-23 (KST)  
검증 브랜치: BE/FE `feature/main-map`  
병합 기준: 각 저장소의 최신 `origin/dev`

## 테스트 환경

- DB: 로컬 격리 스키마 `meomuneum_test_mainmap_browser_20260923`
- 백엔드: `test` 프로필, `http://127.0.0.1:8081`
- 프론트엔드: Vite, `http://127.0.0.1:5174`
- 프론트 프록시: `VITE_API_PROXY_TARGET=http://localhost:8081`
- 기존 8080 포트는 다른 `feature/user-signup` 프로세스가 사용 중이라 중단하지 않고 8081을 사용했다.

인증 토큰·세션 쿠키·비밀번호는 원문 로그에서도 보안상 `[REDACTED]`로 마스킹했다. 그 외 응답 데이터와 도구 출력은 원문 그대로 기록했다.

## 테스트용 설정 변경

프론트엔드 Vite 프록시 대상은 `VITE_API_PROXY_TARGET`으로 바꿀 수 있다. 기본값은 기존과 같은 `http://localhost:8080`이며, 개발 서버에만 적용된다.

```dotenv
VITE_API_BASE_URL=
VITE_API_PROXY_TARGET=http://localhost:8080
```

## 원문 리소스

- [지도 API 200 응답 헤더](raw/map-dots-200.headers.txt)
- [지도 API 200 응답 본문 — 1,050개 항목](raw/map-dots-200.response.json)

응답 본문 SHA-256:

```text
851e8f82afb2b5b92ef617540de699dd82d2cba78a1df49d3bab48873d59ef3e  raw/map-dots-200.response.json
```

## 백엔드 전체 테스트 원문

명령:

```bash
TEST_DB_URL='jdbc:mysql://localhost:3306/meomuneum_test_mainmap_browser_20260923' \
TEST_DB_USERNAME='root' TEST_DB_PASSWORD='' ./gradlew test --no-daemon
```

출력:

```text
To honour the JVM settings for this build a single-use Daemon process will be forked. For more on this, please refer to https://docs.gradle.org/9.7.1/userguide/gradle_daemon.html#sec:disabling_the_daemon in the Gradle documentation.
Daemon will be stopped at the end of the build
> Task :compileJava
> Task :processResources
> Task :classes
> Task :compileTestJava
> Task :processTestResources NO-SOURCE
> Task :testClasses
OpenJDK 64-Bit Server VM warning: Sharing is only supported for boot loader classes because bootstrap classpath has been appended
2026-09-23T16:27:57.829+09:00  INFO 94079 --- [meomuneum-backend] [ionShutdownHook] j.LocalContainerEntityManagerFactoryBean : Closing JPA EntityManagerFactory for persistence unit 'default'
2026-09-23T16:27:57.830+09:00  INFO 94079 --- [meomuneum-backend] [ionShutdownHook] com.zaxxer.hikari.HikariDataSource       : HikariPool-1 - Shutdown initiated...
2026-09-23T16:27:57.832+09:00  INFO 94079 --- [meomuneum-backend] [ionShutdownHook] com.zaxxer.hikari.HikariDataSource       : HikariPool-1 - Shutdown completed.
2026-09-23T16:27:57.834+09:00  INFO 94079 --- [meomuneum-backend] [ionShutdownHook] j.LocalContainerEntityManagerFactoryBean : Closing JPA EntityManagerFactory for persistence unit 'default'
2026-09-23T16:27:57.834+09:00  INFO 94079 --- [meomuneum-backend] [ionShutdownHook] com.zaxxer.hikari.HikariDataSource       : HikariPool-2 - Shutdown initiated...
2026-09-23T16:27:57.835+09:00  INFO 94079 --- [meomuneum-backend] [ionShutdownHook] com.zaxxer.hikari.HikariDataSource       : HikariPool-2 - Shutdown completed.
2026-09-23T16:27:57.836+09:00  INFO 94079 --- [meomuneum-backend] [ionShutdownHook] j.LocalContainerEntityManagerFactoryBean : Closing JPA EntityManagerFactory for persistence unit 'default'
2026-09-23T16:27:57.837+09:00  INFO 94079 --- [meomuneum-backend] [ionShutdownHook] com.zaxxer.hikari.HikariDataSource       : HikariPool-3 - Shutdown initiated...
2026-09-23T16:27:57.838+09:00  INFO 94079 --- [meomuneum-backend] [ionShutdownHook] com.zaxxer.hikari.HikariDataSource       : HikariPool-3 - Shutdown completed.
> Task :test

BUILD SUCCESSFUL in 14s
4 actionable tasks: 4 executed
Consider enabling configuration cache to speed up this build: https://docs.gradle.org/9.7.1/userguide/configuration_cache_enabling.html
```

배포 JAR 패키징 명령과 출력:

```bash
./gradlew bootJar --no-daemon
```

```text
To honour the JVM settings for this build a single-use Daemon process will be forked. For more on this, please refer to https://docs.gradle.org/9.7.1/userguide/gradle_daemon.html#sec:disabling_the_daemon in the Gradle documentation.
Daemon will be stopped at the end of the build
> Task :compileJava UP-TO-DATE
> Task :processResources UP-TO-DATE
> Task :classes UP-TO-DATE
> Task :resolveMainClassName UP-TO-DATE
> Task :bootJar

BUILD SUCCESSFUL in 4s
4 actionable tasks: 1 executed, 3 up-to-date
Consider enabling configuration cache to speed up this build: https://docs.gradle.org/9.7.1/userguide/configuration_cache_enabling.html
```

## 프론트엔드 전체 테스트 원문

병합 직후에는 잠금 파일에 추가된 아이콘 패키지가 로컬 `node_modules`에 설치되지 않아 첫 실행이 실패했다.

```text
FAIL  src/App.test.tsx [ src/App.test.tsx ]
Error: Failed to resolve import "@karrotmarket/react-monochrome-icon/IconEyeSlashLine" from "src/features/user-signup/components/SignupPage.tsx". Does the file exist?

Test Files  1 failed | 4 passed (5)
Tests  14 passed (14)
```

다음으로 잠금 파일 기준 의존성을 설치했다.

```text
added 325 packages, and audited 326 packages in 5s

58 packages are looking for funding
  run `npm fund` for details

found 0 vulnerabilities
```

재실행 명령:

```bash
npm run test && npm run build && npm run lint
```

재실행 출력:

```text
> meomuneum-frontend@0.0.0 test
> vitest run

 RUN  v5.0.1 /Users/bipo/Documents/meomuneum/FE-branch/feature/main-map

Test Files  5 passed (5)
Tests  21 passed (21)
Start at  16:30:24
Duration  2.42s (environment 53%, transform 18%, tests 15%, setup 8%, import 5%)

> meomuneum-frontend@0.0.0 build
> tsc -b && vite build

vite v8.3.0 building client environment for production...
transforming...
✓ 564 modules transformed.
rendering chunks...
computing gzip size...
dist/index.html                   0.46 kB │ gzip:   0.30 kB
dist/assets/index-CkdiUEVh.css  493.85 kB │ gzip:  51.86 kB
dist/assets/index-BjCYS0-H.js   373.47 kB │ gzip: 114.42 kB

✓ built in 703ms

> meomuneum-frontend@0.0.0 lint
> eslint .
```

## 지도 API 원문 검증

`GET /api/v1/map-dots`의 저장된 원문 본문에서 계산한 결과:

```json
{
  "message": "map dots retrieved",
  "itemCount": 1050,
  "first": {
    "map_dot_id": 1,
    "code": "KR-COAST-0001",
    "album_cover_url": null,
    "latest_recorded_at": null
  },
  "last": {
    "map_dot_id": 1050,
    "code": "KR-COAST-1050",
    "album_cover_url": null,
    "latest_recorded_at": null
  },
  "allCodesMatch": true
}
```

## Chrome 브라우저 검증 원문

### 메인 지도

Chrome 접근성 트리 원문:

```text
AXWebArea meomuneum-frontend, URL: 127.0.0.1:5174/
  heading 음악 지도
  text MEOMUNEUM
  button 로그인
  heading 우리의 음악이 쌓이는 곳
  text 도트 하나마다 함께 만든 순간을 담아요.
  container 대한민국 도트 지도
  link Description: 음악 추천 챗봇 열기, Value: 127.0.0.1:5174/chatbot
  container 메인 탐색
    button 지도
    button 기록
    button 새 기록 만들기
    button 채팅방
    button 마이
```

DOM 원문 확인값:

```json
{"circles":1050,"floatingHref":"/chatbot","mapVisible":true}
```

브라우저 콘솔 오류·경고 원문:

```json
[]
```

### 로그인·로그아웃

격리 DB에만 생성한 더미 계정으로 로그인 후의 접근성 트리 원문:

```text
AXWebArea meomuneum-frontend, URL: 127.0.0.1:5174/
  heading 음악 지도
  text MEOMUNEUM
  button 로그아웃
  heading 우리의 음악이 쌓이는 곳
  container 대한민국 도트 지도
  link Description: 음악 추천 챗봇 열기, Value: 127.0.0.1:5174/chatbot
```

로그아웃 후 원문:

```text
AXWebArea meomuneum-frontend, URL: 127.0.0.1:5174/login
  heading 다시 만나서 반가워요
  text 나의 음악 지도로 이어가요
  text field 이메일
  text field 비밀번호
  button 로그인
  link Description: 회원가입, Value: 127.0.0.1:5174/signup
```

### 회원가입 화면 전환

```text
AXWebArea meomuneum-frontend, URL: 127.0.0.1:5174/signup
  button 이전 화면으로 돌아가기
  heading 회원가입
  text 머문음에서 나만의 음악 지도를 시작해 보세요.
  text field 닉네임
  text field 이메일
  text field 비밀번호
  heading 약관 동의
  checkbox Description: [필수] 서비스 이용약관 동의, Value: 0
  button 회원가입
```

### 챗봇 페이지 전환

```text
AXWebArea meomuneum-frontend, URL: 127.0.0.1:5174/chatbot
  heading 음악 추천 챗봇
  container 음악 추천 대화
  text 지금의 순간에 음악을 더해볼까요?
  text entry area 추천받고 싶은 상황
  button 추천 요청 보내기
```

## 결과

| 항목 | 결과 |
| --- | --- |
| BE 전체 테스트 | 통과 |
| FE 단위 테스트 | 5 files / 21 tests 통과 |
| FE 빌드·린트 | 통과 |
| 지도 API | 200, 1,050개 도트, 현재 코드 형식 확인 |
| 지도 표시 | Chrome DOM에서 SVG 도트 1,050개 확인 |
| 회원가입 화면 | `/signup` 전환 확인 |
| 로그인·로그아웃 | 격리 DB 더미 계정으로 화면 전환 확인 |
| 챗봇 | 플로팅 버튼으로 `/chatbot` 전환 확인 |
