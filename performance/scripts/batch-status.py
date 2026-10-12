"""Read-only live progress; partial counts are not final performance results."""
import json
from pathlib import Path
from collections import Counter

results = Path(__file__).resolve().parents[1] / 'results'
batch = sorted(results.glob('*-baseline-batch'))[-1]
steps = [json.loads(line) for line in (batch / 'steps.jsonl').read_text().splitlines()]
print(batch.name, steps[-1])
print('Completed measurement trials:', sum(e['event'] == 'trial-complete' for e in steps), '/ 9')
run = sorted(p for p in results.iterdir() if p.is_dir() and (p / 'raw.jsonl').exists())[-1]
counts = Counter()
for line in (run / 'raw.jsonl').open():
    try:
        point = json.loads(line)
    except ValueError:
        continue  # The last line may still be writing.
    name = point.get('metric')
    if point.get('type') == 'Point' and name in ('recommendation_completed', 'chat_sent', 'chat_delivered', 'chat_rejected'):
        counts[name] += point['data']['value']
        if name == 'recommendation_completed' and not point['data']['value']:
            counts['recommendation_failed'] += 1
print(run.name, dict(counts))
