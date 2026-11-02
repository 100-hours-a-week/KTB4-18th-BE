#!/usr/bin/env python3
"""Independently verify phase counts/p95 from retained k6 raw points."""
import argparse
from collections import defaultdict
import gzip
import hashlib
import json
import math
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument('directory', type=Path)
parser.add_argument('--allow-failed', action='store_true', help='Verify raw evidence of a failed run without declaring that run passed')
args = parser.parse_args()
root = args.directory.resolve()
result = json.loads((root / 'result.json').read_text())
summary = json.loads((root / 'summary.json').read_text())
latencies = defaultdict(list)
counters = defaultdict(float)
config = json.loads((root / 'config.json').read_text())
histograms = {}
if config.get('latency_output') == 'histogram':
    for item in json.loads((root / 'latency-histograms.json').read_text()):
        key = (str(item['target']), item['phase'])
        assert key not in histograms and item['unit'] == 'ms'
        pairs = item['entries']
        assert all(isinstance(value, int) and value >= 0 and isinstance(count, int) and count > 0 for value, count in pairs)
        assert len({p[0] for p in pairs}) == len(pairs)
        histograms[key] = sorted(pairs)
with gzip.open(root / 'raw.json.gz', 'rt') as stream:
    for line in stream:
        point = json.loads(line)
        if point.get('type') != 'Point':
            continue
        data = point['data']
        labels = data.get('tags', {})
        key = (str(labels.get('target')), labels.get('phase'))
        if point['metric'] == 'chat_receive_ms':
            latencies[key].append(data['value'])
        if point['metric'] in ['chat_sent', 'chat_received', 'chat_ack']:
            counters[(point['metric'], *key)] += data['value']
checks = []
for name in ['chat_sent', 'chat_received', 'chat_ack']:
    observed = sum(value for (metric, target, phase), value in counters.items() if metric == name)
    assert observed == summary['metrics'][name]['values']['count'], ('raw vs k6 summary', name)
for row in result['phases']:
    key = (str(row['target']), row['phase'])
    assert counters[('chat_sent', *key)] == row['attempted'], ('send count', key)
    assert counters[('chat_received', *key)] == row['received'], ('receive count', key)
    assert counters[('chat_ack', *key)] == row['ack'], ('ACK count', key)
    if row['phase'] == 'measure':
        if config.get('latency_output') == 'histogram':
            pairs = histograms[key]
            count = sum(pair[1] for pair in pairs)
            assert count == row['received'], ('histogram sample count', key)
            rank = math.ceil(0.95 * count)
            cumulative, p95 = 0, None
            for value, frequency in pairs:
                cumulative += frequency
                if cumulative >= rank:
                    p95 = value
                    break
        else:
            samples = sorted(latencies[key])
            assert len(samples) == row['received'], ('latency sample count', key)
            p95 = samples[math.ceil(0.95*len(samples))-1] if samples else None
        assert p95 == row['receive_p95_ms'], ('p95', key, p95)
        checks.append({'target': row['target'], 'received': row['received'], 'raw_nearest_rank_p95_ms': p95})
run_passed = result['k6_exit_code'] == 0 and result['cleanup_clean']
if not args.allow_failed:
    assert run_passed
assert checks, 'No measured phases found'
failed_thresholds = []
for name, metric in summary['metrics'].items():
    if not all(t['ok'] for t in metric.get('thresholds', {}).values()):
        failed_thresholds.append(name)
        if not args.allow_failed:
            raise AssertionError(('threshold', name))
archives = ['result.json', 'summary.json', 'raw.json.gz', 'manifest.json', 'resources.jsonl',
            'latency-histograms.json',
            'timeseries-export.json', 'dashboard-sources.json',
            'timeseries-k6.json.gz', 'timeseries-resources.json.gz', 'timeseries-backend.json.gz']
report = {'raw_counts_and_p95_match': True, 'measured_phases': checks,
          'run_passed': run_passed and not failed_thresholds, 'failed_thresholds': failed_thresholds,
          'timeseries_export': result.get('timeseries_export', 'not collected in this older run'),
          'sha256': {name: hashlib.sha256((root/name).read_bytes()).hexdigest() for name in archives if (root/name).exists()}}
(root / 'verification.json').write_text(json.dumps(report, indent=2)+'\n')
print('Raw evidence verified; run passed:', report['run_passed'], 'measured phases:', checks)
