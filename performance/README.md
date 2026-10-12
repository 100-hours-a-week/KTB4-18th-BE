# 챗봇·지역 채팅 부하 테스트 준비

Mac에서 동일한 백엔드·DB·모의 AI 조건으로 개선 전후를 비교하기 위한 환경이다.
준비 단계에서는 실제 부하, 기준 성능 측정, 성능 최적화를 실행하지 않는다.
2026-10-11 후속 요청으로 소량 smoke·정원 정책 검증을 완료했다. [작업 기록](work-log.md)과 [smoke 결과](smoke-2026-10-11.md)를 확인한다. 기준 성능·개선 수치는 아직 없다.
다음 후속 요청으로 TEXT1rps·VOICE1rps·지역5명 조건의2분 워밍업+5분 측정을 각각3회 완료했다. [개선 전 기준선과 PNG/SVG 그래프·CSV](reports/baseline-2026-10-11/README.md)를 확인한다. 위 미실행 문구는 이전 준비/smoke 시점 기록이며 최적화·개선율·최대 처리량은 여전히 후속 단계다.
현재 확인한 검사 결과와 아직 실행하지 않은 항목은 [verification.md](verification.md)에 기록한다.
현재 브랜치의 제품 코드에 옵트인 계측을 추가했으며, 계측 활성화 상태를 개선 전후 동일하게 유지한다.
공개 API·추천 executor의 4/8/100 설정·채팅 공통 잠금·정원25명·메시지 정책은 유지한다.

## 실행 경계

- TEXT/VOICE 추천, SSE done 완료, 전사, 지역 텍스트 채팅을 측정한다.
- 실제 AI·iTunes·지도 공급자 호출, 이미지 추천, 공동 재생, 공연 방3000명, 미구현 복구 기능은 측정하지 않는다.
- 위치 모의 서버는 합성 좌표를 10개 기존 SIGUNGU에 매핑한다. 실제 지리 정확도 검증이 아니다.
- 로그인/refresh 부하는 제외한다. 전용 DB의 합성 사용자250개에 전용 로컬 비밀키로 access JWT를 미리 발급한다. 실제 Bearer 검증·DB 사용자 조회·STOMP 인증은 그대로 통과한다. 위치 토큰은 실제 `/locations/resolve`로 발급받는다.
- localhost 전용 스크립트이며 운영 주소를 입력하면 거절한다. 관리9091포트는 호스트에 공개하지 않는다. API·DB·모의 서버·Grafana·Prometheus의 호스트 포트도127.0.0.1에만 바인딩한다.
- 테스트 결과는 이 Mac·이 자원 제한·이 모의 응답 조건의 결과다. 운영 동접·실제 AI 성능으로 일반화하지 않는다.

## 준비 환경

| 구성 | 조건 |
| --- | --- |
| 백엔드 | Java25, CPU2, 메모리1GiB, JVM heap512MiB, Hikari10 |
| MySQL | 9.7.0, CPU1, 메모리1GiB, buffer pool256MiB |
| 모의 서버 | Python3.12.11, CPU1, 메모리256MiB |
| Grafana | 12.1.1, CPU0.5, 메모리512MiB |
| Prometheus | 3.5.0, CPU0.5, 메모리512MiB, scrape5초 |
| 모의 추천 | 첫 text200ms, 완료2000ms, text2개, 고정5곡 |
| 모의 전사 | 1000ms 후 고정 transcript |
| 합성 자료 | 사용자250개, 10개 방에25명씩, AAC MP4약10·30·60초(인코딩 여유0.2초) |

Docker Desktop에 충분한 CPU·메모리를 배정하고 전후 동일하게 유지한다. 호스트 k6와 Docker VM이 자원을 공유하므로 다른 개발 서버·IDE 빌드·회귀 검사를 측정 중 함께 실행하지 않는다.
서비스·base 이미지는 준비 시 확인한 digest로 고정하고 실행마다 실제 image ID·repo digest를 manifest에 남긴다. FFmpeg 직접 패키지도 준비 시 버전으로 고정한다. 전후에 pull하지 않고 같은 DB/모의/모니터링 이미지, 같은 백엔드 base/runtime을 사용한다. 소스 변경으로 백엔드 이미지는 달라진다.

## 최초 준비 (백엔드 저장소 루트에서)

