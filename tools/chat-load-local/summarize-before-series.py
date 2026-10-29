#!/usr/bin/env python3
"""Rebuild baseline CSV/JSON/report from preserved results; no DB or auth writes."""
import argparse,csv,hashlib,json,statistics
from datetime import datetime,timezone,timedelta
from pathlib import Path
ROOT=Path(__file__).resolve().parent
p=argparse.ArgumentParser(description=__doc__);p.add_argument('series',type=Path);args=p.parse_args()
out=args.series.resolve();s=json.loads((out/'series.json').read_text())
assert s['status']=='completed' and len(s['rounds'])==3
rows=[];audits=[];hashes=set();jars=set()
for trial in s['rounds']:
 d=Path(trial['directory']);r=json.loads((d/'result.json').read_text());m=json.loads((d/'manifest.json').read_text());v=json.loads((d/'verification.json').read_text());k=json.loads((d/'summary.json').read_text());cmd=json.loads((d/'k6-command.json').read_text());auth=json.loads(Path(cmd['authentication_summary']).read_text())
 assert trial['analysis_exit_code']==0 and v['raw_counts_and_p95_match'] and r['cleanup_clean'] and r['reports_complete']
 assert auth['requested']==auth['success']==1000 and not auth['failures']
 assert m['earliest_access_expiry']>r['run_window']['end']
 assert all(x['ok'] for x in r['timeseries_export'].values())
 hashes.add(m['source_sha256']['k6/chat-load.js']);jars.add(m['build']['jar_sha256'])
 counters={name:k['metrics']['chat_'+name]['values']['count'] for name in ['ws_opened','ws_closed','unexpected_closes','socket_errors','protocol_errors','clock_errors','scheduled_missed','rejected','duplicate','missing','unconfirmed','ack_missing','cleanup_errors']}
 warm={a['target']:a for a in r['phases'] if a['phase']=='warmup'}
 for a in r['phases']:
  if a['phase']!='measure':continue
  row={'round':trial['round'],'run_id':r['run_id'],**a,'warmup_scheduled_missed':warm[a['target']]['scheduled_missed'],'actual_send_rate':a['attempted']/a['seconds'],'app_commit':m['build']['source_head'],'jar_sha256':m['build']['jar_sha256'],'script_sha256':m['source_sha256']['k6/chat-load.js']}
  row['measured_input_and_delivery_met']=a['minimum_ready']==a['target'] and a['attempted']==a['target_messages']==a['accepted']==a['ack'] and a['received']==a['expected'] and all(a[z]==0 for z in ['missing','rejected','unconfirmed','ack_missing','scheduled_missed'])
  rows.append(row)
 audits.append({'round':trial['round'],'run_id':r['run_id'],'raw_verified':True,'all_thresholds_passed':v['run_passed'],'failed_thresholds':v['failed_thresholds'],'k6_exit_code':r['k6_exit_code'],'auth_verified':1000,'auth_summary':cmd['authentication_summary'],'auth_valid_through_run':True,'counters':counters,'event_loop_lag_ms':k['metrics']['chat_event_loop_lag_ms']['values'],'final_db':r['final_db'],'timeseries_export':r['timeseries_export']})
assert len(rows)==9 and len(hashes)==len(jars)==1
with (out/'measured-results.csv').open('w',newline='') as f:
 w=csv.DictWriter(f,fieldnames=list(rows[0]));w.writeheader();w.writerows(rows)
medians={str(n):statistics.median(a['receive_p95_ms'] for a in rows if a['target']==n) for n in [100,500,1000]}
kst=timezone(timedelta(hours=9));start=datetime.fromisoformat(s['started_at']);finish=datetime.fromisoformat(s['finished_at'])
report={'completed_rounds':3,'fully_passed_rounds':sum(a['all_thresholds_passed'] for a in audits),'started_kst':start.astimezone(kst).isoformat(),'finished_kst':finish.astimezone(kst).isoformat(),'elapsed_minutes':(finish-start).total_seconds()/60,'observed_p95_medians_ms':medians,'measured_rows':rows,'round_audits':audits,'measured_send_shortfall':sum(a['target_messages']-a['attempted'] for a in rows),'measured_received_total':sum(a['received'] for a in rows),'rounds_excluded_or_replaced':0,'conditions_changed_during_series':False,'archived_jar':s['archived_jar'],'jar_sha256':s['jar_sha256'],'script_sha256':next(iter(hashes)),'limitation':'Observed p95 includes input shortfalls; not three passed fixed-rate baselines, nor isolated server processing latency.'}
(out/'comparison.json').write_text(json.dumps(report,indent=2)+'\n')
svg=['<svg xmlns="http://www.w3.org/2000/svg" width="1000" height="440" viewBox="0 0 1000 440">','<rect width="1000" height="440" fill="white"/>','<style>text{font-family:Arial,sans-serif;fill:#172033}</style>','<text x="45" y="30" font-size="21">Before conversion: observed receive p95</text>','<text x="45" y="53" font-size="13">Input shortfalls retained; red border = measured send target not met. Values in ms.</text>']
colors=['#2563eb','#d97706','#059669']
for t in range(5):
 y=350-t*60;svg += [f'<line x1="80" y1="{y}" x2="970" y2="{y}" stroke="#e2e8f0"/>',f'<text x="66" y="{y+4}" text-anchor="end" font-size="12">{t*800}</text>']
for group,target in enumerate([100,500,1000]):
 center=225+group*300
 for index,number in enumerate([1,2,3]):
  a=next(a for a in rows if a['target']==target and a['round']==number);value=a['receive_p95_ms'];x=center-75+index*54;y=350-value*240/3200;stroke='#b91c1c' if not a['measured_input_and_delivery_met'] else 'none'
  svg += [f'<rect x="{x}" y="{y}" width="37" height="{350-y}" fill="{colors[index]}" stroke="{stroke}" stroke-width="3"/>',f'<text x="{x+18}" y="{y-7}" text-anchor="middle" font-size="12">{value}</text>']
 svg.append(f'<text x="{center}" y="379" text-anchor="middle" font-size="14">{target} connected users</text>')
for i,color in enumerate(colors):
 x=45+i*155;svg += [f'<rect x="{x}" y="409" width="16" height="12" fill="{color}"/>',f'<text x="{x+24}" y="420" font-size="12">Round {i+1}</text>']
svg.append('</svg>');(out/'observed-p95.svg').write_text('\n'.join(svg))
print(json.dumps({k:report[k] for k in ['completed_rounds','fully_passed_rounds','elapsed_minutes','observed_p95_medians_ms','measured_send_shortfall','measured_received_total']},indent=2))
