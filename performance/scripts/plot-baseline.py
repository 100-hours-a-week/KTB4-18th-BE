"""Export standalone PNG/SVG and CSV from a completed batch; no smoke pooling."""
import csv
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import re
import sys
import urllib.parse
import urllib.request

os.environ.setdefault('MPLCONFIGDIR', '/private/tmp/meomuneum-perf-mpl')
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
import numpy as np

batch = Path(sys.argv[1]).resolve()
destination = Path(sys.argv[2]).resolve()
destination.mkdir(parents=True, exist_ok=True)
cache = Path(os.environ['PERF_PROMETHEUS_CACHE']).resolve() if os.environ.get('PERF_PROMETHEUS_CACHE') else None
steps = [json.loads(line) for line in (batch / 'steps.jsonl').read_text().splitlines()]
assert steps[-1]['event'] == 'batch-complete', 'Only a completed batch is a baseline report'
runs = [entry for entry in steps if entry['event'] == 'run-recorded' and entry['phase'] == 'baseline']
assert len(runs) == 9, 'Expected nine measurement runs'
colors = ['#2563eb', '#f97316', '#16a34a']
plt.rcParams.update({'font.size': 10, 'axes.spines.top': False, 'axes.spines.right': False,
                     'figure.facecolor': 'white', 'axes.grid': True, 'grid.alpha': .2})


def stamp(value):
    return dt.datetime.fromisoformat(value.replace('Z', '+00:00')).timestamp()


def memory(value):
    number, unit = re.match(r'([\d.]+)\s*([A-Za-z]+)', value).groups()
    multiplier = {'B': 1, 'KiB': 1024, 'MiB': 1024**2, 'GiB': 1024**3,
                  'kB': 1000, 'MB': 1000**2, 'GB': 1000**3}[unit]
    return float(number) * multiplier / 1024**2


rows, evidence = [], []
for entry in runs:
    run = batch.parent / entry['run_id']
    summary = json.loads((run / 'summary.json').read_text())
    manifest = json.loads((run / 'manifest.json').read_text())
    assert int((run / 'exit-code.txt').read_text()) == 0, 'Failed run needs explicit analysis'
    metrics = summary['metrics']
    values = lambda key: metrics.get(key, {}).get('values', {})
    points = []
    for line in (run / 'raw.jsonl').open():
        point = json.loads(line)
        if point['type'] == 'Point':
            points.append({'metric': point['metric'], **point['data']})
    begin, end = min(stamp(p['time']) for p in points), max(stamp(p['time']) for p in points)
    chat = entry['trial'].startswith('chat')
    duration_key = 'chat_ack_ms' if chat else 'recommendation_e2e_ms'
    completed_key = 'chat_ready' if chat else 'recommendation_completed'
    counts = values(completed_key)
    transcription = [p['value'] for p in points if p['metric'] == 'http_req_duration'
                     and p.get('tags', {}).get('name') == 'recommendation.transcription'
                     and p.get('tags', {}).get('status') == '200']
    row = {'trial': entry['trial'], 'run_id': run.name,
           'samples': sum(p['metric'] == duration_key for p in points),
           'successful': counts.get('passes', 0), 'failed': counts.get('fails', 0),
           'completed_rate': counts.get('rate', 0), 'p95_ms': values(duration_key).get('p(95)'),
           'p99_ms': float(np.percentile([p['value'] for p in points if p['metric'] == duration_key], 99)),
           'chat_delivery_p95_ms': values('chat_delivery_ms').get('p(95)'),
           'sent': values('chat_sent').get('count', 0), 'delivered': values('chat_delivered').get('count', 0),
           'missing': values('chat_missing').get('count', 0), 'duplicates': values('chat_duplicates').get('count', 0),
           'rejected': values('chat_rejected').get('count', 0),
           'dropped_iterations': '' if chat else values('dropped_iterations').get('count', 0),
           'started': values('recommendation_started').get('count', 0),
           'transcription_http_samples': len(transcription),
           'transcription_http_p95_ms': float(np.percentile(transcription, 95)) if transcription else '',
           'successful_per_s_300s': counts.get('passes', 0) / 300 if not chat else '',
           'checks_passed': values('checks').get('passes', 0), 'checks_failed': values('checks').get('fails', 0)}
    resources, captured = [], None
    for line in (run / 'container-stats.jsonl').read_text().splitlines():
        sample = json.loads(line)
        if 'captured_at' in sample:
            captured = stamp(sample['captured_at'])
        elif '-backend-' in sample.get('Name', '') and captured is not None:
            resources.append({'time_s': captured - begin, 'cpu_percent': float(sample['CPUPerc'].rstrip('%')),
                              'memory_mib': memory(sample['MemUsage'].split('/')[0].strip())})
    assert resources, 'Resource evidence missing'
    row['backend_cpu_max_percent'] = max(r['cpu_percent'] for r in resources)
    row['backend_memory_max_mib'] = max(r['memory_mib'] for r in resources)
    rows.append(row)
    series = {}
    if cache:
        series = json.loads((cache / (entry['trial'] + '-prometheus.json')).read_text())
        timestamps = [x[0] for response in series.values() for item in response['data']['result'] for x in item['values']]
        assert timestamps and min(timestamps) >= begin - 1 and max(timestamps) <= end + 1, 'Cache belongs to another trial'
    else:
        for metric in ('performance_recommendation_queue_size', 'performance_recommendation_active',
                       'hikaricp_connections_active'):
            query = urllib.parse.urlencode({'query': metric, 'start': begin, 'end': end, 'step': 5})
            with urllib.request.urlopen('http://127.0.0.1:19090/api/v1/query_range?' + query, timeout=10) as response:
                series[metric] = json.load(response)
    (destination / (entry['trial'] + '-prometheus.json')).write_text(json.dumps(series, indent=2))
    latency = [{'time_s': stamp(p['time']) - begin, 'metric': p['metric'], 'value_ms': p['value']}
               for p in points if p['metric'] in (duration_key, 'chat_delivery_ms')]
    evidence.append({'row': row, 'latency': latency, 'resources': resources, 'begin': begin, 'end': end,
                     'manifest': manifest, 'series': series})

