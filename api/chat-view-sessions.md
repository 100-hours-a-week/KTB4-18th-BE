# 지역 채팅 탭별 열람 구간

2026-10-13 후속: BE #145의 실제 조회·CHAT_READY·cursor는
`chat-message-recovery.md`와 `chat-message-query.openapi.yaml`에 구현 계약을 기록했다.
아래의 후속/미구현 표기는 #143 완료 당시의 범위를 설명한다.

BE #143. [이슈](https://github.com/100-hours-a-week/KTB4-18th-BE/issues/143).
이 문서는 열람 문맥·참여 구간 기록과 재시작 검증에 한정한다.
history/recovery REST, snapshot·cursor 및 FE 복구·캐시는 BE #145 / FE #136·#140 후속 작업이다.

## 연결 계약

기존 STOMP CONNECT의 Authorization Bearer, room_id, membership_id는 유지한다.
선택 헤더 `view_session_id`를 추가한다.

- 최초 접속·새 브라우저 탭·새 기기·새 membership은 헤더를 생략한다.
- 서버는 REST 입장이나 CONNECT 시점에 열람 구간을 시작하지 않는다.
- 단순 브로커의 방 topic 구독이 실제 등록됐는지 확인한 뒤 서버가 UUID를 발급한다.
- 해당 구독에만 다음 이벤트를 보낸다. 다른 사용자나 같은 계정의 다른 연결에 방송하지 않는다.

```json
{"type":"CHAT_VIEW_READY","data":{"view_session_id":"550e8400-e29b-41d4-a716-446655440000"}}
```

- 같은 탭의 재연결에는 발급받은 view_session_id를 CONNECT에 전달한다.
- 사용자·방·활성 membership·만료·제재를 다시 검사한다. 알 수 없는 UUID를 새로운 과거 문맥으로 등록하지 않는다.
- 같은 view를 다른 살아 있는 연결에서 동시에 사용하면 거부한다.
- 새 탭/기기의 독립 문맥은 새 UUID 발급으로 구분한다. UUID는 인증이나 물리적 기기 증명이 아니다.
  FE는 이 식별자를 다른 탭·기기에 복사·동기화하지 않아야 한다.
- 검증 실패는 `CHAT_ROOM_403_VIEW` / `chat view unavailable`이며,
  CONNECT 시에는 close 4103 `CHAT_VIEW_UNAVAILABLE`로 종료한다.
- 헤더가 없는 기존 FE도 기존 실시간 메시지를 사용할 수 있다. 새 이벤트는 알 수 없는 타입으로 무시 가능하다.
- 기존 구독 receipt는 유지한다. `CHAT_VIEW_READY`는 열람 문맥 발급 이벤트이며
  #145의 recovery_cursor·CHAT_READY를 대체하거나 복구 완료를 의미하지 않는다.

## 데이터와 구간 경계

- chat_view_sessions: 서버 발급 UUID, 사용자·방·membership, 생성·마지막 확인·종료 시각.
- chat_view_intervals: 실제 구독 시작, 시작 직전 메시지 ID, 확인된 시각·메시지 ID,
  종료 시각·메시지 ID·사유, 기록한 서버 프로세스 UUID.
- 시각은 서버 UTC DATETIME(6)이며 클라이언트 시각을 받지 않는다.
- 실제 구독 시작 전의 메시지 ID까지 제외한다. 같은 마이크로초에 저장된 메시지도 ID로 구분한다.
- 열람 구간 변경과 기존 메시지 저장·입퇴장은 단일 서버 presence 잠금으로 순서를 맞춘다.
- 의도적인 구독 해제는 즉시 종료한다. 여러 방 topic 구독이 있으면 마지막 구독 해제에 종료한다.
- 일반 단절은 탭별로 최초 30초까지 허용한다. 중복 disconnect 알림은 유예를 연장하지 않는다.
- 30초 미만에 같은 view로 실제 재구독하면 구간을 잇는다.
  정확히 30초 이후에는 이전 구간을 종료하고 새 구독부터 별도 구간을 만든다.
- 유예 종료 시각과 같은 시각의 메시지부터 제외한다. 30초 이후 부재 구간을 연결하지 않는다.
- 다른 탭의 연결이 살아 있어도 끊긴 탭의 유예를 연장하지 않는다.
- 실제 퇴장·지역 전환·로그아웃·기존 제재 추방은 모든 관련 view와 유예 구간을 즉시 종료한다.
  이전 view를 새 membership에 재사용할 수 없다. 마스킹 정책을 신규 자동 제재로 되돌리지 않는다.

## 권한 검증과 후속 조회 연결

`ChatViewLifecycle.authorize(userId, roomId, membershipId, viewId)`는 현재 실제 구독 연결,
사용자·방·membership·제재·view 소유권을 검증하고 기록된 구간을 반환한다.
저장된 UUID나 DB의 활성 membership만으로 조회 권한을 부여하지 않는다.

`ChatViewInterval.permits`는 구간 경계만 판정한다. #145 조회에서는 별도로 인증과
메시지 전송 시각 기준 24시간 만료를 검사하고, 페이지마다 권한을 다시 검증해야 한다. 구간 검증과 실제 조회는 같은 presence 잠금 범위에서 수행해
퇴장과 조회 사이의 경합을 막아야 한다. 오래된 외부 트랜잭션의 스냅샷을 이어 쓰지 않도록
열람 기록 서비스는 별도 트랜잭션으로 최신 참여 상태를 검증한다.
지금은 메시지 조회 endpoint나 cursor를 추가하지 않는다.

