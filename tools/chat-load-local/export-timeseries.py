#!/usr/bin/env python3
"""Export selected Prometheus time series and dashboard sources for one run."""
import argparse
import gzip
import json
from pathlib import Path
from urllib.parse import urlencode
from urllib.request import urlopen

ROOT = Path(__file__).resolve().parent


def export(output, run_id, start, end):
    groups = {
        'k6': '{__name__=~"k6_chat_.*",run_id=' + json.dumps(run_id) + '}',
        'resources': '{__name__=~"chat_local_.*",run_id=' + json.dumps(run_id) + '}',
        'backend': '{job="backend",__name__=~"up|process_.*|jvm_memory_.*|jvm_threads_.*|hikaricp_.*|chat_.*"}',
    }
    report = {'run_id': run_id, 'start': start, 'end': end, 'step_seconds': 5, 'groups': {}}
    for name, query in groups.items():
        try:
            url = 'http://127.0.0.1:19090/api/v1/query_range?' + urlencode({'query': query, 'start': start, 'end': end, 'step': 5})
            with urlopen(url, timeout=30) as response:
                data = json.load(response)
            if data.get('status') != 'success':
                raise ValueError('Prometheus query failed')
            with gzip.open(output / ('timeseries-' + name + '.json.gz'), 'wt') as stream:
                json.dump(data, stream)
            report['groups'][name] = {'ok': True, 'series': len(data['data']['result'])}
        except Exception as error:
            report['groups'][name] = {'ok': False, 'error': type(error).__name__}
    dashboards = {p.name: json.loads(p.read_text()) for p in (ROOT / 'grafana/dashboards').glob('*.json')}
    (output / 'dashboard-sources.json').write_text(json.dumps(dashboards, indent=2) + '\n')
    (output / 'timeseries-export.json').write_text(json.dumps(report, indent=2) + '\n')
    return report


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('output', type=Path)
    parser.add_argument('--run-id', required=True)
    parser.add_argument('--start', type=float, required=True)
    parser.add_argument('--end', type=float, required=True)
    args = parser.parse_args()
    print(json.dumps(export(args.output.resolve(), args.run_id, args.start, args.end), indent=2))
