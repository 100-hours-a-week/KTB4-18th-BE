"""Read-only progress; partial values must not be presented as final results."""
import datetime as dt, json, re, sys
def stamp(value):
    normalized=re.sub(r"\.(\d+)(?=[+-]|Z)",lambda m:"."+m.group(1).ljust(6,"0")[:6],value.replace("Z","+00:00"))
    return dt.datetime.fromisoformat(normalized)
from pathlib import Path
results=Path(__file__).resolve().parents[1]/'results'
batch=sorted(results.glob('*-validation-replacement-batch' if len(sys.argv)>1 and sys.argv[1]=='replacement' else '*-validation-batch'))[-1]
plan=json.loads((batch/'plan.json').read_text())
steps=[json.loads(s) for s in (batch/'steps.jsonl').read_text().splitlines()]
completed=[s for s in steps if s['event']=='trial-complete']
print(json.dumps({'batch':batch.name,'completed_trials':len(completed),'total_trials':plan['repetitions']*len(plan['conditions']),'last_step':steps[-1]}))
start=next((s for s in reversed(steps) if s['event']=='step-start'),None)
if start and start['step'].endswith(('warmup','validation')):
 phase=start['step'].rsplit('-',1)[-1]
 runs=sorted(results.glob('*-recommendation-'+phase))
 if runs:
  run=runs[-1];pts=[]
  if (run/'raw.jsonl').exists():
   for line in (run/'raw.jsonl').open():
    try:p=json.loads(line)
    except json.JSONDecodeError:continue
    if p.get('type')=='Point':pts.append(p)
   counts={k:sum(p['data']['value'] for p in pts if p['metric']==k) for k in ['recommendation_started','recommendation_completed','dropped_iterations']}
   failed=sum(p['data']['value']==0 for p in pts if p['metric']=='recommendation_completed')
   durations=sorted(p['data']['value'] for p in pts if p['metric']=='recommendation_e2e_ms')
   counts['negative_duration_samples']=sum(v<0 for v in durations)
   if durations:
    position=(len(durations)-1)*.95;lower=int(position);upper=min(lower+1,len(durations)-1)
    counts['partial_p95_ms']=durations[lower]+(durations[upper]-durations[lower])*(position-lower)
   counts.update({'failed':failed,'run':run.name})
   if pts:counts['elapsed_raw_seconds']=(stamp(pts[-1]['data']['time'])-stamp(pts[0]['data']['time'])).total_seconds()
   print(json.dumps(counts))
