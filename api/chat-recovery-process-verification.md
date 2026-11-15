# 실제 서버 메시지 복구·권한·재시작 검증

[BE #146](https://github.com/100-hours-a-week/KTB4-18th-BE/issues/146),
[FE #141](https://github.com/100-hours-a-week/KTB4-18th-FE/issues/141).
신규 제품 동작이나 migration을 추가하지 않는다.

## 환경과 실행

- Mac 로컬, MySQL9.7.0 Testcontainers 전용 DB, JVM/JDBC UTC.
- 검증 대상은 main runtime classpath의 실제 Spring Boot 앱을 별도 JVM으로 실행한다.
  HTTP·WebSocket은 loopback 임의 포트만 사용한다. 운영 URL/DB/사용자 메시지는 사용하지 않는다.
- 실제 위치 확인·입장·탈퇴·조회 API와 STOMP CONNECT/SUBSCRIBE/SEND를 사용한다.
  계정·좌표·메시지와 인증 키는 합성 자료다. 외부 Kakao 지역 조회만 로컬 HTTP stub으로 대체한다.
- server readiness는 실제 public terms HTTP200, 열람 readiness는 실제 CHAT_READY로 확인한다.
- 실제 프로세스를 destroyForcibly로 종료하고 PID 종료/새 PID를 확인한 뒤 같은 DB로 다시 실행한다.
  Spring context만 다시 만들거나 clock을 이동한 검증과 구분한다.

```sh
./gradlew spotlessCheck checkstyleMain checkstyleTest test bootJar --no-daemon
./gradlew chatProcessTest --no-daemon
CHAT_RECOVERY_FE_DIR=/absolute/path/to/meomuneum-frontend ./gradlew chatProcessTest --no-daemon
```

일반 test에는 프로세스 종료 테스트를 포함하지 않는다. 별도 chatProcessTest가 필수 검증이며
Docker가 없으면 클래스가 제외되므로 이를 실제 서버 검증 통과로 취급하지 않는다.
CHAT_RECOVERY_FE_DIR를 지정하면 FE의 실제 REST/WS·캐시 연동 테스트도 같은 서버에 실행한다.
지정하지 않으면 해당 선택 테스트는 제외된다.

## 기대 결과와 검증 기준

| 검증 | 기대 결과 |
| --- | --- |
| 짧은 단절 | 같은 view의 유예 중 메시지는 복구, snapshot 이후 메시지는 live 수신 |
| 페이지·재전송 | 고정 snapshot의 다음 페이지가 중복/누락 없이 이어지고 같은 client UUID 재전송은 저장1건 |
| 30초 초과 | 다른 기기가 같은 계정으로 살아 있어도 끊긴 탭의30초 이후 부재 메시지 제외, ABSENCE 경계 |
| 새 탭·다른 사용자·조작 | 새 view는 이전 메시지 없음, 다른 view cursor400, 다른 사용자/membership403, 변조400, 무인증401 |
| 퇴장 경합 | 먼저 권한을 얻은 조회200 또는 퇴장 이후403, 확정 퇴장 뒤 추가 조회403·다른 연결4100 |
| 제재·24시간 | 활성 제재403, 기록 구간 안의 만료 메시지도 목록 제외·본인 UUID 조회404 |
| 실제 강제 재시작 | 확인 기록의 메시지는 복구, 종료 후 삽입한 미확인 메시지는 제외, RESTART 종료 경계 |
| 기록 부재 | 삭제한 view의 조회403·CONNECT4103, 새 view로 미기록 과거 권한 생성 없음 |
| 실제 FE | 마스킹 본문만 저장/캐시, 복구 중 입력 차단·live 병합, 전체 퇴장4100, 같은 탭 재입장 캐시 복원·부재 제외 |

메시지24시간 경계는 기다리는 대신 합성 메시지와 구간 시작 시각을 DB에서 준비한다.
메시지는 열람 구간 안에 있게 유지해 단순 구간 불일치가 아니라 보관 기간 검증이 거부하는지 확인한다.
30초 단절은 실제 wall-clock을 기다리며 새 허용 구간을 임의로 연장하지 않는다.
퇴장 경합에서 이미 확정된200 응답을 소급 취소하지 않으며 종료 확정 후에는 추가 조회를 거부한다.

## 브라우저 연동

```sh
CHAT_BROWSER_FE_DIR=/absolute/path/to/meomuneum-frontend ./gradlew chatProcessTest --no-daemon
```

선택 검증은 합성 계정 정보가 있는 FE tests/fixtures/chatRecovery.local.json을 생성하고
최대30분간 서버를 유지한다. 이 파일은 FE Git 제외 대상이며 검증 종료 후 제거된다.
FE에서 npm run verify:chat:browser를 실행하고 /tests/fixtures/chatRecovery.html을 연다.
끝나면 build/chat-process-verification/browser-release 파일을 만들어 테스트에 종료를 알린다.
자동 FE와 브라우저 검증을 함께 수행하려면 두 FE_DIR 변수를 모두 지정한다.

기록은 build/chat-process-verification/와 Gradle XML/HTML 보고서에 남는다.
공유 문서에는 성공 항목·합성 메시지 화면만 옮기고 JWT·cursor·view ID는 포함하지 않는다.
테스트가 만든 프로세스/컨테이너만 종료하며 기존 performance/ 자료는 사용하거나 수정하지 않는다.

## 2026-10-14 결과와 제한

- 최종 Spotless·Checkstyle main/test·전체 test·chatProcessTest·bootJar 통과.
- 일반502개 중483개 통과·기존 조건부19개 제외, 프로세스6개 중5개 통과·브라우저 유지 선택1개 제외.
  브라우저 선택 실행은 별도로 실제 화면 검증과 명시적 release까지 완료했다.
- 실제 종료/재시작은2개 JVM PID와 같은 MySQL로 검증했다.
  확인한 메시지 복구·미확인 구간 제외·RESTART 기록·기록 부재 CONNECT4103·REST403을 확인했다.
- FE463개와 fixture TypeScript/빌드 통과. 실제 FE REST/WS·캐시1건은 같은 프로세스 서버에서 통과했다.
- 첫 시도: 테스트 JDBC URL 구분자 오류로 DB 접속 실패, 실제 HTTP Origin 누락으로403,
  새 테스트 Checkstyle 줄 길이 오류가 발생했다. 테스트 환경/코드를 보완하고 전체 재검증했다.
- 브라우저 유지15분이 만료돼 선택 단계가 실패했다.30분·명시적 release로 재검증했다.
  기능 테스트 통과 뒤 남아 있던 Checkstyle 오류도 수정 후 최종 전체 게이트에서 통과했다.
- 로그는 실행별 build/chat-process-verification/run-*/에 기록하고 최신 합성 결과는 result.json에 남긴다.
  이전 shared 로그를 쓰던 초기 시도의 요약은 위 실패 이력에 보존한다.
- 퇴장·조회는 실제 동시 요청으로 검사했다. 제재·만료 fixture 상태 변경과 조회도 동시에 실행하고 상태 확정 후 추가 조회/본문 반환이 거부되는지 확인했다.
  제재·만료 상태 변경은 격리 DB fixture 조작이다. 제재 등록 HTTP/운영 관리 흐름이나 모든 스케줄링 조합까지 검증한 것으로 확장하지 않는다.
- 실제 모바일/물리2기기/스크린 리더, 공연·다중 서버·부하·운영 배포는 미검증이다.
  상세 브라우저 결과·스크린샷은 FE docs/verification/chat-recovery-e2e.md에 있다.
