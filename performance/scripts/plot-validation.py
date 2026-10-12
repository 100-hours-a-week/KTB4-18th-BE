"""Repeated fixed-VU evidence, independent raw checks and cached monitoring for reproduction."""
import csv, datetime as dt, hashlib, json, math, os, re, sys, urllib.parse, urllib.request
from pathlib import Path
os.environ.setdefault('MPLCONFIGDIR','/private/tmp/meomuneum-perf-mpl')
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
import numpy as np
batch, out=map(lambda p:Path(p).resolve(),sys.argv[1:3])
out.mkdir(parents=True,exist_ok=True)
steps=[json.loads(s) for s in (batch/'steps.jsonl').read_text().splitlines()]
assert steps[-1]['event']=='batch-complete','Only complete batches can be reported'
selection=batch/'accepted-runs.json'
entries=json.loads(selection.read_text())['accepted'] if selection.exists() else [s for s in steps if s['event']=='run-recorded']
assert len(entries)==12 and sum(s['phase']=='validation' for s in entries)==6
base=json.loads((batch.parent/'20261011T120644Z-recommendation-baseline/manifest.json').read_text())
queries={'rejected_total':'performance_recommendation_rejected_total','queue':'performance_recommendation_queue_size','active':'performance_recommendation_active',
         'hikari_active':'hikaricp_connections_active','hikari_pending':'hikaricp_connections_pending',
         'queue_wait_p95':'histogram_quantile(0.95, sum(rate(performance_recommendation_queue_wait_seconds_bucket[30s])) by (le))',
         'first_text_p95':'histogram_quantile(0.95, sum(rate(performance_recommendation_first_text_seconds_bucket[30s])) by (le))'}
