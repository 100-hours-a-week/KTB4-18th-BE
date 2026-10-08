# 지역 채팅 메시지 이벤트 계약

BE #141 / FE #134. 인증·입퇴장은 `chat-participation.openapi.yaml` 참조. 저장소 밖 메시지 조회/복구 및 공동 재생은 이 작업 범위에서 제외.

## 연결과 전송

STOMP 1.2 `/ws`. CONNECT의 Authorization Bearer, room_id, membership_id로 계정과 활성 참여 검증. 토큰을 URL·로그에 남기지 않는다.

- 구독: `/topic/chat-rooms/{room_id}` 및 `/user/queue/chat-events`.
- 두 구독의 receipt가 확인된 뒤 FE 전송 활성화. 서버는 실제 방 구독이 등록된 연결만 전송 허용.
- SEND: `/app/chat-rooms/{room_id}/messages`, JSON `{"client_message_id":"UUID","content":"텍스트"}`.
- 본문을 JavaScript trim과 같은 공백 규칙으로 정리한 뒤 1~300 Unicode code points 검증. 길이 초과 자동 절삭 없음.
- 사용자별 1초에 1개(설정 가능). 성공한 메시지만 빈도에 계산. 활성 membership 중 같은 내용의 세 번째 메시지부터 차단. 일시 재연결/30초 정원 유예 만료로 반복 횟수 초기화 없음. 실제 퇴장/새 membership에서 초기화.
- 성공한 UUID 재시도는 먼저 검증하여 빈도/반복 계산 없이 ACK만 반환. 계정+UUID 중복 저장·broadcast 없음. 다른 내용/다른 참여/서버 재시작 전 UUID 재사용은 CLIENT_ID_CONFLICT. 24시간을 넘긴 재시도 보장 없음.
- 서버가 DB 커밋 후 수락 시점 구독자에게 전달. 미구독·다른 방·퇴장·수락 후 입장한 연결은 받지 않음. 구독 시 과거 메시지 재생 없음.

## 이벤트

모두 `{ "type": "이벤트명", "data": {...} }`. 시각은 UTC ISO-8601. author/client_message_id는 서버가 인증 계정과 저장 결과로 설정한다.

`CHAT_MESSAGE` 방 topic의 data:

```json
{"message_id":1,"client_message_id":"550e8400-e29b-41d4-a716-446655440000","room_id":700,"user_id":7,"nickname":"작성자","content":"안녕하세요","created_at":"2026-10-08T00:00:00Z"}
```

`CHAT_ACK`: 전송 연결의 개인 queue에 `{membership_id, message: CHAT_MESSAGE의 data}`. ACK와 broadcast 도착 순서는 보장하지 않음. message_id로 목록 중복 제거, ACK의 UUID로 전송 결과 확정.

`CHAT_REJECTED`: 전송 연결 개인 queue에 `{room_id, membership_id, client_message_id, reason, retry_after_ms}`. 유효하지 않은 UUID는 빈 문자열로 반환하며 임의 입력을 반사하지 않음.

reason: EMPTY_CONTENT, CONTENT_TOO_LONG, INVALID_CONTENT, INVALID_REQUEST, INVALID_CLIENT_ID, RATE_LIMIT, DUPLICATE_CONTENT, URL_NOT_ALLOWED, PERSONAL_INFORMATION, NOT_READY, DESTINATION_DENIED, CLIENT_ID_CONFLICT, RETRY_UNAVAILABLE, SEND_FAILED. 일반 위반은 메시지만 차단하고 연결/참여 유지. SEND_FAILED나 ACK 미확인 시 같은 UUID로 재시도 가능.

`CHAT_BANNED`: `{room_id, membership_id, client_message_id, reason: PROFANITY|OBSCENITY, banned_until}`. 욕설/음란성 목록과 예외는 `chat-moderation-policy.md`. 메시지 저장·broadcast 없이 제재 커밋, 계정의 활성 참여 종료 및 모든 연결 close **4101**, reason `CHAT_BANNED|{banned_until}`. close가 이벤트보다 먼저 도착해도 종료 시각 표시 가능.

제재 중 REST 입장 403 `chat use banned`, data `{banned_until}`. STOMP CONNECT/SUBSCRIBE/SEND 거부, ERROR의 banned_until 또는 close 종료 시각 제공. FE 입력과 재연결 중지. 종료 시각부터 제재 비활성.

## 프론트 처리와 검증

React text로 본문 표시. 입력 라벨, Enter 전송/Shift+Enter 줄바꿈/한글 조합 Enter 보호. 준비 전·공백·300자 초과 입력 차단. 재연결은 대기 항목을 미확인 상태로 유지하며 명시적 재전송에 기존 UUID 사용. 퇴장/새 입장 시 메시지·대기 상태·타이머·지연 이벤트 초기화. 본문·개인정보·토큰 로그 없음.

검증: 실제 WebSocket 2인 송수신, 다른 방 격리, 늦은 구독 과거 메시지 미전달, UUID ACK 재시도, 일반 위반 연결 유지, 반복 카운트 재연결 유지, 모든 연결 제재 종료와 7일 경계. FE 이벤트 파싱·ACK/broadcast 역순 중복 제거·UUID 재시도·퇴장 지연 이벤트·안전한 본문 표시·키보드/조합 입력 검증.
