"""Select a predeclared anomaly replacement; never discard valid SLO failures."""
import hashlib,json,sys
from pathlib import Path
original,replacement=map(lambda x:Path(x).resolve(),sys.argv[1:3])
def load(batch):
 steps=[json.loads(l) for l in (batch/'steps.jsonl').read_text().splitlines()]
 assert steps[-1]['event']=='batch-complete'
 return [s for s in steps if s['event']=='run-recorded']
old,new=load(original),load(replacement)
assert len(old)==12 and len(new)==2
excluded=[e for e in old if e['trial']=='text-4rps-r2']
selected=[]
for e in old:
 if e['trial']=='text-4rps-r2':
  candidate=next(s for s in new if s['phase']==e['phase'])
  selected.append({**candidate,'trial':e['trial'],'source_batch':replacement.name,'replaces_run_id':e['run_id']})
 else:selected.append({**e,'source_batch':original.name})
for e in selected:
 run=original.parent/e['run_id'];m=json.loads((run/'summary.json').read_text())['metrics']
 assert m['recommendation_e2e_ms']['values']['min']>=0,(run,'Clock anomaly: cannot accept')
 assert int((run/'exit-code.txt').read_text()) in (0,99),'Tool failure cannot be accepted'
 # Check original positive and negative outcomes, not select by p95 threshold.
 assert m['recommendation_completed']['values']['passes']+m['recommendation_completed']['values']['fails']==m['recommendation_started']['values']['count']
assert any(e['exit_code']==99 and e['phase']=='validation' for e in selected),'Expected valid SLO failure retained'
value={'accepted':selected,'excluded':excluded,'reason':'4rps-r2 wall-clock anomaly (15 negative durations); paired warmup replaced for consistent setup','aborted_wrapper_run':'20261011T134827Z-recommendation-warmup','source_steps_sha256':{b.name:hashlib.sha256((b/'steps.jsonl').read_bytes()).hexdigest() for b in (original,replacement)}}
with (original/'accepted-runs.json').open('x') as f:json.dump(value,f,indent=2)
print('Selected twelve valid runs; valid performance failures retained, originals preserved.')