plt.rcParams.update({'font.size':10,'axes.spines.top':False,'axes.spines.right':False,'axes.grid':True,'grid.alpha':.2})
def stamp(t):return dt.datetime.fromisoformat(t.replace('Z','+00:00')).timestamp()
def digest(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def memory(value):
    n,u=re.match(r'([\d.]+)\s*([A-Za-z]+)',value).groups()
    return float(n)*{'B':1,'KiB':1024,'MiB':1024**2,'GiB':1024**3,'kB':1000,'MB':1000**2,'GB':1000**3}[u]/1024**2
records=[]
for entry in entries:
    run=batch.parent/entry['run_id'];meta=json.loads((run/'scenario.json').read_text())
    manifest=json.loads((run/'manifest.json').read_text());metrics=json.loads((run/'summary.json').read_text())['metrics']
    phase=entry['phase'];seconds=120 if phase=='warmup' else 300
    assert meta['VUS']==meta['MAX_VUS']=='150' and meta['INPUT_TYPE']=='TEXT'
    assert meta['DURATION']==('2m' if phase=='warmup' else '5m')
    assert meta['RATE']==('2' if entry['trial'].startswith('text-2') else '4')
    assert manifest['jar_sha256']==base['jar_sha256'],'Product JAR changed'
    assert manifest['mock']==base['mock'] and manifest['k6_version']==base['k6_version']
    assert {i['image_id'] for i in manifest['images']}=={i['image_id'] for i in base['images']}
    for key,value in base['files'].items():
        if key.startswith(('src/','performance/k6/','performance/mock/')) or key=='performance/compose.yaml':
            assert manifest['files'][key]==value,(run.name,key)
    assert manifest['services']==base['services'],'Service configuration changed'
    options=run/'resolved-options.json';resolved=json.loads(options.read_text())
    corrected=False
    if 'recommendations' not in resolved['scenarios']:
        options=batch/(run.name+'-options-corrected.json');resolved=json.loads(options.read_text());corrected=True
    opt=resolved['scenarios']['recommendations']
    assert opt['preAllocatedVUs']==opt['maxVUs']==150 and opt['rate']==int(meta['RATE'])
    assert opt['duration']==('2m0s' if phase=='warmup' else '5m0s')
    console=(run/'console.txt').read_text()
    assert f"{int(meta['RATE']):.2f} iterations/s for" in console and 'maxVUs: 150' in console
    def val(k,f,default=0):return metrics.get(k,{}).get('values',{}).get(f,default)
    points=[]
    for line in (run/'raw.jsonl').open():
        p=json.loads(line)
        if p['type']=='Point':points.append({'metric':p['metric'],**p['data']})
    starts=[p for p in points if p['metric']=='recommendation_started']
    completes=[p for p in points if p['metric']=='recommendation_completed']
    durations=[p for p in points if p['metric']=='recommendation_e2e_ms']
    begin=min(stamp(p['time']) for p in starts);end=max(stamp(p['time']) for p in points)
    assert len(starts)==val('recommendation_started','count')
    success=sum(p['value']==1 for p in completes);failed=sum(p['value']==0 for p in completes)
    assert success==val('recommendation_completed','passes') and failed==val('recommendation_completed','fails')
    assert len(completes)==len(starts),'Incomplete workload, do not hide interruption'
    assert len(durations)==success
    assert all(p['value']>=0 for p in durations),'Wall-clock anomaly: exclude and rerun affected trial'
    p95=float(np.percentile([p['value'] for p in durations],95))
    assert math.isclose(p95,val('recommendation_e2e_ms','p(95)'),rel_tol=1e-10)
    inflight=peak=0
    for p in sorted(points,key=lambda p:stamp(p['time'])):
        if p['metric']=='recommendation_started':inflight+=int(p['value']);peak=max(peak,inflight)
        elif p['metric']=='recommendation_completed':inflight-=1
    assert inflight==0
    cache=out/(entry['trial']+'-'+phase+'-prometheus.json')
    if cache.exists():monitor=json.loads(cache.read_text());assert monitor['start']==begin and monitor['end']==end
    else:
        monitor={'start':begin,'end':end,'step_seconds':5,'queries':{}}
        for k,q in queries.items():
            url='http://127.0.0.1:19090/api/v1/query_range?'+urllib.parse.urlencode({'query':q,'start':begin,'end':end,'step':5})
            with urllib.request.urlopen(url,timeout=15) as response:data=json.load(response)
            assert data['status']=='success';monitor['queries'][k]=data
        cache.write_text(json.dumps(monitor,indent=2))
    series={k:[[float(t)-begin,float(v)] for item in data['data']['result'] for t,v in item.get('values',[]) if math.isfinite(float(v))] for k,data in monitor['queries'].items()}
    assert series['queue'] and series['active'],'Required scrape evidence missing'
    resources=[];captured=None
    for line in (run/'container-stats.jsonl').read_text().splitlines():
        sample=json.loads(line)
        if 'captured_at' in sample:captured=stamp(sample['captured_at'])
        elif '-backend-' in sample.get('Name','') and captured is not None:
            resources.append({'t':captured-begin,'cpu_percent':float(sample['CPUPerc'].rstrip('%')),'memory_mib':memory(sample['MemUsage'].split('/')[0].strip())})
    assert resources
    total_seconds=val('http_reqs','count')/val('http_reqs','rate')
    dropped=val('dropped_iterations','count')
    row={'trial':entry['trial'],'scenario_trial':meta['TRIAL'],'phase':phase,'run_id':run.name,'exit_code':int((run/'exit-code.txt').read_text()),
         'target_starts_per_s':int(meta['RATE']),'input_seconds':seconds,'preallocated_vus':150,'max_vus':150,
         'running_vu_sampled_max':val('vus','max'),'business_inflight_peak':peak,'started':len(starts),'successful':success,'failed':failed,
         'completion_rate_started':success/len(starts),'completion_rate_started_plus_dropped':success/(len(starts)+dropped),
         'dropped_iterations':dropped,'successes_per_input_second':success/seconds,
         'successful_tps_full_run':success/total_seconds,
         'successful_tps_during_input':sum(p['value']==1 and begin<=stamp(p['time'])<begin+seconds for p in completes)/seconds,
         'last_30s_successful_tps':sum(p['value']==1 and begin+seconds-30<=stamp(p['time'])<begin+seconds for p in completes)/30,
         'http_rps_full_run':val('http_reqs','rate'),'p95_ms':p95,'p99_ms':float(np.percentile([p['value'] for p in durations],99)),
         'queue_sampled_max':max(v for t,v in series['queue']),'active_sampled_max':max(v for t,v in series['active']),
         'queue_sampled_last':series['queue'][-1][1],
         'backend_rejected_counter_sampled_max':max((v for t,v in series['rejected_total']),default=0),
         'hikari_pending_sampled_max':max((v for t,v in series['hikari_pending']),default=0),
         'backend_cpu_max_percent':max(p['cpu_percent'] for p in resources),'backend_memory_max_mib':max(p['memory_mib'] for p in resources),
         'full_run_seconds':total_seconds,'options_corrected_copy':corrected}
    records.append({'row':row,'begin':begin,'end':end,'monitor':series,'resources':resources,
                    'durations':[[stamp(p['time'])-begin,p['value']] for p in durations],
                    'successful_completions': [stamp(p['time'])-begin for p in completes if p['value']==1],
                    'raw_sha256':digest(run/'raw.jsonl'),'options_sha256':digest(options)})
rows=[r['row'] for r in records];measured=[r for r in records if r['row']['phase']=='validation']
assert len(measured)==6
with (out/'all-runs.csv').open('w') as f:
    w=csv.DictWriter(f,fieldnames=rows[0]);w.writeheader();w.writerows(rows)
with (out/'summary.csv').open('w') as f:
    w=csv.DictWriter(f,fieldnames=rows[0]);w.writeheader();w.writerows(r['row'] for r in measured)
(out/'graph-data.json').write_text(json.dumps(records,indent=2))
labels=[r['row']['trial'].replace('text-','') for r in measured]
colors=['#2563eb' if r['row']['target_starts_per_s']==2 else '#f97316' for r in measured]
fig,axs=plt.subplots(2,2,figsize=(13,8),layout='constrained')
axs[0,0].bar(labels,[r['row']['p95_ms']/1000 for r in measured],color=colors);axs[0,0].axhline(5,color='red',ls='--',label='5s target');axs[0,0].set_title('Successful recommendation p95');axs[0,0].set_ylabel('seconds');axs[0,0].legend()
axs[0,1].plot(labels,[r['row']['target_starts_per_s'] for r in measured],'ko',label='Target start rate');axs[0,1].bar(labels,[r['row']['successful_tps_full_run'] for r in measured],color=colors,label='Completions / full run');axs[0,1].set_title('Full-run successful TPS (includes drain)');axs[0,1].legend(fontsize=9)
axs[1,0].bar(labels,[r['row']['queue_sampled_max'] for r in measured],color=colors);axs[1,0].set_title('Queue peak, 5s samples');axs[1,0].set_ylabel('pending tasks')
axs[1,1].bar(labels,[r['row']['completion_rate_started_plus_dropped']*100 for r in measured],color=colors);axs[1,1].set_ylim(0,105);axs[1,1].set_title('Successful / (started + dropped)');axs[1,1].set_ylabel('percent')
for ax in axs.flat:ax.tick_params(axis='x',rotation=25)
fig.suptitle('Fixed 150 VUs — 2min warmup + 5min input, 3 repetitions; no optimization')
fig.savefig(out/'overview.png',dpi=170);fig.savefig(out/'overview.svg');plt.close(fig)
fig,axs=plt.subplots(6,3,figsize=(15,18),layout='constrained')
for i,record in enumerate(measured):
    row=record['row'];warm=next(r for r in records if r['row']['trial']==row['trial'] and r['row']['phase']=='warmup')
    offset=record['begin']-warm['begin']
    for part,shift in [(warm,0),(record,offset)]:
        for key,color in [('queue','#f97316'),('active','#2563eb')]:
            pts=part['monitor'][key];axs[i,0].plot([(t+shift)/60 for t,v in pts],[v for t,v in pts],color=color,label=key if part is warm else None)
    axs[i,0].axvline(offset/60,color='gray',ls='--');axs[i,0].set_title(row['trial']+' queue / active');axs[i,0].set_ylabel('tasks');axs[i,0].set_xlabel('minutes from warmup start');axs[i,0].legend(fontsize=8)
    bins=[]
    for lower in range(0,int(record['end']-record['begin'])+1,30):
        sample=[v for t,v in record['durations'] if lower<=t<lower+30]
        if sample:bins.append((lower+15,float(np.percentile(sample,95))/1000))
    axs[i,1].plot([t/60 for t,v in bins],[v for t,v in bins],color='#2563eb',marker='o',ms=3);axs[i,1].axhline(5,color='red',ls='--');axs[i,1].set_title('Measurement completions: 30s-window p95');axs[i,1].set_ylabel('seconds');axs[i,1].set_xlabel('minutes from measurement start')
    pts=record['resources'];axs[i,2].plot([p['t']/60 for p in pts],[p['cpu_percent'] for p in pts],color='#16a34a');axs[i,2].set_title('Backend Docker CPU, 100% = one core');axs[i,2].set_ylabel('percent');axs[i,2].set_xlabel('minutes from measurement start')
fig.suptitle('Warmup growth and measurement behavior — separate k6 runs with completion drain',fontsize=15)
fig.savefig(out/'timeline.png',dpi=150);fig.savefig(out/'timeline.svg');plt.close(fig)
lines=['# 추천 고정 VU 반복 재검증','', 'TEXT2/4rps, preAllocatedVUs=maxVUs=150, 조건별 초기화·재시작→2분워밍업→5분측정3회.2→4 순서3회 반복. MySQL9.7.0·고정mock추천2초·동일제품JAR/자원. 최적화 없음.', '', '![요약](overview.png)', '', '|조건|성공|실패|미시작|p95초|전체평균TPS|입력구간완료TPS|HTTP RPS|실행VU표본최고|대기열최고|실행작업최고|', '|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|']
for r in measured:
    x=r['row'];lines.append(f"|{x['trial']}|{x['successful']}|{x['failed']}|{x['dropped_iterations']}|{x['p95_ms']/1000:.3f}|{x['successful_tps_full_run']:.3f}|{x['successful_tps_during_input']:.3f}|{x['http_rps_full_run']:.3f}|{x['running_vu_sampled_max']}|{x['queue_sampled_max']:g}|{x['active_sampled_max']:g}|")
two=[r['row'] for r in measured if r['row']['target_starts_per_s']==2]
four=[r['row'] for r in measured if r['row']['target_starts_per_s']==4]
warm4=[r['row'] for r in records if r['row']['phase']=='warmup' and r['row']['target_starts_per_s']==4]
selection_data=json.loads(selection.read_text()) if selection.exists() else {}
(out/'run-selection.json').write_text(json.dumps(selection_data,indent=2))
lines+=['', '## 결과와 다음 판단', '',
 f"- 본 측정6회 합계{sum(r['row']['successful'] for r in measured)}건 모두완료,실패/미시작0. TEXT2p95 {min(x['p95_ms'] for x in two)/1000:.3f}~{max(x['p95_ms'] for x in two)/1000:.3f}초,3회중1회5초기준위반. TEXT4p95 {min(x['p95_ms'] for x in four)/1000:.3f}~{max(x['p95_ms'] for x in four)/1000:.3f}초.",
 f"- 선택된4rps워밍업3회 모두기준위반. 대기열100·실행작업4→8이관측됐고,합계{sum(x['failed'] for x in warm4)}건이실패했다. 워밍업p95 {min(x['p95_ms'] for x in warm4)/1000:.3f}~{max(x['p95_ms'] for x in warm4)/1000:.3f}초. 서버rejected카운터도각회차실패건수와일치한다.",
 '- 모든본측정에서후반지연·대기열증가가관측됐다. 4rps가5분전체p95를통과해도장시간안정적수용량이라고결론내리지않는다. 2rps도5초경계여서여유있는성능이라고표현하지않는다.',
 '- 제품코드core4/max8/queue100과초기대기열이차기전에worker가늘지않는시계열이일치한다. 부하발생기150VU고정에서dropped0인상태로이패턴이재현돼추천실행풀/대기열정책이우선개선대상이다. CPU/DB표본만으로다른병목을배제하지않는다.',
 '- 다음은실행풀/대기열정책의한가지개선과같은조건전후비교다. 모의AI지연·DB버전·자원을고정하고기준위반/초기거절/후반지연을함께비교한다. 실제제품최적화나개선율산출은아직없다.',
 '- 시간역행으로음수15개가나온원래4rps-r2본측정과짝워밍업은비교에서제외하고동일조건재실행으로대체했다. 실패를제외한것이아니며2rps-r3의유효지연기준위반은유지했다. 모든제외원본과사유는run-selection.json및비공개results에보존한다.',
 '- 최초셸기록오류시도1개,원래배치12실행,대체2실행총15실행을보존했다. 정식선택12개중6개는워밍업,6개는본측정이다. 실행은2026-10-11~12KST이며대체전약25분간격이있다.',
 '', '![대기열·실행 작업·지연·CPU 변화](timeline.png)', '', '## 해석 범위', '', '- VU150은 사전 할당 풀이다. 실제 실행 중 VU표본 및 started/completed이벤트 기반 업무 동시진행 최고는 CSV에 구분한다.', '- 전체 평균TPS는 종료대기 포함 k6전체기간 분모이다. 입력구간TPS는 시작 이후300초 안에 완료된 성공건수/300이며 이후완료는 제외한다. successes_per_input_second는 drain완료도 포함한 정규화 수치다. HTTP RPS는전체평균이며 성공TEXT1업무는3HTTP흐름이다.', '- p95/p99는 성공만의 지연이며 완료율·실패·미시작과 함께 해석한다. 원본p95를 독립 재계산해12실행summary와 대조했다. 종료코드99는기준위반,0은기준통과다.', '- 워밍업은 별도 k6실행이며 모든 started업무가 종료된 후 측정을 시작한다. 요청대기열은그 사이비워질 수 있고 늘어난worker는남을 수 있다. 이 결과는 같은 절차의 개선전 비교근거이며 연속부하/콜드스타트/장시간soak/운영수용량을대체하지 않는다.', '- queue/active/DBpool은5초표본이다. histogram30초rate p95는근사치이며 상단bucket30초를넘는꼬리정밀도가제한된다. 측정초기30초query는워밍업종료구간을포함할수 있다. rejected카운터최고는백엔드재시작이후누적값이므로워밍업거절도포함한다. queue마지막표본은종료완료시점이전일수있다. 30초지연그래프의마지막빈은종료대기의부분구간표본을포함한다.', '- DockerCPU100%=한코어,메모리는컨테이너RSS/캐시측정이며heap아니다. Mac전체·mock·모니터링자원경쟁은존재한다.', '- 정식12실행에서제외한첫워밍업시도의사전옵션파일은inspect기본env미전달문제로smoke설정이기록돼배치에corrected옵션을별도보존했다. 원본은수정하지않았고실제console의RATE/기간/VU와raw건수를검증했다. 이후inspect는system-env전달옵션으로수정했다.', '', '## 원본과 재생성', '', f'비공개원본: performance/results/{batch.name}/ 및 CSV각run_id의raw.jsonl·manifest.json·summary.json·console.txt. 백업/JWT는공개하지 않는다.', '', 'summary.csv는6측정,all-runs.csv는12워밍업/측정,graph-data.json은시계열과원본SHA,각prometheus.json은조회캐시다. 보고서artifact-hashes.json은결과파일해시이다.', '', '재생성: matplotlib/numpy환경Python으로 performance/scripts/plot-validation.py <batch> <report> 실행. Prometheus캐시가있으면네트워크없이재생성하며timestamp를검증한다.']
(out/'README.md').write_text('\n'.join(lines)+'\n')
(out/'verification.json').write_text(json.dumps({'accepted_runs':12,'measurements':6,'independent_raw_p95_checks':12,'fixed_vu_options_checks':12,'runtime_fingerprint_checks':12,'aborted_attempt_excluded':True,'steps_sha256':digest(batch/'steps.jsonl'),'plotter_sha256':digest(Path(__file__)),'matplotlib_version':matplotlib.__version__,'numpy_version':np.__version__},indent=2))
(out/'artifact-hashes.json').write_text(json.dumps({p.name:digest(p) for p in sorted(out.iterdir()) if p.is_file() and p.name!='artifact-hashes.json'},indent=2))
print(json.dumps([r['row'] for r in measured],indent=2))