```bash
python3 performance/scripts/prepare.py --init-only
./gradlew spotlessCheck checkstyleMain checkstyleTest bootJar --no-daemon
docker compose --env-file performance/.env -f performance/compose.yaml up -d --build
python3 performance/scripts/prepare.py
python3 performance/scripts/preflight.py
python3 performance/scripts/regression.py
```

초기 Gradle 전체 검사에는 TEST_DB_* 환경이 필요하다. 전용 DB로 전체 검사를 재현하려면 Compose 기동 후 `python3 performance/scripts/regression.py`를 사용한다. 처음 JAR이 없으면 `./gradlew bootJar --no-daemon`으로 생성 후 Compose를 시작한다.
`regression.py`는 별도 `meomuneum_perf_regression` DB를 생성해 테스트한다. 벤치마크 DB와 분리하며 검사 후 사용하지 않는 회귀 DB가 남아도 기준 DB를 변경하지 않는다.
`prepare.py --init-only`는 .env와 JWT 파일만 만들고 DB·음성은 건드리지 않는다. `prepare.py`는 서버가 건강할 때만 기존 테이블에 합성 사용자를 추가하고 음성 파일을 생성한다. 운영 비밀값을 .env에 넣지 않는다.

- Grafana: http://127.0.0.1:13000/d/meomuneum-performance (계정 perf, 비밀번호는 performance/.env)
- Prometheus: http://127.0.0.1:19090/targets
- 백엔드 API: http://127.0.0.1:18080
- 준비 상태 검사: `preflight.py`는 health·지표·수집·Grafana·모의 설정만 조회하며 추천/메시지를 보내지 않는다.
- `.env`, `generated/`, `results/`는 Git 제외. 토큰 파일은600권한. 보고서 공개 전에 manifest·스크린샷의 민감 정보를 확인한다.

## 부하 실행 전에 확정할 기준

아래는 운영 SLO가 아닌 잠정 실험 기준이다. 기준선 실행 전에 채택하거나 조정한 값을 scenario.json에 고정한다.

| 대상 | 잠정 성공 기준 |
| --- | --- |
| 추천·전사 | 정상 완료율99% 이상, 성공 요청 p95 5초 미만 |
| 채팅 | 연결·구독 준비100%, ACK·수신 p95 500ms 미만, 누락·중복·거절0 |
| 부하 생성 | dropped_iterations0, 목표 시작률과 실제 시작률 확인 |
| 정책 확인 | 정원26번째 입장409, 정상 참여 후 퇴장204 |

지연 백분위는 성공 요청의 값이다. 실패 요청을 숨기지 않고 완료율·실패 수와 함께 보고한다. 실패가 증가한 상황의 낮은 p95를 개선으로 판단하지 않는다.

## 실행 순서

1. `preflight.py` 통과 후 기능 확인용 smoke를 실행한다. 추천1건, 전사1건, 채팅2명×2메시지다. 성능 기준선으로 사용하지 않는다.
2. 정책 확인은 `chat-policy`로 별도 실행한다. 성공/거절을 혼합한 성능 수치로 사용하지 않는다.
3. 필요한 데이터 초기화 후 해당 조건의 워밍업2분을 실행한다.
4. 같은 프로세스에서 측정5분을 실행한다. 워밍업 결과를 측정 결과에 합치지 않는다.
5. 같은 초기 상태·워밍업·부하로 최소3회 반복한다. 새 조건 시작 전에 다시 초기화한다.
6. 개선 후 동일한 도구·환경·자료·계측·기준으로 재실행한다. 한 번에 한 가지 개선을 비교한다.

```bash
performance/scripts/run.sh recommendation smoke
INPUT_TYPE=VOICE performance/scripts/run.sh recommendation smoke
performance/scripts/run.sh speech smoke
performance/scripts/run.sh chat smoke
performance/scripts/run.sh chat-policy smoke

# 추천: 먼저 낮은 시작률로 측정. 단계별1,2,4,8...은 별도 실험이다.
RATE=1 performance/scripts/run.sh recommendation warmup
RATE=1 performance/scripts/run.sh recommendation baseline

# 채팅: 한 방5명 → 한 방25명 → 여러 방에 각25명. 방당 정원은 늘리지 않는다.
PER_ROOM=5 ROOMS=1 performance/scripts/run.sh chat warmup
PER_ROOM=5 ROOMS=1 performance/scripts/run.sh chat baseline

# 음성 길이는 같은 파일·길이로 전후 비교한다.
AUDIO_FILE="$PWD/performance/generated/audio-30s.mp4" RATE=1 performance/scripts/run.sh speech baseline
INPUT_TYPE=VOICE AUDIO_FILE="$PWD/performance/generated/audio-30s.mp4" RATE=1 performance/scripts/run.sh recommendation baseline
```

