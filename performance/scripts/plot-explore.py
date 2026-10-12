"""Short exploration evidence; include threshold failures and distinguish throughput denominators."""
import csv, datetime as dt, hashlib, json, os, sys, urllib.parse, urllib.request
from pathlib import Path
os.environ.setdefault('MPLCONFIGDIR', '/private/tmp/meomuneum-perf-mpl')
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
batch, out = map(Path, sys.argv[1:3])
out.mkdir(parents=True, exist_ok=True)
steps = [json.loads(s) for s in (batch / 'steps.jsonl').read_text().splitlines()]
assert steps[-1]['event'] == 'batch-complete'
rows=[]
evidence=[]
for entry in steps:
    if entry['event'] != 'run-recorded' or entry['phase'] != 'explore': continue
    run=batch.parent / entry['run_id']
    meta=json.loads((run/'scenario.json').read_text())
    metrics=json.loads((run/'summary.json').read_text())['metrics']
    def val(key, field, default=0): return metrics.get(key,{}).get('values',{}).get(field,default)
    pts=[]
    for line in (run/'raw.jsonl').open():
        p=json.loads(line)
        if p['type']=='Point': pts.append({'metric':p['metric'],**p['data']})
    def stamp(t): return dt.datetime.fromisoformat(t.replace('Z','+00:00')).timestamp()
    start=min(stamp(p['time']) for p in pts); end=max(stamp(p['time']) for p in pts)
    chat=meta['SCENARIO']=='chat'
    full_seconds=val('http_reqs','count')/val('http_reqs','rate')
    inflight=peak=0
    for p in sorted(pts,key=lambda p:stamp(p['time'])):
        if p['metric']=='recommendation_started': inflight+=int(p['value']); peak=max(peak,inflight)
        elif p['metric']=='recommendation_completed': inflight-=1
    assert chat or inflight==0, 'All started attempts must finish before analysis'
    queries={'queue':'performance_recommendation_queue_size','active':'performance_recommendation_active',
             'hikari_pending':'hikaricp_connections_pending',
             'queue_wait_p95':'histogram_quantile(0.95, sum(rate(performance_recommendation_queue_wait_seconds_bucket[30s])) by (le))'}
    monitor={}
    for key,q in queries.items():
        cache=out/(entry['trial']+'-'+key+'.json')
        if cache.exists(): data=json.loads(cache.read_text())
        else:
            query=urllib.parse.urlencode({'query':q,'start':start,'end':end,'step':5})
            with urllib.request.urlopen('http://127.0.0.1:19090/api/v1/query_range?'+query,timeout=15) as r: data=json.load(r)
            assert data['status']=='success'; cache.write_text(json.dumps(data,indent=2))
        monitor[key]=[float(v) for series in data['data']['result'] for t,v in series.get('values',[]) if v not in ('NaN','+Inf','-Inf')]
    sent=val('chat_sent','count') if chat else val('recommendation_started','count')
    successful=sum(p['metric']=='chat_ack_ms' for p in pts) if chat else val('recommendation_completed','passes')
    row={'trial':entry['trial'],'run_id':run.name,'exit_code':entry['exit_code'],
         'target_tps':int(meta['PER_ROOM'])/2 if chat else int(meta['RATE']),
         'vu_preallocated':int(meta['PER_ROOM']) if chat else int(meta['VUS']),
         'vu_max_configured':int(meta['PER_ROOM']) if chat else int(meta['MAX_VUS']),
         'vu_running_sampled_peak':max([p['value'] for p in pts if p['metric']=='vus'] or [int(meta['PER_ROOM']) if chat else int(meta['VUS'])]),
         'observed_business_inflight_peak':int(meta['PER_ROOM']) if chat else peak,
         'started_or_sent':sent,'completed_or_acked':successful,
         'business_tps_per_60s':successful/60,'business_tps_full_run':successful/full_seconds,
         'http_rps_full_run':val('http_reqs','rate'),
         'p95_ms':val('chat_ack_ms' if chat else 'recommendation_e2e_ms','p(95)',None),
         'completion_rate':val('chat_ready' if chat else 'recommendation_completed','rate'),
         'dropped_iterations':val('dropped_iterations','count'),
         'chat_deliveries':val('chat_delivered','count'),'chat_missing':val('chat_missing','count'),
         'chat_duplicates':val('chat_duplicates','count'),'chat_rejected':val('chat_rejected','count'),
         'queue_sampled_max':max(monitor['queue'],default=0),'active_sampled_max':max(monitor['active'],default=0),
         'queue_wait_p95_sampled_max_ms':max(monitor['queue_wait_p95'],default=0)*1000,
         'hikari_pending_sampled_max':max(monitor['hikari_pending'],default=0),'raw_span_seconds':end-start,'full_run_seconds':full_seconds}
    rows.append(row); evidence.append({'row':row,'raw_sha256':hashlib.sha256((run/'raw.jsonl').read_bytes()).hexdigest()})
with (out/'summary.csv').open('w') as f:
    w=csv.DictWriter(f,fieldnames=rows[0]);w.writeheader();w.writerows(rows)
