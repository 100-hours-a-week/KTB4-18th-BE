# 인증 세션별 채팅방 참여

B4는 검증된 access JWT의 사용자와 sid를 DB 참여에 연결한다.
작업 브랜치는 `feature/chat-device-membership`, 시작 HEAD는 B3 PR #199가 병합된 `fd0f888`다.

## 참여와 연결

- 같은 sid로 같은 방에 다시 입장하면 같은 활성 membership과 HTTP 200을 반환한다. 새 참여는 201이다.
- 같은 계정이라도 다른 sid는 별도 membership을 생성한다. 같은 방의 정원은 B3에 따라 계정당 1명이다.
- 다른 sid는 별도 지역 방에도 참여할 수 있다. 새 세션 입장이 기존 세션의 참여·연결을 종료하지 않는다.
- CONNECT는 JWT의 사용자·sid와 DB membership의 사용자·sid·방·활성 상태를 모두 확인한다. 같은 계정의 다른 sid나 NULL sid 참여로 연결할 수 없다.
- 같은 sid의 access token 갱신은 같은 참여를 사용한다. 연결 후에는 검증된 연결과 membership을 기준으로 구독·전송·복구의 권한을 확인한다.
- DELETE 퇴장도 사용자·sid·방·membership 소유권을 확인한다. 다른 sid의 참여는 403이며, 같은 sid로 이미 종료한 참여를 다시 퇴장시켜도 새 참여에는 영향을 주지 않는다.

REST에서는 JWT 인증 필터가 이미 검증한 `TokenClaims`를 Authentication details에 전달한다.
컨트롤러는 이 문맥에서만 sid를 얻는다. 요청 본문·쿼리·임의 기기 헤더로 sid를 선택하지 않는다.
STOMP의 선택 `auth_session_id` 헤더는 B1대로 JWT sid와 일치해야 하며 참여 선택 권한을 추가로 부여하지 않는다.
서버 검증 이후 sid는 클라이언트에 새 필드로 반환하지 않는다.

## 저장과 동시성

B2의 nullable `auth_session_id` 컬럼을 JPA에 매핑했다. 추가 DDL과 Flyway migration은 없다.
새 참여의 생성 메서드는 sid를 필수로 받고, 저장 후 sid를 변경하지 않는다.
활성 참여는 사용자·sid로 조회한다. 사용자 전역 단일 Optional 조회는 제거했다.
기존 사용자 행 잠금과 단일 서버 `exclusive` 임계 구역에서 조회·생성·커밋·예약을 직렬화한다.
DB의 활성 sid 유일성 제약도 유지한다. 동일 sid의 동시 입장은 한 참여를 재사용한다.

## 호환성과 기존 데이터

요청·응답 JSON과 위치 토큰·membership 파라미터는 유지한다. 소유권 의미는 달라진다.

| 조건 | 결과 |
| --- | --- |
| 인증되지 않은 요청 | 기존 401 |
| 참여 변경 요청의 sid 누락·비정규 UUID | 401 `chat authentication session required` |
| 다른 sid·NULL sid의 membership으로 CONNECT | ERROR `CHAT_LEFT`, 연결 권한 없음 |
| 다른 sid·NULL sid의 membership으로 DELETE | 403 `chat membership unavailable` |
| 같은 sid·같은 방 재입장 | 200, 기존 예약·유예 기한 미연장 |
| 같은 sid의 종료 후 재입장 | 201, 새 membership 생성, 이전 행 보존 |

이전에 sid 없이 만든 토큰은 재로그인이 필요하다.
기존 NULL sid 참여는 원래 인증 세션을 알 수 없으므로 인수·백필·삭제하지 않는다.
새 sid로 다시 입장하면 새 참여를 생성하며 이전 참여·열람 관계를 붙여 주지 않는다.
기존 NULL 행이 활성 상태로 남아 있어도 새 연결 권한이나 메모리 정원 자리를 주지 않는다.
따라서 단순히 DB 활성 행 수를 접속 정원으로 세지 않는다.
배포로 연결이 끊긴 기존 NULL 참여는 새 입장 절차가 필요하다.

B2 migration과 B3 정원 집계가 먼저 적용되어야 한다.
여러 활성 sid가 생긴 후에는 사용자 단일 Optional을 사용하는 예전 코드로 단순 롤백할 수 없다.
롤백이 필요하면 연결과 쓰기를 멈추고 참여 데이터를 보존한 상태에서 복귀 절차를 정해야 한다.
기존 migration 변경·auth sid 추측 백필·이력 삭제를 복귀 수단으로 사용하지 않는다.