with (destination / 'summary.csv').open('w', newline='') as file:
    writer = csv.DictWriter(file, fieldnames=list(rows[0]))
    writer.writeheader(); writer.writerows(rows)
(destination / 'graph-data.json').write_text(json.dumps([
    {'trial': e['row']['trial'], 'latency': e['latency'], 'resources': e['resources']} for e in evidence], indent=2))

# Runtime fingerprint excludes report-writing changes, but includes API code,
# workload, mock, profiles, Compose and the executing JAR. Per-run manifests remain intact.
fingerprints = []
for e in evidence:
    m = e['manifest']
    files = {k: v for k, v in m['files'].items() if k.startswith(('src/', 'performance/k6/', 'performance/mock/'))
             or k in ('build.gradle', 'performance/compose.yaml', 'performance/Dockerfile')}
    fingerprints.append(hashlib.sha256(json.dumps({'files': files, 'jar': m['jar_sha256'],
                                                  'mock': m['mock'], 'services': m['services'],
                                                  'audio': m['fixtures']['audio_sha256'],
                                                  'images': sorted(i['image_id'] for i in m['images']),
                                                  'k6': m['k6_version']}, sort_keys=True).encode()).hexdigest())
assert len(set(fingerprints)) == 1, 'Measurement conditions changed'


def save(fig, name):
    fig.savefig(destination / (name + '.png'), dpi=180, bbox_inches='tight')
    fig.savefig(destination / (name + '.svg'), bbox_inches='tight')
    plt.close(fig)


fig, axes = plt.subplots(1, 3, figsize=(15, 4.5), constrained_layout=True)
for index, condition in enumerate(('text', 'voice')):
    subset = [r for r in rows if r['trial'].startswith(condition)]
    axes[0].plot([1, 2, 3], [r['p95_ms'] / 1000 for r in subset], marker='o', label=condition.upper())
axes[0].axhline(5, color='#dc2626', linestyle='--', label='Proposed limit 5s')
axes[0].set(title='Recommendation completion p95', xlabel='Repetition', ylabel='Seconds', ylim=(0, 5.5), xticks=[1, 2, 3])
axes[0].legend()
chat_rows = [r for r in rows if r['trial'].startswith('chat')]
axes[1].plot([1, 2, 3], [r['p95_ms'] for r in chat_rows], marker='o', label='ACK')
axes[1].plot([1, 2, 3], [r['chat_delivery_p95_ms'] for r in chat_rows], marker='o', label='Delivery')
chat_max = max(max(r['p95_ms'], r['chat_delivery_p95_ms']) for r in chat_rows)
axes[1].set(title='Chat p95 (5 users; proposed limit 500ms)', xlabel='Repetition', ylabel='Milliseconds',
            ylim=(0, max(100, chat_max * 1.4)), xticks=[1, 2, 3])