추천의 RATE는 초당 추천 시작 수다. 1iteration에 POST/SSE/상세GET 여러 요청이 있으므로 HTTP 전체RPS와 구분한다.
채팅은 모든 VU의 STOMP CONNECT와 두 SUBSCRIBE receipt가 확인된 뒤 모의 서버의 준비 barrier로 전송을 시작한다. 각 VU는 다른 계정을 사용하며2000ms 간격·고유 UUID/본문으로 전송한다. 예상수신=방 인원×참여자별 메시지 수(본인 메시지 포함). 실제 unique 수신, ACK 미도착, 미전송, 초과 수신을 확인한다. 여러 k6 프로세스를 동시에 실행하지 않는다.
`chat-policy`는 빈 방에서 예약25개 후26번째를 확인한다. 30초 예약 유예 안에 완료해야 하며 과도하게 느린 환경에서는 부하 지표 대신 정책 검증 실패로 기록한다.

## 관측과 측정 의미

- k6 추천: POST 시작부터 SSE 종료 및 done 확인까지 측정. 상세GET은 COMPLETED 저장 검증이며 완료 시간에 포함하지 않는다. 0곡도 정상 done이면 성공이다.
- INPUT_TYPE=VOICE 추천은 MP4 업로드·전사부터 시작해서 transcript로 VOICE 추천을 요청하고 done까지 측정한다. speech 시나리오는 전사만 따로 측정한다.
- k6/http는 유한 SSE 본문을 완료 후 반환한다. 따라서 클라이언트 첫 text 도착 시간은 이 스크립트로 측정하지 않는다. HTTP TTFB를 첫 text로 표기하지 않는다.
- `performance.recommendation.first.text`: 서버 접수 시작→AI 첫 text를 처리하는 시점. 큐 대기는 포함되나 클라이언트 네트워크·렌더링은 포함되지 않는 서버 지표다.
- `performance.recommendation.queue.wait`: executor 제출→실행 시작, 활성 작업·대기열 크기와 함께 확인한다.
- `performance.chat.lock.wait/hold`: 외부 exclusive 호출의 공통 모니터 대기/점유. 중첩 재진입은 중복 계측하지 않는다. 다른 synchronized getter의 대기를 직접 모두 계측하는 지표는 아니다.
- `performance.chat.db.store`: 신규 메시지의 중복 조회·저장·트랜잭션 완료를 포함한다. 단일 SQL 시간으로 설명하지 않는다.
- 채팅 전송/수신 시각은 동일 Mac의 k6 시계 기준이다. 실제 사용자 기기 간 시계 차이를 측정한 결과가 아니다.
- 고정5곡은 공통 음악행을 반복 갱신하는 hot catalog 조건이다. 병목이 음악행 잠금이면 이 조건을 함께 제시한다. 모의 서버 지연을 줄여 백엔드 성능 개선으로 제시하지 않는다.
- Grafana k6 p95는 remote-write 출력 구간의 p95다. 최종 전체 p95는 각 실행 summary.json으로 비교한다. 구간p95/3회p95를 평균내 전체p95라고 하지 않는다.

## 초기화와 증빙 보존

- 시작 데이터를 통일할 때: 결과를 보관한 뒤 backend를 중지하고 `reset-data.py --confirm-disposable-db` 실행, backend 재기동, `prepare.py` 실행한다. 준비 단계에서는 초기화를 실행하지 않는다.
- 초기화는 전용 meomuneum_perf DB의 추천/채팅 실행 데이터만 지운다. fixture 사용자·지역·방·기존 catalog는 보존하며 고정 mock5곡을 항상 미리 채운다.
- 프로세스 재기동으로 SSE10분 보존·UUID tombstone·접속 상태를 초기화한다. 같은 워밍업으로 JVM/DB를 예열한 뒤 측정한다.
- mock 설정 변경: .env 수정 후 `docker compose ... up -d --force-recreate mock`. normal/error/early-close/unavailable/slow-headers를 별도 실행한다. 각각 구성 manifest를 기록한다.
- 중지: `docker compose --env-file performance/.env -f performance/compose.yaml stop`. 볼륨과 증빙은 남긴다. 일상 종료에 `down -v`를 사용하지 않는다.

