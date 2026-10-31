# 채팅 부하 테스트 준비 수정과 검증 경계

## 변경 범위

- 같은 방의 인원 변경 hint를 최신 값으로 병합한다. 메시지·퇴장 등 terminal 이벤트는 병합하지 않고 기존 DB 정본 검증을 유지한다. relay 수신 큐 포화의 최초 상태를 제한된 로그로 남긴다.
- 분산 연결 상태 변경에서 사용하지 않는 전체 인원 조회를 제거한다. checkpoint의 DB 작업은 로컬 presence 잠금 밖에서 수행하고, 퇴장 커밋 후 해당 view 메타데이터를 제거한 다음 transport를 닫는다.
- 참여 전환은 DB 연결 획득 전에 공정 Semaphore 두 개로 제한한다. 대기는 기존 1초, commit 예산은 기존 2초다. permit은 투영·transport callback 전에 반환한다.
- 읽기 전용 socket 검증은 방 변경 잠금과 분리하되 DB의 계정·ban·membership·view·Task·epoch·generation·lease·Core 인증 조건을 유지한다. 권한 캐시는 추가하지 않는다.
- checkpoint/메시지로 방 버전만 바뀌면 presence를 재발행하지 않는다. 전환은 트랜잭션 안에서 보존한 버전 이상으로 변경된 view만 투영하며 전체 인원 counts와 주기적 전체 복구·view별 CAS는 유지한다.
- Core HTTP 진입은 기존 전체 1초의 남은 예산 안에서 대기한다. 인증 실패의 단계·상태·경과 시간·제한된 큐 상태를 기록하되 토큰·계정·sid·본문·예외 메시지는 기록하지 않는다.
- Task별 인증 제한은 공유 source IP 대신 참여 원장과 같은 Task UUID를 사용한다. 사용자별·Task별 초당 100회와 bounded executor·HTTP deadline은 확대하지 않는다.

## 내부 인증 호환성

`X-Chat-Task-Id`는 서비스 bearer 검증 뒤 호출 제한에만 사용하는 canonical UUID다. 사용자 인증이나 DB 소유권 허가가 아니다. 중복·잘못된 형식은 400, 미전달 구버전은 source IP 제한을 유지한다. Core가 새 헤더를 지원하도록 먼저 반영한 뒤 Chat을 반영한다. 실제 배포는 수행하지 않았다. 기계 계약은 `api/chat-core-auth.openapi.json`에 기록한다.

## 검증

관련 단위 회귀 55개와 실제 MySQL·Redis·Core 프로세스·소켓 종료 runtime 회귀 41개, 총 96개가 통과했다. 별도 k6 알고리즘 회귀 12개도 통과했다. SpotlessCheck, main/chat/core/test Checkstyle 및 Core/Chat JAR 빌드 검증을 수행했다. 전체 저장소 테스트 성공이나 운영 검증 완료를 의미하지 않는다.

관련 Gradle 검증은 `test`에서 ChatMessageRelayBackpressureTest, ChatPresenceEventCoalescerTest, ChatViewLifecycleTest, ChatPresenceRegistryTest, ChatRoomEntryServiceTest, ChatStompInterceptorTest, ChatMessageOutboundGuardTest, ChatCoreAuthGuardsTest, ChatParticipationDiagnosticsTest, ChatRuntimeSchedulingConfigTest, ChatParticipationSchedulingConfigTest, ChatCoreSessionVerifierTest, CoreSessionHttpClientTest, CoreAuthContractTest를 선택하고, 별도 `chatRuntimeLifecycleTest`를 실행한다.

## 본 측정에서 확인한 미해결 장애

짧은 예비 실행은 100→500→1,000명 준비·메시지 단계·정리를 완료했으나, 같은 빌드의 긴 본 측정에서 장애가 재발했다. 본 측정 조건은 단계별 워밍업 60초·측정 180초, 방 전체 10msg/s, 목표 100→500→1,000명이다. 100명 후 고정 60초 대기 중 B를 수동 편입한다. 임계치 기반 자동 확장 검증은 아니다.

2026-11-01 KST 전환 후 본 측정 3회 시도 결과:

| 회차 | 결과 |
|---|---|
| 1 | 100명 준비 중 Core caller_rate 503. 메시지 측정 미진입. 정상 정리 성공. |
| 2 | 100명 전송 1,800건 ACK/수신 180,000건. 500명 수신 p95 176,042ms·시간 내 ACK 0건. 1,000명 준비 중 B sweep SQL1205 잠금 대기 timeout 및 A 허가 만료·heartbeat 차단. 정상 정리 실패, 활성 참여·view 각 500개 잔존. |
| 3 | 100명 전송 1,800건 ACK/수신 180,000건. 500명 측정 중 준비 완료 연결 최저 251명, Core validation 실패와 B 허가 만료·heartbeat 차단. 정상 정리 실패, 활성 참여·view 각 386개 잔존. |

전체 단계 완주 0회, 정상 정리 성공 1회다. 1,000명 메시지 측정값은 없으며 0으로 채우지 않는다. 3회차 500명 구간은 연결 수가 유지되지 않아 정상 500명 성능값으로 비교할 수 없다. SQL1205는 lock wait timeout이며 deadlock1213으로 단정하지 않는다. 정확한 lock holder, GC·호스트 자원 경합과 지연의 인과관계는 추가 계측 대상이다.

측정 중 제품·스크립트·서버 설정·자원·JAR을 바꾸지 않았다. 원본 counter와 summary, 측정 구간의 독립 p95를 대조했다. 실패 상태를 백업한 뒤 각 회차의 기준 DB·프록시·기존 before 앱을 복원했으며, 복원 성공을 정상 cleanup 성공으로 표시하지 않는다. 로컬 Mac과 Docker가 자원을 공유하므로 클라우드 성능이나 운영 준비 완료로 일반화하지 않는다.

이번 커밋은 부하 준비 과정의 수정과 실패 기준점을 보존한다. 인증 호출 제한, 잠금 대기 timeout, 허가 만료·연결 종료 문제의 해결 완료가 아니다. 후속 장애 수정·동일 조건 예비 검증 후 본 측정을 다시 진행해야 한다.

실행 식별자: `20261031T155704Z-after-01-round-1a583a`, `20261031T155832Z-after-02-round-5a56aa`, `20261031T161032Z-after-03-round-c48b51`. 원본 로그·raw·시계열·비공개 DB backup은 로컬 검증 보관소에 유지한다. 인증 fixture·쿠키·토큰·DB backup 및 tools 전체는 커밋하지 않는다.
