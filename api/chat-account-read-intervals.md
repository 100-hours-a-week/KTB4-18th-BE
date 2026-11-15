# B8 계정·방별 메시지 열람 구간 산출

## 범위와 진입점

`ChatViewLifecycle.authorizeAccount(userId, roomId, membershipId, viewId)`가 내부 진입점이다.
현재 탭의 실제 구독·presence·활성 참여·방·view 소유권·사용자·제재 상태를 먼저 검증한다.
이어 같은 계정·방의 실제 활성 구독을 checkpoint하고 DB 기록을 읽는다.
이미 종료된 참여나 view의 checkpoint는 갱신하지 않는다.

`ChatAccountReadIntervals`는 userId/roomId, UTC microsecond asOf, 불변 intervals를 담는다.
`permits(messageId, sentAt)`는 24시간 보관 범위와 asOf를 확인한 뒤 각 기록의 허용 여부를 OR로 판정한다.
최소 시작부터 최대 종료까지의 범위로 압축하지 않는다. 겹치는 기록을 보존해야
시각과 메시지 ID의 조합에서 발생하는 부재 구간을 잘못 허용하지 않는다.
결과 목록에 겹치는 근거가 있어도 권한 판정은 Boolean 합집합이다.

## 산출 규칙

- `chat_view_sessions.user_id/room_id`로 계정과 방을 한정하고 membership의 사용자·방 일치도 검증한다.
- 여러 탭·sid·종료된 membership/view를 포함한다. 과거 view는 이력 근거이며 현재 요청/재연결 문맥으로 재사용하지 않는다.
- 예약만 있고 구독 기록이 없으면 구간이 없다.
- 열린 구간은 verified_at/verified_through_message_id까지만 사용한다.
- 종료된 참여/view에 남은 열린 기록이 종료 시각 이후 검증됐으면 사용하지 않는다.
- 구간 종료 저장 실패로 단절 유예가 남아 있어도 DB에 확정된 참여/view 종료 시각에서 제한한다. LEFT로 확정되지 않은 ID 구간이 참여 종료를 넘으면 사용하지 않는다.
- 명시적 종료/RESTART 등의 확정 ID 끝 경계는 inclusive, 시작 ID는 exclusive다.
- DISCONNECTED의 NULL end_message_id는 ends_at 직전까지만 허용한다. 정확히30초는 제외한다.
- 모든 기기가 부재했던 구간은 허용하지 않는다. 다른 활성 탭이 참여한 구간은 같은 계정의 이력에 포함한다.
- 메시지 전송 시각은 cutoff(asOf-24시간)보다 커야 하고 asOf 이하여야 한다.
- 기존 시간별 구간 정리와 서버 재시작 checkpoint 처리, 보관 기간은 바꾸지 않는다.

## B9 연동 시 준수 사항

이번에는 기존 history/recovery/client-message-id 조회와 CHAT_READY/cursor/gap을 바꾸지 않았다.
HTTP에서 다른 기기의 과거 메시지가 바로 조회되는 작업까지 완료한 것이 아니다.
B9에서 계정 이력 조회에 이 진입점을 사용하고 페이지마다 현재 참여·구독 권한을 재검증해야 한다.
SQL로 소비할 때도 각 구간의 시각+ID predicate를 OR로 연결하고 전체 cutoff/asOf 제한을 적용해야 한다.
구간별 조회 결과를 단순 연결하면 중복 메시지가 생길 수 있다. 메시지 ID 중복 제거·순서·cursor는 B9 책임이다.
기존 같은 탭 단절 복구와 계정 이력의 범위·snapshot·gap 의미를 구분해 적용한다.
현재 API의 단절 복구 권한은 계속 탭별이다.

## 영향과 적용

공개 REST/STOMP 계약·환경 변수·의존성·DB 스키마·Flyway·FE 변경은 없다.
신규 내부 산출 로직은 B9에서 소비하기 전까지 기존 API 결과에 연결되지 않는다.
기존 checkpoint는 활성 membership/view에만 기록하도록 제한했다.
B7의 인증 담당자 이벤트 발행 의존성은 그대로 남는다. 인증 코드나 로그아웃 API는 수정하지 않았다.
기능 롤백은 내부 산출 호출을 제거하면 되며 데이터 역마이그레이션은 없다.

## 검증

- 단위 테스트: 시각/ID 조건의 합집합, 동일 microsecond 경계, strict24시간·grace·asOf, 불변 결과.
- MySQL 통합: 다중 sid/탭, 로그아웃 후 새 sid, 모든 기기 부재 제외, 다른 사용자·방·불일치 membership 격리, 활성 다른 view checkpoint와 미구독 예약 제외, 종료된 참여 checkpoint 차단, 정확히30초·restart checkpoint, 현재 제재/퇴장/미구독 거부, 종료 저장 실패의 남은 유예 제한.
- 실제 별도 JVM 재시작 검증 6개 중4통과/선택 FE·브라우저 연동2개 제외. 종료된view와checkpoint 경계의 기존 복구 회귀를 확인했다. 신규 계정 이력 HTTP API까지 검증한 것은 아니다.
- 전체 프로젝트 test는 재실행하지 않았다. 앞선 B7에서 외부 DB 및 인증 테스트 실패·Hazelcast 초기화 지연이 확인됐으며 이번에는 채팅 및 실제 서버 재시작 범위를 검증했다.
- 원본 전체 Spotless는 기존 인증/설정5개 파일 위반, Checkstyle test는 기존 LocalMockLoginServiceTest.java:40의121자 위반이다. 인증 파일은 수정하지 않았다.
- 최종 채팅 회귀·build·정적 검사 집계는 아래 추가 기록을 따른다.

### 최종 결과

- 채팅 일반187개 중184통과/조건부3제외/실패0. 신규4개 단위·6개 MySQL 통합 사례 포함.
- bootJar·Checkstyle main·채팅 Java 전체 Spotless 통과. Checkstyle test의 채팅 위반0, 기존 인증 길이1건으로 전체 태스크 실패.
- 최종 XML·summary: build/b8-account-interval-verification/final-chat/ 및 summary.json. 별도 JVM 검증 XML은 process/.
- 마지막 계정 이력 SQL 보완 뒤 일반 채팅 회귀와 빌드를 다시 확인했다. 기존 API가 이 SQL을 아직 소비하지 않으므로 실제 프로세스 재시작 검증은 기존 탭 복구/checkpoint 회귀의 증빙으로 구분한다.
- FE·인증 코드·공개 API·DB 스키마·운영 데이터·배포는 변경하지 않았다.
