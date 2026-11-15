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


## 후속 장애 수정·성능 개선 및 별도 진단 — 2026-11-01

앞의 본 측정3회 실패 기록은 당시 기준점으로 보존한다. 이후 변경은 다음과 같다.

- Chat의 인증 호출 시작을 초당84회로 제한해 Core의 기존 Task별100회 제한에 지연 여유를 둔다. in-flight16개·전체1초 deadline과 사용자별 제한은 유지한다.
- busy expired Task와 outbox 정리는 `FOR UPDATE SKIP LOCKED`로 확정한 행만 처리해 정상 작업의 잠금을 기다리지 않는다. 미만료 메시지와 DB 권한 fencing은 유지한다.
- runtime DB/Redis 단계·전이 admission holder·Core의 DB/Hazelcast 검증 단계의 최초 실패를 제한된 로그로 남긴다. 계정·sid·토큰·본문은 기록하지 않는다.
- enqueue 권한은 메시지/방/사용자/소켓/세대/epoch/issuer에 묶인 내부 증거로 전달한다. 실제 outbound handler는 현재 요청 최대32개를 새로운 PRIMARY DB 조회로 묶고 Core/JWT·현재 구독·소유권·구간·24시간 경계를 다시 검증한다. 권한 결과 캐시는 없다.
- 로컬 구독 인덱스와 SEND/본문 조회의 잠금 범위 축소로 전역 presence monitor 점유를 줄인다. 중복 억제와 최종 enqueue는 직렬화한다. DB batch worker1개·pending64개·outbound32개를 사용하며 DB pool/CPU/heap/권한 TTL은 확대하지 않는다.
- SQL 준비 정보 재사용을 명시하고 배치 크기는1/2/4/8/16/32의6가지 SQL 형태로 제한한다. NULL view padding은 실제 참여자에 일치하지 않는다. 준비 정보 캐시는 권한 결과 캐시와 구분한다.
- 인원수 알림만200ms 구간에 최신 hint로 합치며 이미 전송한 snapshot version 이하는 재전송하지 않는다. 기존 dispatch 전용 scheduler로 flush하고 pending room256개를 상한으로 둔다. 초기 snapshot/ready·메시지·terminal 이벤트는 이 지연 구간에서 제외한다.
- 동일 heap/CPU에서 Chat G1 GC를 명시한다. Core GC는 변경하지 않는다. 세부 GC 로그와 부하 도구/fixture는 로컬에만 보존한다.

관련 단위59개·MySQL46개·Core/종료2개, 총107개와 Spotless·Checkstyle main/chat/test·Core/Chat JAR 검증을 통과했다. 모든 배치 크기의 현재 권한과 PRIMARY 실행 계획, 권한 폐기 재조회, SQL 형태6개·전체 재반복 추가 prepare0도 검증했다.

최신 별도 진단 `20261101T075325Z-after-03-bottleneck-48cfca`는 각 단계 warm60초/measure60초·10msg/s·100senders·80자·join100ms 조건이다. 고정60초 구간에서 B 수동증설을10.803초에 완료했다.

| 목표 연결 | A/B | 각 구간 ACK | 각 구간 수신/기대 | 측정 p95/p99 |
| --- | --- | --- | --- | --- |
| 100 | 100/0 | 600/600 | 60000/60000 | 192/290ms |
| 500 | 114/386 | 600/600 | 300000/300000 | 226/466ms |
| 1000 | 140/860 | 600/600 | 600000/600000 | 907/1153ms |

모든 구간 목표 연결 수 유지, 전체3600ACK/1920000수신·누락/중복/비정상종료/정리오류0, k6 exit0이다. 정상 종료 후 active view·interval·membership0, 별도 기준DB/프록시/기존 앱 복원을 확인했다. 테스트 이후 소스와 실행JAR 해시도 대조했다.

이는 수정 후 별도 진단1회이며 기존 정식3회 결과를 대체하지 않는다.180초 본 측정·자동확장·운영 검증 완료가 아니다.1000명 p95907ms로 지연 여유가 크다고 볼 수 없고, 순간 outbound 대기도 남는다.

중간 진단에서는 격리 MySQL1GiB의 OOM이 발생해 장애DB를 보존하고 같은 한도로 재시작했다. 최종 진단에서는 최고706.2MiB/OOM재발0이지만 SQL형태 제한과 DB재시작이 함께 적용돼 장기 누적 원인을 분리 입증하지 못했다. 이전 Core1.1초 인증지연도 최종 진단에서 재발하지 않았으나 직접 원인 해결 확정으로 닫지 않는다. 중간 실패 원본·상세 분석·107개 XML·해시 기록은 로컬 보관소에 보존한다. 공개 API/DB 스키마 변경·AWS/배포·외부 이슈/PR 변경은 없다.

## 최신 수정본 본 측정과 전후 비교 — 2026-11-02

위 실패·진단 기록은 당시 이력으로 보존한다. 이후 신규 인증 요청을 최대100개로 묶고 Core 세션을 Hazelcast getAll로 새로 조회했다. 요청 시작 기준1초와 인증 허가4초, 폐기 우선순위는 유지한다. 전이 단계 경과와 전체 경과를 분리 계측하고, 연속 presence outbox를 최대100개 병합했다. 전이 중 claim 지연과 방 잠금 SKIP LOCKED를 적용하고 Core G1도 명시했다. 자원·DB 풀·권한 TTL·공개 API·DB 스키마는 확대하거나 변경하지 않았다.

