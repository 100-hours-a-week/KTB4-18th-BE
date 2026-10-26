# 지역 채팅 로컬 부하테스트

Mac에서 k6를 실행하고 Docker의 단일 백엔드·MySQL·Hazelcast에 부하를 보낸다. Prometheus·Grafana로 관찰한다. AWS 비용 없이 전환 전 기준을 기록하는 도구이며, 다중 서버 실행과 자동 확장은 아직 구현하지 않았다.

## 준비와 실행

macOS, Python 3.9 이상, Node.js 18 이상(집계 테스트), Java 25, Docker Compose, k6가 필요하다. 아래 명령은 이 디렉터리에서 실행한다. 공개 포트는 모두 loopback에 바인딩한다.

```sh
bash run.sh init
bash run.sh build
bash run.sh up
bash run.sh verify
bash run.sh smoke
python3 fixtures.py seed
python3 fixtures.py login --limit 1000 --workers 32
```

`init`은 비밀값을 Git 제외 파일 `.env`에 생성하며 기존 파일을 유지한다. `build`는 현재 체크아웃의 bootJar와 커밋·해시를 `.runtime`에 보관한다. 테스트 계정은 `chatloadNNNN@local.test` 형식의 합성 계정 1,000개다. 위치 외부 API만 로컬 모의 서버를 사용하며 인증은 실제 REST·CSRF·세션 갱신 경로를 거친다.

**알려진 준비 제약:** 측정 당시 신규 로그인에서 `503 AUTH_STATE_UNAVAILABLE`이 발생했다. 기존에 준비한 세션의 실제 refresh로 공식 측정은 실행했지만 신규 로그인 문제는 해결하지 않았다. 새 환경에서는 위 로그인 명령의 성공부터 확인해야 하며, 기존 세션 파일은 Git으로 공유하지 않는다. 본 측정 전에 인증과 구독 준비가 통과해야 한다.

## 공식 측정

전원을 연결하고 불필요한 앱을 정리한다. 이 도구의 3회 실행기는 측정한 Mac 조건에 맞춰 Docker CPU 10개·메모리 8,321,515,520바이트를 검사한다. 다른 환경에서는 예비 점검 후 비교할 전후 조건을 함께 확정한다. 실행 중 조건을 바꾸지 않는다.

```sh
# 단일 회차: 초기화 시 전체 SQL 백업 후 합성 채팅 데이터만 정리한다.
python3 prepare-baseline.py --restore
python3 verify.py
python3 run-chat.py round --mode before --round 1 --renew-auth
# 실패 회차도 보존하면서 원본 대조
python3 analyze-chat.py results/<run_id> --allow-failed

# 또는 준비된 환경에서 전환 전 3회 연속 실행
python3 -u run-before-series.py
# 종료 후 CSV·JSON·SVG 생성
python3 summarize-before-series.py results/<series_id>
```

3회 실행기는 매 회차 백업·기준 상태 복원·서버 재시작·health·환경 확인·1,000계정 인증 갱신·측정·원본 대조를 순서대로 수행한다. `round`는 `--renew-auth` 생략 시에도 인증을 갱신한다. 갱신 실패 또는 회차 종료까지 유효시간 부족이면 부하를 시작하지 않는다. 측정 중에는 인증을 갱신하지 않는다. 전송량 미달 등 성능 실패는 기록하고 다음 회차를 실행하며, 인증·준비·정리 실패는 중단한다. `completed` 상태는 모든 성능 조건 통과를 의미하지 않는다.

| 항목 | 고정 조건 |
| --- | --- |
| 계정·방 | 서로 다른 1,000계정, 지역 방 1개, 정원 1,000 |
| 연결 | 100 → 500 → 1,000, 기존 연결 유지 후 추가 입장 |
| 단계 | 준비 완료 후 전송 없이 10초 → 안정화 60초 → 측정 180초 → 수신 대기 5초 |
| 전송 | 방 전체 초당 10건, 발신 100계정 순환, 고유한 80자 본문·메시지 ID |
| 단계 사이 | 100명 단계 뒤 60초, 연결·heartbeat 유지, 메시지 전송 중지 |
| 종료 | 5명씩 퇴장·연결 종료, 묶음 간 100ms |
| 앱 | CPU 2개, 1,536MiB, JVM Xms256m/Xmx768m |
| 관측 | Prometheus 512MiB, Grafana 512MiB |

## 관측과 결과 해석

- API/WS: `http://127.0.0.1:18080` / `ws://127.0.0.1:18080/ws`
- Grafana: `http://127.0.0.1:13000`, 사용자 `localtester`; 비밀번호는 `.env`의 `LOCAL_GRAFANA_PASSWORD`.
- Prometheus: `http://127.0.0.1:19090`.

지연은 k6 전송 시각부터 수신 처리 시각까지다. 발신자의 수신도 포함하고 ACK는 방 메시지 수신과 분리한다. 수락된 메시지와 전송 당시 준비된 연결 세대로 예상 수신 수를 계산한다. 미발송·거절·연결 이탈·전달 누락을 별도로 확인한다. p95는 전체 유효 수신의 정수 ms 히스토그램으로 계산한다. 원본 파일은 선택한 지표 중심이며 모든 k6 기본 지표의 개별 샘플을 저장하지 않는다.

서버·k6가 같은 Mac을 사용하므로 관찰된 지연을 순수 서버 처리 시간으로 해석하지 않는다. CPU는 컨테이너별로 확인하며 한 코어 사용량 100% 기준이다. JVM heap과 컨테이너 전체 메모리를 구분한다. `sample-resources.py`는 수동 자원 기록, `capture-evidence.py`는 관측 설정 증빙에 사용한다.

`results/<series_id>/commands.jsonl`에 정확한 명령·시각·종료 코드를 남긴다. 회차별 `k6-command.json`, `manifest.json`, `result.json`, `summary.json`, `verification.json`, 지연 히스토그램·선택 원본·자원·시계열을 보존한다. 시리즈는 실행 코드·설정 고정본과 JAR 해시도 보관한다. 결과 분석에는 실행 당시 경로와 로컬 인증 준비 요약이 필요하다. 원본 폴더를 옮길 때는 manifest의 경로 참조도 보존한다.

공식 3회 결과와 해석 한계는 [측정 요약](BASELINE-2026-10-26.md)을 참고한다. 이 브랜치는 dev에서 분리한 도구 모음이며, 당시 측정 대상 제품 커밋과 현재 체크아웃은 다를 수 있다. 재실행 시 실제 빌드 식별자를 반드시 기록한다.

## Git 포함·제외와 검증

Git에는 실행·집계·테스트·관측 설정과 이 문서·측정 요약을 포함한다. `.env`, `.runtime/`(인증·백업·JAR), `results/`, 임시 `diagnostics/`, 과거 준비 문서는 제외한다. 제외는 로컬 삭제를 뜻하지 않는다. 원본 결과와 비공개 백업은 별도로 보관한다.

```sh
node --test k6/chat-core.test.mjs
python3 renew-fixtures.test.py
bash -n run.sh
```

종료는 `bash run.sh stop`, 재시작은 `bash run.sh up`이다. `bash run.sh down`도 볼륨·결과를 보존한다. 신규 로그인 503, Mac·발생기 병목 분리, 다중 서버와 자동 확장 검증은 남은 과제다.