(out/'evidence.json').write_text(json.dumps(evidence,indent=2))
fig,axs=plt.subplots(1,3,figsize=(13,4),layout='constrained')
labels=[r['trial'].replace('-r1','') for r in rows]
for ax in axs: ax.spines[['top','right']].set_visible(False);ax.tick_params(axis='x',rotation=30)
axs[0].bar(labels,[r['p95_ms']/1000 for r in rows],color=['#2563eb','#2563eb','#f97316','#16a34a']);axs[0].set_title('Successful completion / chat ACK p95');axs[0].set_ylabel('seconds');axs[0].axhline(5,color='red',ls='--',label='Recommendation target 5s');axs[0].legend(fontsize=8)
for i,r in enumerate(rows): axs[0].text(i,r['p95_ms']/1000+.5,f"{r['p95_ms']/1000:.3f}s",ha='center',fontsize=9)
axs[1].bar(labels,[r['business_tps_per_60s'] for r in rows],label='Completed or ACKed / 60s',color='#2563eb');axs[1].plot(labels,[r['target_tps'] for r in rows],'ko',label='Scheduled / second');axs[1].set_title('Business throughput');axs[1].set_ylabel('transactions or messages / second');axs[1].legend(fontsize=8)
axs[2].bar(labels,[r['queue_sampled_max'] for r in rows],color='#f97316');axs[2].set_title('Recommendation queue peak (5s samples)');axs[2].set_ylabel('pending tasks')
fig.suptitle('Short local exploration — 60s, one run each; no optimization',fontsize=13)
fig.savefig(out/'overview.png',dpi=170);fig.savefig(out/'overview.svg');plt.close(fig)
lines=['# 짧은 부하 탐색 결과','', '각 조건30초 워밍업·60초 탐색·1회 실행. MySQL9.7.0, 기존 제품 코드/자원, 모의 추천2초·전사1초. 5분·3회 기준선과 구분하며 운영 최대 수용량이나 개선율을 주장하지 않는다.', '', '![부하 탐색 그래프](overview.png)','', '|조건|실행 VU 표본 최고|관측 진행 중 업무 최고|목표 TPS|완료/ACK 건수 ÷60초|전체 실행 HTTP RPS|p95 ms|완료율|대기열 표본 최고|', '|---|---:|---:|---:|---:|---:|---:|---:|---:|']
for r in rows: lines.append(f"|{r['trial']}|{r['vu_running_sampled_peak']:g}|{r['observed_business_inflight_peak']}|{r['target_tps']:g}|{r['business_tps_per_60s']:.2f}|{r['http_rps_full_run']:.2f}|{r['p95_ms']:.1f}|{r['completion_rate']*100:.1f}%|{r['queue_sampled_max']:g}|")
lines+=['','VU는 k6 가상 사용자 실행 풀이다. 추천의 관측 진행 중 업무 수는 started/completed 원본 이벤트의 차이이며 미리 할당50/최대150 VU와 구분한다. k6 vus gauge는 실행 중 VU의 주기적 표본이며 순간 최대는 업무 이벤트 차이로 보완한다. 채팅은 실제 접속25명이 각각2초 간격으로 보낸다.', '', '완료 TPS의60초 분모는 입력 구간으로 정규화한 값이며 종료 뒤 drain에서 완료된 요청도 포함한다. 실제 전체 실행 평균은 summary.csv의 business_tps_full_run이다. HTTP RPS는 k6 전체 실행 평균이다. TEXT 성공 흐름3 HTTP·VOICE4 HTTP; 채팅 메시지는 WebSocket이므로 HTTP RPS로 메시지 처리량을 해석할 수 없다.', '', '관측 실패·미전송·누락·중복은 summary.csv에 포함한다. exit99는 정한 성능 기준 위반이며 도구 실행 오류와 구분한다. 추천 p95는 성공한 요청만의 값이다. 대기열 gauge는5초 표본이며 짧은 변동을 놓칠 수 있다. histogram 30초 rate 기반 p95 최고는 근사치이며 k6 사용자 지연과 다르다.', '', '채팅25VU:750건 전송·ACK,18750건 클라이언트 수신,누락/중복/거절0,ACK·수신p95 모두54ms. 송신 입력 구간으로 정규화한12.5msg/s와312.5delivery/s를 연결·정리 포함 평균10.53msg/s·263.33delivery/s와 구분한다.', '', '추천 실행 풀은 코드상 core4/max8/queue100이다. TEXT4rps에서 실행4·대기열최고79가 관측돼 큐가 차기 전에 작업자가 늘지 않는 구조가 병목 후보다. 먼저150 VU를 사전 할당해 같은4rps를 재검증하고, 실행 풀/대기열 개선은 별도 조건에서 전후 반복 비교한다. 아직 개선율은 없다.', '', 'TEXT4rps는 본 측정 dropped38건·p95 약42초로 기준 위반이다. 시작된 요청100% 완료율은 미시작 요청을 제외한다. preAllocated50에서 실행 VU가 증가하는 동안 누락이 발생했으므로 고정150 VU 재검증으로 부하 발생기 영향을 분리해야 한다. 대기열/실행 풀 관측과 함께 병목 후보로 판단하며 최대 TPS를 확정하지 않는다.', '', '원본: performance/results/'+batch.name+'/steps.jsonl 및 각 run_id의 raw.jsonl/summary.json/manifest.json. 개인 JWT/DB 백업은 비공개 results에 유지한다.', '', '재생성: 임시 matplotlib 환경의 Python으로 performance/scripts/plot-explore.py <batch> <report> 실행. 저장된 Prometheus JSON은 재사용한다.']
(out/'README.md').write_text('\n'.join(lines)+'\n')
(out/'artifact-hashes.json').write_text(json.dumps({p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(out.iterdir()) if p.is_file() and p.name!='artifact-hashes.json'},indent=2))
print(json.dumps(rows,indent=2))