## 서버 재시작과 기록 실패

- 활성 구독의 시각·메시지 ID를 5초마다 DB에 확인 기록하고, 권한 조회 시에도 갱신한다.
- 시작 시 이전 프로세스가 남긴 열린 구간을 마지막 확인 시각·메시지 ID에서 종료한다.
  중단 시각을 추정해 임의로 30초를 추가하지 않는다.
- 이미 DB에 남은 단절 유예 종료 시각은 보존한다.
- 재접속·재구독은 활성 계정·membership·제재·정원 검증을 다시 통과해야 한다.
- view 기록이 없거나 만료되면 이전 UUID의 재사용을 거부한다.
- disconnect 기록 실패로 열린 구간이 남으면 같은 프로세스에서도 마지막 확인 경계에서 종료하고 새 구간을 만든다.
- 퇴장 구간 기록 실패 시에도 메모리 문맥을 제거하며, 이미 종료된 membership 검증으로 과거 조회를 거부한다.
- 비정상 중단 시 마지막 확인 이후 아직 기록하지 못한 메시지는 복구에서 제외될 수 있다.
  평상시 확인 간격은 5초이고, DB 오류가 있으면 누락 범위가 커질 수 있다. 검증되지 않은 부재 메시지로 보충하지 않는다.
- 기록 실패 로그는 사건 종류와 예외 클래스만 남긴다. UUID·인증·원문 메시지는 남기지 않는다.

## 만료와 정리

- 마지막 확인 이후 24시간이 지난 view는 즉시 재사용 불가다.
- 종료된 구간은 종료 후 24시간부터 시간별 정리 작업에서 삭제한다.
- 확인이 끊긴 view도 마지막 확인 후 24시간부터 삭제한다. 하위 구간은 FK cascade로 삭제한다.
- 현재 구독 중인 문맥은 주기적 확인으로 유지한다. 구간이 오래 열려 있어도 메시지 자체의 24시간 보관 정책은 유지한다.
- 정리 작업 실패는 다음 시간별 작업에서 재시도하며, 권한 만료가 물리 삭제를 기다리지 않는다.
- 이 기록은 인증·참여 복구용 메타데이터다. 메시지 본문·정확한 GPS·인증 토큰은 저장하지 않는다.

## 적용과 복구 절차

1. 기존 적용 migration은 수정하지 않고 V20261013024123을 먼저 적용한다.
   두 테이블을 추가하며 기존 메시지·참여 이력을 변경하거나 과거 구간을 채우지 않는다.
2. 단일 BE 서버를 배포한다. 새 구독부터 기록한다. 이전 서버의 미기록 참여에 과거 열람 권한을 부여하지 않는다.
3. FE #136에서 같은 탭의 view 저장·CONNECT 재전달을 연동하고, BE #145에서 실제 복구 조회를 제공한다.
4. 되돌릴 때 구버전 BE로 복귀하고 추가 테이블·Flyway 이력을 유지한다.
   테이블 제거 또는 적용 migration 삭제를 롤백으로 사용하지 않는다.
5. 비정상 중단 테스트는 기존 프로세스가 남긴 열린 구간을 준비한 뒤 새 storage 프로세스 식별자로 초기화한다.
   실제 애플리케이션 프로세스 종료·재기동 E2E는 #146에서 수행한다.

## 검증 범위

- MySQL: 실제 구독 전 제외, 같은 마이크로초 ID 경계, 정확히 30초/장기 단절,
  한 탭만 단절, 전체 퇴장, 소유권·기록 부재·제재, 재시작·마지막 확인,
  기록하지 못한 단절, 만료·cascade 정리, 메시지 삭제 후 경계 유지.
- WebSocket: 실제 topic 구독 등록 후 해당 연결에만 UUID 전달 및 기존 채팅 회귀.
- lifecycle: private 구독의 권한 미생성, 마지막 unsubscribe, 동일 UUID 동시 사용 거부,
  중복 단절, 종료 후 권한 거부.

### 2026-10-13 실행 결과

- 격리된 MySQL 9.7.0을 TEST_DB_URL / TEST_DB_USERNAME / TEST_DB_PASSWORD로 연결했다.
  제품 DB나 기존 performance 자료는 변경하지 않았다.
- `./gradlew spotlessApply spotlessCheck checkstyleMain checkstyleTest test bootJar --no-daemon` 통과.
- 전체 495개 중 476개 통과, 실패·오류 0개, 기존 환경 조건으로 제외된 테스트 19개.
- 새 ChatViewStorageIntegrationTest 14개와 ChatViewLifecycleTest 4개 모두 통과.
- 기존 실제 HTTP/WebSocket 채팅 통합 테스트 16개 통과.
- 조건부 제외: MusicRecordApiDatabaseIntegrationTest 5개,
  SessionCookieScopeHttpIntegrationTest 1개, SignupLocalProfileIntegrationTest 8개,
  SessionCookieSecureHttpIntegrationTest 1개, RefreshCsrfProtectionIntegrationTest 3개,
  MusicRecordTestProfileIntegrationTest 1개. 이 결과를 해당 환경까지 검증한 것으로 취급하지 않는다.
- 재시작은 동일 DB에 새 storage 인스턴스·프로세스 식별자를 사용한 검증이다.
  실제 서버 프로세스 종료·재시작 E2E와 운영 배포 검증은 이번 실행에 포함하지 않는다.