axes[1].legend()
for i, condition in enumerate(('text', 'voice', 'chat')):
    subset = [r for r in rows if r['trial'].startswith(condition)]
    axes[2].bar(np.arange(3) + (i - 1) * .23, [r['completed_rate'] * 100 for r in subset], width=.23, label=condition.upper())
axes[2].set(title='Completion / chat readiness', xlabel='Repetition', ylabel='Percent', ylim=(0, 105), xticks=[0, 1, 2], xticklabels=['1', '2', '3'])
axes[2].legend(loc='lower right')
fig.suptitle('Initial local baseline | 2m warmup + 5m measurement | 3 independent repetitions', fontsize=14)
save(fig, 'overview')

fig, axes = plt.subplots(3, 3, figsize=(16, 11), constrained_layout=True)
for i, condition in enumerate(('text', 'voice', 'chat')):
    subset = [e for e in evidence if e['row']['trial'].startswith(condition)]
    for repetition, e in enumerate(subset):
        data = e['latency']
        metric = 'chat_ack_ms' if condition == 'chat' else 'recommendation_e2e_ms'
        bins = []
        for start in range(0, 330, 30):
            values_in_bin = [p['value_ms'] for p in data if p['metric'] == metric and start <= p['time_s'] < start + 30]
            if values_in_bin: bins.append((start + 15, float(np.percentile(values_in_bin, 95))))
        axes[i, 0].plot([p[0] for p in bins], [p[1] for p in bins], marker='.', color=colors[repetition], label=f'R{repetition+1}')
        for col, key in ((1, 'cpu_percent'), (2, 'memory_mib')):
            axes[i, col].plot([p['time_s'] for p in e['resources']], [p[key] for p in e['resources']], color=colors[repetition], label=f'R{repetition+1}')
    axes[i, 0].set(title=condition.upper() + ' | successful 30s-window p95', ylabel='Milliseconds')
    axes[i, 1].set(title=condition.upper() + ' | backend Docker CPU', ylabel='Percent (100% = one core)')
    axes[i, 2].set(title=condition.upper() + ' | backend Docker memory', ylabel='MiB')
    for ax in axes[i]:
        ax.set_xlabel('Seconds from first k6 sample (includes chat setup/drain)')
        ax.set_ylim(bottom=0); ax.legend()
fig.suptitle('Latency and backend resources | interval p95 is not the whole-run p95', fontsize=14)
save(fig, 'timeline')

report = ['# 개선 전 기준 성능 — 2026-10-11', '',
          'Mac·MySQL9.7.0·모의 AI에서 2분 워밍업 뒤 5분씩 3회 독립 측정했다. 운영 처리량이나 개선율이 아니다.', '',
          '![완료율과 전체 p95](overview.png)', '', '![지연·자원 추이](timeline.png)', '',
          '| 조건 | 회차 | 성공/실패 | 전체 p95(ms) | 수신 p95(ms) | 시작/전송 | dropped |',
          '| --- | --- | --- | --- | --- | --- | --- |']
for r in rows:
    report.append(f"| {r['trial'].rsplit('-r',1)[0]} | {r['trial'].rsplit('-r',1)[1]} | {r['successful']}/{r['failed']} | {r['p95_ms']:.2f} | {r['chat_delivery_p95_ms'] if r['chat_delivery_p95_ms'] is not None else '—'} | {r['started'] or r['sent']} | {r['dropped_iterations']} |")
