# B9 계정 참여 이력 기반 메시지 조회

## 조회와 현재 요청 권한

GET /api/v1/chat-rooms/{room_id}/messages의 mode=history는 B8 계정·방 구간의 OR 합집합으로 조회한다.
같은 계정의 다른 탭/sid, 종료된 참여와 재로그인 전 기록을 포함한다.
mode=recovery와 CHAT_READY.recovery_cursor는 기존 현재view 구간을 유지한다.
by-client-id는 같은 계정·방 참여 구간 안의 본인 메시지만 반환한다. 다른 작성자/부재/만료는404다.

요청 JWT의 검증된 sub/sid와 현재 membership을 맞춘다. 다른 sid의 현재 view/membership은 사용할 수 없다.
legacy/malformed sid는401, 타sid/종료 membership은403이다. 채팅 로컬 종료 세션도 거절한다.
매 페이지 실제 현재 구독·presence·활성 참여·방·view 소유권·제재를 재검증한다.
과거 view를 현재 권한으로 재사용하지 않는다. 인증 저장소·세션 폐기는 변경하지 않는다.

## snapshot과 페이지

history는 ID 내림차순, recovery는 오름차순이며 최대50개씩 조회한다.
허용 구간 OR을 단일 SQL에 적용하므로 겹치는 구간이 있어도 메시지가 중복되지 않는다.
history cursor에 최초 조회 asOf(UTC microsecond)를 서명하고 메시지 ID snapshot과 함께 고정한다.
user/room/current membership/view/mode/position/expiry 결합도 유지한다.
후속 페이지는 snapshot ID/asOf를 넘는 새 메시지를 포함하지 않는다.24시간 cutoff는 매 요청의 현재 시각으로 갱신한다.
권한 축소·퇴장·제재·retention은 cursor로 되살릴 수 없다.
이전 history cursor는 asOf가 없어400이며 cursor 없이 다시 조회한다. 기존 recovery cursor는 수용한다.

## 부재 안내

history gaps는 계정의 구간 전체를 시작 순서로 훑으며 가장 먼 종료 경계를 유지한다.
짧은 구간이 긴 구간 안에 포함된 경우 잘못된 ABSENCE를 만들지 않는다.
다른 탭/기기가 참여한 동안에는 계정 부재로 표시하지 않는다.
24시간 보관 안내는 해당 계정·방의 남아 있는 가장 이른 view 기록을 기준으로 판단한다.
숨겨진 메시지 내용·개수는 반환하지 않는다.

## FE와 적용

F6는 CHAT_READY 이후 탭 복구와 account history의 최신200개를 불러온다.
페이지가 완성되기 전 전송을 막고 live/ACK를 버퍼링한다. 성공한 서버 이력으로 로컬 임시 캐시를 교체한 뒤
현재view 복구와 live를 ID로 병합한다. 재연결 때도 서버 이력을 다시 검증한다.
gap 안내는 서버 계정 history 결과를 사용하며 탭별 recovery gap을 추가하지 않는다.

공개 URL/성공 응답 필드는 유지한다. history/by-client-id 의미 확대와 sid 검사·opaque history cursor 호환성 변경이 있다.
B9를 먼저 반영하고 F6를 적용한다. 구버전 BE는 account history를 제공하지 못하므로 새 FE와 조합하지 않는다.
롤백 시 FE와BE를 함께 고려하며 cursor를 폐기하고 새 연결/조회로 재시작한다.
DB/Flyway·환경 변수·새 의존성은 없다. B7 인증 담당자 이벤트 발행 의존성은 별도로 유지한다.

## 검증

최종 결과는 아래 기록을 따른다.

- 실제 MySQL/HTTP/STOMP와 실제 FE Node 클라이언트를 포함한 채팅190개 전부통과·제외0.
- 다른 sid·재로그인 이력, 부재 제외, history/recovery 범위 분리, 본인 client-id 조회, 현재JWT sid 소유권, snapshot/페이지·중첩 gap을 검증했다.
- 실제 별도 JVM 프로세스6개 중5통과/선택 브라우저 유지1개 제외. 기존 재시작/단절 복구와 실제 FE REST·cache 연동을 포함한다.
- 추가 codec 단위2개는 별도 실행에서 모두 통과했다. 구형 recovery 호환·history asOf 누락/미래·서명 변조 거부를 확인한다.
- bootJar·Checkstyle main·채팅 Java Spotless 통과. 원본 Checkstyle test는 기존 인증 LocalMockLoginServiceTest.java:40 길이121자 위반1개로 실패·채팅 위반0.
- 전체 Spotless의 기존 인증/설정5파일 위반과 전체 test의 외부DB·인증 실패/지연은 이전 검증 기록을 유지한다. 인증 코드를 고치거나 전체 suite 통과로 표시하지 않는다.
- 증빙 build/b9-account-history-verification/test/·chatProcessTest/. 원본 process XML은 브라우저 유지 실행 시 덮어써지므로 별도 복사해 보존했다.
- 실제 물리 기기·운영 로그인·GPS는 미검증이다. 제품 인증·DB·환경 변수·배포 변경은 없다.
