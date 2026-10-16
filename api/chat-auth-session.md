# 채팅 인증 세션 문맥

B1 / F1, 2026-10-16. 두 저장소의 `feature/chat-auth-session-context` 작업.

## 연결 계약

- 기존 로그인·재발급 access JWT의 서명된 `sub`와 `sid`를 사용한다. 새 device ID·DB 테이블·기기 지문을 만들지 않는다.
- 서버는 기존 JWT 서명·issuer·audience·type·만료 검사와 참여·방 권한 검사 후 연결을 등록한다.
- CONNECT의 선택 native header `auth_session_id`는 JWT sid와 정확히 일치해야 한다. 헤더만으로 소유권을 부여하지 않는다. 불일치·JWT sid 누락/비정규 UUID는 기존 `AUTH_REQUIRED` 오류로 거절하며 내용을 응답하지 않는다.
- FE는 현재 sub/sid를 연결 소유자로 캡처하고 CONNECT에 auth_session_id를 전달한다. 별도 REST 필드·쿠키·응답 이벤트는 추가하지 않는다.
- Authorization과 auth_session_id는 처리 후 native headers에서 제거한다. URL·브로드캐스트·사용자 안내에 싣지 않는다.
- 기존 FE도 서명된 sid가 있는 토큰이면 auth_session_id 없이 연결할 수 있다. sid 없는 이전 토큰은 재로그인 후 사용해야 한다.
- auth_session_id는 인증 로그인 세션, view_session_id는 탭별 열람 구간이다. 서로 대체하거나 복사하지 않는다.

## 수명과 책임

- ChatAuthSessionRegistry는 WebSocket 연결 ID → 사용자/인증 세션을 서버 메모리에 보관한다. 사용자와 sid를 함께 사용해 연결 집합을 조회한다.
- 동일 sid의 토큰 재발급은 같은 소속으로 식별한다. 다른 로그인 sid는 계정이 같아도 다른 소속이다.
- STOMP disconnect와 native socket close에서 현재 연결만 제거한다. 정리는 중복 호출해도 안전하다.
- 이 작업은 서명된 access token의 세션 소속을 식별한다. Hazelcast의 세션 폐기 상태 조회나 즉시 전파·기기별 로그아웃은 추가하지 않는다. 기존 access token 만료 정책과 전역 ChatLogoutEvent는 그대로이며 B7의 후속 범위다.
- 기기 경계는 기존 서버 인증 세션을 기준으로 기록한다. 같은 물리 기기의 다른 브라우저/프로필/시크릿 창을 묶는 정책을 확정하지 않는다.
- membership·정원·지역 이동·탭별 퇴장·열람 권한/캐시 정책은 후속 B2~B9/F2~F6의 범위다.

## 검증

- 원본 BE Spotless 통과. 원본의 compileJava는 선행 인증 변경에서 없는 AuthSessionController/LoginQueueController를 참조하여 실패한다.
- 원본 AuthState 테스트도 없는 LoginQueueAdmissionScheduler를 참조한다. 이 문제들을 이번 PR의 제품 코드에 섞지 않았다.
- 임시 사본 `/private/tmp/meomuneum-b1-verify-62go_q59`에서 AuthExceptionHandler의 두 누락 import/annotation 참조만 제거하고, 깨진 auth 테스트 디렉터리를 컴파일 대상에서 제외했다. 원본에는 반영하지 않았다.
- 사본의 Spotless·Checkstyle main/test 및 채팅/JWT 143개 중142개 통과·조건부1개 제외. 실제 MySQL9.7.0·WebSocket에서 동일 계정의 두 sid, 같은 sid 토큰 갱신/다중 연결, 한 연결 종료 정리를 확인했다.
- 사본에서 CHAT_RECOVERY_FE_DIR을 지정한 ChatViewStorageIntegrationTest21개 전부 통과. 실제 FE 클라이언트와 HTTP/STOMP 연결·복구·병합을 포함한다.
- 재현: 사본에서 `TZ=UTC CHAT_RECOVERY_FE_DIR=<FE 경로> ./gradlew test --tests '*ChatViewStorageIntegrationTest' --rerun-tasks --no-daemon`.
- 원본 전체 test·bootJar 통과나 운영 배포 완료로 취급하지 않는다. 선행 인증 빌드 오류 해결 후 원본 전체 검사를 다시 해야 한다.

## 적용과 복구

- 선행 인증의 signed sid 토큰 발급이 필요하다. 선택 헤더를 사용하는 새 FE는 구 BE에서도 기존 인증으로 연결할 수 있지만 세션 식별 보장은 새 BE가 필요하다.
- 새 BE를 적용한 뒤 FE를 적용하고 같은 세션 갱신·인증 변경 중 복구 취소를 확인한다.
- 롤백 시 FE/BE를 기존 버전으로 되돌린다. DB 변경·마이그레이션은 없다.
- GitHub 이슈 번호는 미확인이다. 이 대화에서 작성한 B1/F1 이슈 초안을 기준으로 작업했으며 외부 이슈 생성·PR·push는 수행하지 않았다.

## B4 이후의 참여 소유권

B4에서 REST 입장과 CONNECT를 DB의 `auth_session_id`에 연결했다. 같은 계정의 다른 sid는 같은 membership을 공유하지 않는다. 아래 B1 검증 기록은 당시 범위이며, 현재 참여 계약·호환성은 [기기별 참여 문서](chat-device-membership.md)를 따른다.