각 실행은 `performance/results/<UTC시각>-<시나리오>-<단계>/`에 manifest.json, scenario.json, resolved-options.json, raw.jsonl, summary.json, console.txt, exit-code.txt, container-stats.jsonl을 남긴다. k6가 충분한 실행 길이로 HTML을 생성한 경우 report.html도 보존한다. 짧은 smoke는 HTML 생성이 생략될 수 있다.
완료 실행의 조건·결과·아티팩트 SHA256은 `results/run-ledger.jsonl`에 자동 추가한다. 실패도 기록하며 실행 폴더 원본은 보존한다. 최초 smoke는 resolved-options 저장 도입 전 결과이며 실제 크기는 console과 실행 스크립트를 대조했다.
manifest는 커밋·dirty상태·소스별SHA256·Mac사양·k6버전·자원제한·이미지ID/digest·모의설정·음성hash를 기록하며 비밀값/토큰을 포함하지 않는다. 미커밋 계측도 hash로 구분한다.
원본 결과와 프로세스 자원 스냅샷은 함께 보관한다. 실패 실행도 삭제하지 않는다. 확인된 병목마다 가설→지표→수정→동일조건 재측정→정확성검증 순서로 comparison-template.md를 채운다.

## 반복 기준선 실행과 그래프 재생성

`python3 performance/scripts/baseline-batch.py`는 전용 DB의 실행 데이터4개 테이블을 백업·초기화하고 TEXT/VOICE/5명채팅의 워밍업·측정을3회씩 직렬 실행한다. 준비 단계용 명령이 아니며 약1시간 이상 걸린다. 중단되면 `--resume performance/results/<배치ID>`로 완료한 회차를 보존하고 미완료 회차만 초기화부터 다시 진행한다. 시도별 로그·백업은 덮어쓰지 않는다.

matplotlib3.11.2·numpy2.5.4가 설치된 Python에서 다음 명령으로 완료 배치를 집계한다. 아직 완료하지 않은 배치는 그래프 보고서로 확정하지 않는다.

```bash
python performance/scripts/plot-baseline.py performance/results/20261011T120430Z-baseline-batch performance/reports/baseline-2026-10-11
python3 performance/scripts/batch-status.py
```

plot-baseline.py는 로컬 Prometheus의 당시 query_range도 저장한다. 현재 retention3일이므로 장기 재생성에는 보관한 Prometheus JSON을 활용하거나 동일한 측정 raw와자원으로 재생성해야 한다. 기존 보고서·원본을 보존한 별도 출력 폴더를 사용한다.
보존한 시계열로 재생성할 때는 `PERF_PROMETHEUS_CACHE=performance/reports/baseline-2026-10-11`을 설정한다. 이 모드에서는 Prometheus 접속 없이 기존 캐시가 해당 실행 시간에 속하는지 확인해 사용한다.

## 2026-10-11 짧은 부하 탐색

[결과·VU/TPS·그래프](reports/explore-2026-10-11/README.md): TEXT2/4rps,VOICE2rps,지역25VU. 각30초워밍업·60초탐색1회. TEXT4는 p95/미시작 기준 위반, 나머지 통과. 기존5분3회 기준선과 분리하며 개선율·최대수용량 확정 전 단계다.

## 2026-10-12 추천 고정 VU 집중 재검증

[결과·그래프·원본 검증](reports/validation-2026-10-12/README.md): TEXT2/4rps·150VU 사전 할당·2분 워밍업/5분 측정3회. 유효 본 측정5404건 모두 완료·dropped0. TEXT2 p95 4.953~5.413초,4rps 4.497~4.871초.4rps 워밍업의 큐100/worker4→8/약28초 지연/10거절과 측정 후반 지연 증가를 보존했다. 음수 지연의 계측 이상 회차는 원본 보존 후 같은 조건으로 대체했으며 유효 성능 기준 위반은 유지했다. 개선은 후속 단계이고 운영 최대 수용량으로 해석하지 않는다.

## 제품 개선 전 확인할 세 항목

[실험 계획](experiment-plan-2026-10-12.md)에 발생 환경의 한계,병목 가설의 한 변수 검증,개선 목표안·정확성 조건을 기록했다. 추가 검증은 미실행이며,계측 유효성·발생기 여유 검증 후 가설 실험과 제품 개선으로 진행한다. 기존 결과는 병목 후보의 근거이며 운영 수용량이나 원인 분리 완료로 표시하지 않는다.
