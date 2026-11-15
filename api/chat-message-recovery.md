# 지역 채팅 메시지 조회·재연결 복구

[BE #145](https://github.com/100-hours-a-week/KTB4-18th-BE/issues/145)와
[FE #136](https://github.com/100-hours-a-week/KTB4-18th-FE/issues/136)의 구현 계약.
REST 상세는 `chat-message-query.openapi.yaml`, 열람 구간은 `chat-view-sessions.md`를 따른다.

## 연결과 복구

1. 최초 CONNECT는 기존 인증·room_id·membership_id를 보낸다. 재연결은 같은 탭에서 받은
   view_session_id와 실제 반영한 마지막 서버 message_id를 last_message_id로 함께 보낸다.
   마지막 ID가 없으면 0이다. 다른 탭으로 복사하지 않는다.
2. 브로커 방 구독의 실제 등록 확인 후 기존 CHAT_VIEW_READY와 다음 CHAT_READY를 해당 연결·구독에만 보낸다.
   receipt만으로 복구 완료를 판단하지 않는다.

```json
{"type":"CHAT_READY","data":{"room_id":1,"membership_id":2,"view_session_id":"550e8400-e29b-41d4-a716-446655440000","recovery_cursor":"opaque-signed-cursor","snapshot_message_id":42}}
```

3. FE는 방과 개인 이벤트의 receipt 및 CHAT_READY를 모두 받은 뒤 recovery_cursor로 조회한다.
   복구 완료 전 실시간 MESSAGE·ACK를 임시 보관하며 입력을 막는다.
4. 각 페이지는 동일 snapshot까지 허용된 구간의 메시지만 ID 오름차순으로 조회한다.
   next_cursor가 null일 때까지 받은 후 실시간 버퍼와 message_id로 병합한다.
   client_message_id가 같은 본인 저장 메시지는 ACK가 유실돼도 전송 대기를 정리한다.
5. 단절 시 요청을 취소하고 이전 연결 응답을 폐기한다. 퇴장·지역/방 변경·새 참여의 응답도 반영하지 않는다.
   복구 실패 시 자동 REST 재입장으로 새 문맥을 만들지 않고 입력을 막은 채 수동 복구를 제공한다.

## 권한·페이지·공백

- 인증 사용자·활성 membership·방·view 소유권·실제 살아 있는 구독·활성 제재를 매 페이지 검증한다.
  UUID나 cursor만으로 조회할 수 없다. 퇴장과 조회는 기존 단일 서버 presence 잠금으로 순서를 맞춘다.
- 서버 기록 구간과 생성 시각 기준 최근24시간을 SQL에서 먼저 적용한 뒤 최대50개씩 페이지 처리한다.
  미참여·만료 메시지의 본문·개수는 응답에 포함하지 않는다. 닉네임은 현재 사용자 닉네임이다.
- history는 ID 내림차순, recovery는 ID 오름차순이다. 클라이언트 시각은 정렬·권한 기준으로 쓰지 않는다.
- cursor는 HMAC-SHA256으로 사용자·방·membership·view·mode·snapshot·position·만료를 묶는다.
  다음 페이지에서도 snapshot·만료는 유지한다. 변조·문맥 변경·모드 변경·24시간 만료는400이다.
  서명 키는 기존 auth.jwt.secret이며 용도를 분리한 접두사를 사용한다. 키 변경 시 기존 cursor는 무효다.
- 메시지 만료는 물리 삭제 시점과 무관하게 매 페이지 적용한다. 긴 조회 중 만료된 메시지는 제외된다.
- by-client-id는 본인이 보낸 메시지만 같은 권한으로 조회한다. 다른 작성자·부재·만료·존재하지 않는 UUID는 동일404다.
- gaps는 ABSENCE 또는 RETENTION 경계만 제공한다. 제외 메시지 수나 내용은 제공하지 않는다.
  FE는 참여하지 않은 시간과24시간 보관 만료 안내선을 표시한다. 메시지 없는 공백도 표시한다.
- 30초 초과 단절·재시작은 #143에서 검증된 경계만 복원한다. 계속 연결된 다른 탭으로 부재 구간을 채우지 않는다.

## 적용·복구

- #143 마이그레이션이 적용된 BE를 먼저 배포하고 FE를 배포한다. 이번 변경은 추가 DB migration이 없다.
- BE는 기존 CONNECT·실시간·CHAT_VIEW_READY를 유지한다. 새 FE는 CHAT_READY 계약이 필요한 버전이다.
  구버전 BE로 복귀해야 한다면 FE도 이전 버전으로 함께 복귀한다. 저장 테이블·이력은 유지한다.
- 공동 재생·공연 채팅·이전 직접 열람 메시지의 sessionStorage 캐시는 이번 범위에 포함하지 않는다.
  실제 프로세스 종료/재시작·재입장 UX의 확장 검증은 후속 이슈에서 진행한다.

## 검증

- MySQL9.7.0의 실제 테이블에서 구독 전·부재·만료 제외, 고정 snapshot의 페이지 경계,
  history/recovery 순서, 타 문맥·모드·변조 cursor, 매 페이지 퇴장·제재·단절 거부,
  by-client-id 본인 제한 및 실제 HTTP 인증/필수 헤더 검증을 수행한다.
- 선택 연동 테스트는 `CHAT_RECOVERY_FE_DIR=<FE 절대 경로> ./gradlew test --tests '*ChatViewStorageIntegrationTest'`
  로 실행한다. 테스트 서버가 생성한 합성 인증과 실제 HTTP/WebSocket으로 FE 클라이언트를 실행한다.
  단절 누락 복구·복구 중 live 병합·전송 차단·퇴장 후 지연 응답 폐기를 검증한다.
  이 환경변수가 없으면 해당1건은 제외된다. 운영 서버·사용자 데이터를 사용하지 않는다.

### 2026-10-13 실행 결과

- 격리 MySQL9.7.0, JDBC serverTimezone=UTC, JVM TZ=UTC에서
  Spotless·Checkstyle main/test·전체 test·bootJar 통과.
- 전체502개 중483개 통과·기존 조건부19개 제외·실패/오류0.
  ChatViewStorageIntegrationTest21개(기존14개·추가 조회/HTTP6개·실제 FE 연동1개),
  기존 HTTP/WebSocket16개 모두 통과했다.
- FE format:check·lint·446개 단위/컴포넌트 테스트·build 통과.
  일반 FE 실행에서 실제 서버 연동1건은 제외되며 BE 실행에서 별도로 통과했다.
- 최초 검증은 테스트 코드 스타일4건과 회원가입/약관3건이 실패했다.
  스타일은 수정했으며, JDBC UTC와 JVM KST 불일치로 약관 시각 경계가 다르게 계산되는
  테스트 환경 문제는 TZ=UTC로 맞춰 전체 재검증했다. 회원가입 제품 코드나 운영 DB를 변경하지 않았다.
- 첫 연동 시도는 jsdom Event와 Node WebSocket Event의 실행 환경 충돌로 실패했다.
  DOM이 필요 없는 연동 파일을 node 환경으로 분리한 뒤 실제 서버 연동과 전체 검사를 다시 통과했다.
- 기존 FE 빌드의500kB 번들 경고는 남아 있다. 실제 브라우저 수동 QA·운영 배포·서버 프로세스 재시작 E2E 결과로 확대하지 않는다.