## 후속 작업 경계

- 기존 join의 지역 전환 처리는 현재 sid의 참여만 대상으로 기존 순서를 유지한다. 기기별 지역 이동의 전용 흐름과 위치 토큰의 세션 결합은 B5에서 다룬다. 현재 위치 토큰은 기존 사용자·지역 검증을 유지한다.
- 동일 membership의 여러 탭은 여전히 공유한다. 그 membership을 퇴장시키면 해당 연결 전부가 닫히며, 탭 하나만 나가는 동작은 B6다.
- 기존 전체 종료·로그아웃·제재 경로는 여러 참여를 모두 종료할 수 있도록 목록을 처리한다. 현재 로그아웃 이벤트의 계정 전체 종료 의미는 유지하며 기기별 로그아웃 제한은 B7이다.
- JWT sid의 서명·형식·참여 소속을 검증한다. Hazelcast의 세션 폐기 즉시 조회·전파는 이번 범위가 아니다.
- 계정의 과거 구간 통합과 다른 기기의 이력 조회·복원은 B8/B9와 FE 후속 작업이다.

운영 적용 시 REST 입장과 CONNECT에 같은 sub/sid 토큰을 사용하고, 다른 sid는 별도 입장하도록 확인한다.
B5~B7까지의 최신 정책 전체를 완성한 배포로 취급하지 않는다.
운영 설정·기존 FK 문제·FE 제품 코드는 이번 작업에서 변경하지 않았다.

## 검증 환경과 결과

원본 `compileJava`는 기존 AuthExceptionHandler의 누락 AuthSessionController/LoginQueueController 참조로 실패한다.
기존 누락 참조 오류는 제품 코드에서 수정하지 않았다. JWT 인증 필터의 검증 문맥 전달은 B4 기능 변경에 포함한다. 원본 포맷과 컴파일을 제외한 Checkstyle main/test 결과를 별도로 기록한다.

격리 사본 `/private/tmp/meomuneum-b4-verify-hobk1285`에서 누락된 두 import/advice 대상만 제거하고,
누락 LoginQueueAdmissionScheduler를 참조하는 auth 테스트 디렉터리를 제외했다.
변경한 제품 코드와 테스트는 원본과 같은 파일로 실행한다. 사본 결과를 원본 전체 test/bootJar 성공으로 표시하지 않는다.

검증 대상:

- 같은 sid의 중복·동시 입장과 서로 다른 sid의 독립 생성, 정원 1에서 계정 중복 제거.
- 다른 지역의 참여 유지와 기존 NULL 참여 보존·연결 거절.
- 실제 HTTP의 sid 없는/잘못된 토큰 거절, 재입장 200, 종료 후 새 참여 201.
- 실제 WebSocket의 다른 sid 참여 CONNECT 거절과 퇴장 소유권 거절 후 기존 연결 유지.
- 한 참여를 퇴장시킨 뒤 다른 sid 연결의 메시지 전송, 토큰 갱신·기존 입장·제재·복구 회귀.
- JWT 필터가 검증한 문맥 전달, 임의 auth_session_id 헤더로 sid 변경 불가.
- 실제 FE 복구 클라이언트와 HTTP/WebSocket 연동.

첫 관련 회귀는 통과했다. 추가한 sid 누락 보안 테스트가 Origin 헤더를 빠뜨려 401 이전에 기존 Origin 필터의 403을 받은 것을 확인하고 테스트 요청만 수정했다.
수정 전 결과는 `build/b4-membership-verification/before-origin-fixture-fix/`에 보존한다.
실제 단말·다중 서버·운영 배포와 로그아웃 기기 격리 전체 흐름은 검증 대상에 포함하지 않았다.

최종 결과: 관련 186개 중 186개 통과, 제외 0개, 실패·오류 0개.
원본 Spotless·컴파일 제외 Checkstyle main/test, 사본 bootJar 통과. 최종 XML/집계는 `build/b4-membership-verification/final/`과 `summary.json`에 보존했다. 원본과 사본의 Java 파일은 앞서 명시한 인증 오류 우회 파일 하나를 제외하고 일치한다.
OpenAPI YAML 구문과 경로 3개를 Ruby YAML parser로 확인했다. push·PR 등록·운영 DB 적용·배포는 수행하지 않았다.