관련 단위74개와 실제 MySQL·Core·종료 회귀51개, 총125개 및 Spotless·main/chat/core/test Checkstyle 통과 기록을 보존한다. 최신 본 측정과 목적별 커밋 전후 제품 소스·JAR 해시가 동일함을 대조했다. 커밋 분리를 위해 제품 내용을 다시 수정하지 않았으며 부하 중 Gradle을 실행하지 않았다. 전체 저장소 테스트나 운영 검증 완료를 뜻하지 않는다.

본 측정은 기존 조건인100→500→1,000명, 연결 추가25ms, 안정화10초, 준비 전송60초/측정180초, 방 전체10msg/s·송신자100명·80자로3회 실행했다. 매 회차 A만 시작하고100명 후 고정60초 대기 중 B를 수동 기동·준비 확인·프록시에 편입했다. B 준비는 각각10.65/10.70/10.77초였다. 아래 순번은 시작 시각 순서로 하네스 인자 round=2/3/1과 구분한다.

| 동접 | 후1회 p95 | 후2회 p95 | 후3회 p95 | 후1/2/3회 예정 전송 미달 | 후1/2/3회 마감 내 미수신 |
| --- | --- | --- | --- | --- | --- |
| 100 | 2,250ms | 2,026ms | 2,256ms | 0/0/0 | 0/0/0 |
| 500 | 1,554ms | 1,990ms | 1,647ms | 0/0/0 | 0/0/0 |
| 1,000 | 37,017ms | 34,306ms | 6,087ms | 206/62/8 | 99,108/262,099/0 |

100명·500명은 세 회차 모두 예정1,800개 전송·수락·ACK와 마감 내 전체 수신을 충족했다. 1,000명도 세 회차 연결을 유지했지만 예정 전송량과 전달 기준은 충족하지 못해 전체 k6 exit는 모두99다. 수신 마감은 단계 종료 후5초이며 메시지별5초 SLO가 아니다. 미수신은 관측 창의 부족분으로 마감 후 도착도 포함하므로 영구 유실로 단정하지 않는다. 부하 발생기의 예정 전송 미달은 서버가 받은 메시지의 미수신과 구분한다.

기준선2026-10-26의 전3회 p95 중앙값은29/792/2,685ms, 최신 후3회는2,250/1,647/34,306ms다. 각각 회차 p95 세 개의 중앙값으로 전체 표본을 합친 p95가 아니다. 전1,000명에도 예정 전송 미달이 있어 동일 실제 부하의 개선율이나 성공 수용량으로 확정하지 않는다. 후1,000명 합산 미수신은361,207/5,124,000=7.0493%로 회차 비율 평균이 아닌 건수 합산이다.

후1/2회1,000명 구간에서 outbox oldest가 약34~38초까지 증가했다. 전이 우선 정책은 일반 checkpoint도 포함하므로 발행 지연 기여 여부가 후속 검토 대상이며 해결 완료로 표시하지 않는다. 후2회11:33:51.393 KST에는 Core 인증 검증 불가 경고1건도 있었다. 채팅 준비·소켓·비정상 종료·정리 오류 counter는 세 회차 모두0이지만 인증 경고의 원인까지 해결한 것은 아니다.

처음 시도는10:51:11 KST부터 Mac Idle Sleep300초와 자원 표본 공백306초가 확인돼 정상 비교에서 제외하고 원본을 보존했다. 이후 측정 중 임시 잠자기 방지를 적용해 제외 회차를 보충했다. 결과가 나쁜 회차를 골라 제외하지 않았다. 세 본 측정 모두 정상 연결 정리, 비공개 DB 백업 보존, 깨끗한 격리 DB·프록시·기존 Backend 건강 상태 복원을 확인했다.

본 측정 식별자:

- 후1회: `20261102T020405Z-after-02-formal-regression-d1377d`
- 후2회: `20261102T022033Z-after-03-formal-regression-832896`
- 후3회: `20261102T023726Z-after-01-formal-regression-dc10c0`

기존 전환 후 실패3회와 별도 진단은 덮어쓰지 않았다. 상세 비교 HTML/Markdown, 전후18개 구간 CSV·히스토그램 독립 대조, 자원 표, p95·연결/CPU 그래프2개와 원본 SHA-256은 로컬 `docs/verification/chat-load-comparison-2026-11-02/`에 보존한다. 최신 측정 절차·복원과 제외 시도는 `docs/verification/chat-followup-formal-2026-11-02/`에 있다. 기존 ignore 정책에 따라 상세 로컬 문서·tools 전체·인증 fixture·토큰·쿠키·SQL 백업을 강제 추가하지 않는다.

동일한 Mac에서 부하 발생기와 서버를 함께 실행한 로컬 비교다. 전환 후에는 정해진 시점에 두 번째 Chat 서버를 수동으로 추가했다. 실제 AWS 자동 확장과 운영 수용량은 검증하지 않았다. 전후 날짜·앱 구성·총 자원·후속 수정이 달라 서버 수만의 효과를 분리한 실험이 아니다. 본 측정은 완료했지만1,000명 전송 미달·전달 지연·미수신은 남아 있다.
