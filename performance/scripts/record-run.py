"""Append completion evidence without credentials; original artifacts remain untouched."""
import datetime
import hashlib
import json
import sys
from pathlib import Path

run = Path(sys.argv[1]).resolve()
ledger = run.parent / 'run-ledger.jsonl'
if ledger.exists():
    recorded = [json.loads(line)['run_id'] for line in ledger.read_text().splitlines() if line]
    if run.name in recorded:
        raise SystemExit(0)
summary = json.loads((run / 'summary.json').read_text()) if (run / 'summary.json').exists() else {}
metrics = {key: value for key, value in summary.get('metrics', {}).items()
           if key == 'checks' or key.startswith(('recommendation_', 'speech_', 'chat_', 'dropped_iterations'))}
entry = {
    'recorded_at': datetime.datetime.now(datetime.timezone.utc).isoformat(),
    'run_id': run.name,
    'exit_code': int((run / 'exit-code.txt').read_text()),
    'scenario': json.loads((run / 'scenario.json').read_text()),
    'metrics': metrics,
    'html_report_available': (run / 'report.html').exists(),
    'artifacts_sha256': {path.name: hashlib.sha256(path.read_bytes()).hexdigest()
                         for path in sorted(run.iterdir()) if path.is_file()},
}
with ledger.open('a') as file:
    file.write(json.dumps(entry, ensure_ascii=False) + '\n')
print('Recorded run completion and artifact hashes: ' + run.name)