report += ['', '## 조건과 해석', '',
           f"- 호스트: {evidence[0]['manifest']['host']['architecture']}, 논리CPU {evidence[0]['manifest']['host']['logical_cpus']}개, 메모리 {int(evidence[0]['manifest']['host']['memory_bytes']) / 1024**3:.0f}GiB. k6와 Docker VM이 호스트를 공유한다.",
           '- backend CPU2·메모리1GiB·JVM heap512MiB·Hikari10, MySQL CPU1·메모리1GiB·buffer pool256MiB, mock CPU1·메모리256MiB 조건이다. MySQL9.7.0과 각 실제 이미지ID를 고정했다.',
           '- 모의 AI 첫text200ms·추천완료2000ms·전사1000ms·고정5곡 조건이다. 실제 AI·외부 지도·iTunes·로그인 부하는 제외한다.',
           '- TEXT·VOICE 각각 초당 추천 시작1건, 측정300초다. VOICE는9.8초 MP4 업로드·전사→추천 done을 포함한다. 성공 처리량은 성공수/300초이며 HTTP 전체 RPS와 구분한다.',
           '- 채팅 한 방5명이2초 간격으로150개씩 전송한다. 기대 전송750·수신3750(본인 포함)이며 readiness5명과 실제 ACK·방송·누락·중복·거절을 함께 확인한다. 요약 표의 채팅 성공수는 준비 완료 계정수다.',
           '- 잠정 기준은 추천 p95<5초·완료율≥99%, 채팅 ACK/수신 p95<500ms·누락/중복/거절0이다. 서비스 SLO로 확정한 값은 아니다.',
           '- 각 반복 전 전용4개 테이블 백업·초기화·backend 재기동을 수행했다. 워밍업은 집계에서 제외했다. 코드·JAR·모의 응답·자원·음성·이미지ID·k6 버전을 대조했다. 배치 중 변경은 백업 GTID 처리·scrape 대기·재개 및 분석 도구이며 제품·부하 조건은 유지했다.',
           '- 실제 추천 시작은 회차에 따라300 또는301건이며 전부 기록했다. 채팅은 per-vu-iterations라 dropped_iterations가 적용되지 않아 CSV에는 비워두었다.',
           '- p95는 각 실행 전체 성공 표본의 k6 summary 값이다. 3회 p95를 평균내 전체 p95로 표현하지 않았다. p99는 원본 성공 표본의 선형 보간 percentile이다. CSV의 전사 HTTP p95는 전사200 응답의 http_req_duration 원본에서 별도로 계산했으며 음성 전체 E2E와 구분한다.',
           '- 그래프 추이의 p95는30초 구간 표본이다. 마지막 구간은 완료 대기 때문에 적은 표본일 수 있다. Docker CPU100%는1코어이며 backend 제한은2코어다. 자원 샘플은 약7초 간격이다.',
           '- 현재 낮은 시작률과5명 방 조건만 확인했다. 최대 동접·한계 처리량·운영 성능·실제 AI 성능은 이 결과로 확정할 수 없다.',
           '- 채팅 p95는33~76.55ms로 회차별 변동이 있다. 코드 변경 전후 결과가 아니므로 회차 차이를 개선율로 계산하지 않는다.',
           '- Prometheus5초 표본에서 추천 대기열은0, 활성 추천은최대2로 관측됐다. Hikari 활성 연결은0~1로 관측됐지만 짧은 DB 작업은 수집 사이에 지나갈 수 있어 DB 병목 부재를 단정하지 않는다.',
           '- 현 조건에서 잠정 기준을 모두 통과했다. 의미 있는 최적화 근거를 만들려면 별도 후속 실험에서 시작률·방 인원을 단계적으로 늘려 병목과 한계를 확인해야 한다.',
           '- 실패 실행과 원본·DB백업은 비공개 results에 보존한다. CSV/그래프는 원본에서 파생했으며 비밀값·사용자 토큰을 포함하지 않는다.', '',
           '## 원본 및 재생성', '', f'- 배치: `{batch.name}`.',
           f'- 측정 조건 fingerprint: `{fingerprints[0]}`.',
           '- summary.csv, graph-data.json, 회차별 Prometheus query_range JSON, PNG·SVG를 함께 보존한다.',
           '- matplotlib3.11.2 설치 Python에서 `python plot-baseline.py <배치 폴더> <출력 폴더>`로 재생성한다.', '']
report += ['- 보존한 시계열로 오프라인 재생성하려면 `PERF_PROMETHEUS_CACHE=<기존 보고서 폴더>`를 설정하고 별도 출력 폴더를 지정한다. 실제 raw·자원·배치 원본은 함께 보존해야 한다.', '']
report += [f"- `{r['trial']}` → `{r['run_id']}`" for r in rows]
(destination / 'README.md').write_text('\n'.join(report))
print('Saved nine-run CSV, source data, Prometheus evidence and PNG/SVG graphs: ' + str(destination))
