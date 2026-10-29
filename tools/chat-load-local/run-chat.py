#!/usr/bin/env python3
"""One explicit smoke or one round; never starts servers or deletes old data."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import signal
import resource
import subprocess
import sys
import threading
import time
import uuid
import importlib.util
import shutil
import gzip
from contextlib import nullcontext
from urllib.request import urlopen

ROOT = Path(__file__).resolve().parent


def helper(filename):
    spec = importlib.util.spec_from_file_location(filename.replace('-', '_'), ROOT / filename)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def resource_samples(child, output, stop, run_id):
    series = helper('resource-series.py')
    metrics = ROOT / '.runtime/metrics'
    metrics.mkdir(exist_ok=True)
    def command(args):
        try:
            result = subprocess.run(args, cwd=ROOT, text=True, capture_output=True, timeout=5)
            return result.stdout.strip() if result.returncode == 0 else None
        except (OSError, subprocess.TimeoutExpired):
            return None
    with output.open('x') as stream:
        while not stop.is_set():
            ids = (command(['docker', 'compose', '--profile', 'redis', 'ps', '-q']) or '').split()
            raw = command(['docker', 'stats', '--no-stream', '--format', '{{json .}}', *ids]) if ids else None
            row = {'at': datetime.now(timezone.utc).isoformat(),
                   'k6_cpu_percent_rss_kib': command(['ps', '-p', str(child.pid), '-o', '%cpu=,rss=']),
                   'docker': [json.loads(line) for line in raw.splitlines()] if raw else [],
                   'mac_vm_stat': command(['vm_stat']),
                   'mac_cpu': command(['top', '-l', '1', '-n', '0']),
                   'mac_swap': command(['sysctl', 'vm.swapusage'])}
            stream.write(json.dumps(row) + '\n')
            stream.flush()
            temporary = metrics / 'resources.tmp'
            temporary.write_text(series.render(row, run_id))
            temporary.chmod(0o644)
            temporary.replace(metrics / 'resources.prom')
            stop.wait(5)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('profile', choices=['smoke', 'round', 'preflight', 'connections'])
    parser.add_argument('--mode', choices=['before', 'after'], default='before')
    parser.add_argument('--round', type=int, choices=[1, 2, 3], default=1)
    parser.add_argument('--prepare-auth', action='store_true', help='Perform real login preparation BEFORE starting k6')
    parser.add_argument('--fixture-file', type=Path)
    parser.add_argument('--renew-auth', action='store_true', help='Use real CSRF/refresh rotation of existing private test sessions')
    parser.add_argument('--latency-output', choices=['histogram', 'points'], default='histogram')
    parser.add_argument('--raw-output', choices=['selected', 'all'], default='selected')
    parser.add_argument('--reconnect-probe', action='store_true', help='Smoke only: close one receiver during stage 2 warmup')
    args = parser.parse_args()
    if args.reconnect_probe and args.profile != 'smoke':
        parser.error('Reconnect injection is restricted to smoke; formal rounds never inject disconnects.')
    if args.prepare_auth and args.renew_auth:
        parser.error('Choose new login or refresh preparation, not both.')
    if args.profile == 'round':
        if args.prepare_auth or args.fixture_file:
            parser.error('Formal rounds require refresh of existing test sessions; prepare initial login separately and omit --fixture-file.')
        args.renew_auth = True
    cfg = {'profile': args.profile, 'mode': args.mode, 'round': args.round,
           'base_url': 'http://127.0.0.1:18080', 'targets': [20, 40] if args.profile == 'smoke' else [100, 500, 1000],
           'rate': 10, 'warmup_seconds': 2 if args.profile == 'smoke' else 60,
           'measure_seconds': 4 if args.profile == 'smoke' else 180,
           'drain_ms': 2000 if args.profile == 'smoke' else 5000,
           'pause_ms': 1000 if args.profile == 'smoke' else 60000,
           'ready_timeout_ms': 15000 if args.profile == 'smoke' else 90000,
           'join_interval_ms': 25, 'sender_count': 20 if args.profile == 'smoke' else 100, 'message_length': 80,
           'cleanup_batch_size': 5, 'cleanup_interval_ms': 100,
           'settle_ms': 0 if args.profile == 'smoke' else 10000,
           'latency_output': args.latency_output,
           'auth_refresh_required': args.profile == 'round',
           'raw_output': 'all' if args.latency_output == 'points' else args.raw_output,
           'reconnect_max': 1, 'reconnect_delay_ms': 250, 'reconnect_probe': args.reconnect_probe}
    # Separate preparation profiles never alter the fixed formal-round conditions.
    if args.profile == 'preflight':
        cfg.update(targets=[10], rate=5, sender_count=10, warmup_seconds=5,
                   measure_seconds=60, pause_ms=0, ready_timeout_ms=30000)
    elif args.profile == 'connections':
        cfg.update(targets=[100, 500, 1000], warmup_seconds=5, measure_seconds=15,
                   pause_ms=0, ready_timeout_ms=90000)
    with urlopen('http://127.0.0.1:18080/api/v1/terms', timeout=10) as response:
        if response.status != 200:
            raise SystemExit('Local environment is not ready.')
    if args.renew_auth:
        fixture = helper('renew-fixtures.py').renew(max(cfg['targets']))
    elif args.prepare_auth:
        import fixtures
        fixture = fixtures.login(max(cfg['targets']), 32)
    elif args.fixture_file:
        fixture = args.fixture_file.resolve()
    else:
        pointer = ROOT / '.runtime/fixtures/latest-sessions-path'
        if not pointer.exists():
            raise SystemExit('No private sessions. Run with --prepare-auth.')
        fixture = Path(pointer.read_text().strip()).resolve()
    if not fixture.is_relative_to(ROOT / '.runtime/fixtures'):
        raise SystemExit('Only private local fixture files are accepted.')
    users = json.loads(fixture.read_text())[:max(cfg['targets'])]
    if len(users) != max(cfg['targets']) or len({u['user_id'] for u in users}) != len(users) or len({u['sid'] for u in users}) != len(users):
        raise SystemExit('Not enough distinct users/sessions.')
    if len({u['room_id'] for u in users}) != 1:
        raise SystemExit('All clients must use the same room.')
    # Conservative preparation+measurement+cleanup budget; do not stretch JWT validity.
    budget = (len(cfg['targets']) * (cfg['warmup_seconds'] + cfg['measure_seconds'] + cfg['drain_ms']/1000 + cfg['ready_timeout_ms']/1000)
              + len(cfg['targets'])*cfg['settle_ms']/1000 + cfg['pause_ms']/1000 + len(users)*cfg['join_interval_ms']/1000 + 120)
    if min(u['expires_at'] for u in users) < time.time() + budget:
        raise SystemExit('Sessions expire too soon. Re-run with --prepare-auth; no load was started.')
    soft_files, hard_files = resource.getrlimit(resource.RLIMIT_NOFILE)
    if soft_files != resource.RLIM_INFINITY and soft_files < len(users) + 128:
        raise SystemExit('File descriptor limit is too low for this many actual sockets; no load was started.')
    import fixtures
    initial = fixtures.state()
    if any(initial[k] for k in ['active_memberships', 'active_views', 'open_intervals', 'active_bans']):
        raise SystemExit('Test room is not clean. No state was deleted; inspect fixtures.py state before retrying.')
    if args.profile == 'round' and initial['messages'] != 0:
        raise SystemExit('Restore the agreed empty-message baseline before a formal round. Existing data was preserved; smoke is still available.')
    if fixtures.room()['capacity'] != 1000:
        raise SystemExit('Expected room capacity 1000.')
    stamp = datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ')
    run_id = f'{stamp}-{args.mode}-{args.round:02}-{args.profile}-{uuid.uuid4().hex[:6]}'
    output = ROOT / 'results' / run_id
    output.mkdir(mode=0o700)
    config_path = output / 'config.json'
    config_path.write_text(json.dumps(cfg, indent=2) + '\n')
    sources = [*ROOT.glob('k6/chat-*'), *ROOT.glob('resource-*.py'), ROOT / 'export-timeseries.py',
               *ROOT.glob('grafana/dashboards/*.json'), ROOT / 'prometheus.yaml',
               ROOT / 'run-chat.py', ROOT / 'fixtures.py', ROOT / 'renew-fixtures.py', ROOT / 'compose.yaml', ROOT / 'application.yaml']
    manifest = {'run_id': run_id, 'at': datetime.now(timezone.utc).isoformat(), 'config': cfg, 'initial_db': initial,
                'fixture_users': len(users), 'earliest_access_expiry': min(u['expires_at'] for u in users),
                'build': json.loads((ROOT / '.runtime/build.json').read_text()),
                'source_sha256': {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in sources if p.is_file()},
                'k6_version': subprocess.check_output(['k6', 'version'], text=True).strip(),
                'file_descriptor_limits': {'soft': soft_files, 'hard': hard_files},
                'load_generator': '1 k6 VU owning multiple real asynchronous WebSocket connections',
                'counting': 'send-time ready connection generations; unstable connection intervals are flagged',
                'raw_metric_source': 'script batches of custom counters/connection gauges; full k6 summary and Prometheus retained' if cfg['raw_output'] == 'selected' else 'k6 JSON output (all metric samples)',
                'data_reset': 'none; formal rounds require restored empty-message baseline; fixture history consistency must be checked separately'}
    (output / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
    for source in sources:
        if source.is_file():
            snapshot = output / 'source-snapshot' / source.relative_to(ROOT)
            snapshot.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source, snapshot)
    environment = {k: v for k, v in os.environ.items() if not k.startswith('K6_') and k not in ['FIXTURE_FILE', 'CHAT_CONFIG', 'CHAT_SUMMARY']}
    environment.update({'FIXTURE_FILE': str(fixture), 'CHAT_CONFIG': str(config_path), 'CHAT_SUMMARY': str(output / 'summary.json'),
                        'K6_PROMETHEUS_RW_SERVER_URL': 'http://127.0.0.1:19090/api/v1/write',
                        'K6_PROMETHEUS_RW_TREND_STATS': 'p(95),p(99),avg,max',
                        'K6_PROMETHEUS_RW_STALE_MARKERS': 'true'})
    command = ['k6', 'run', '--quiet', '--log-format', 'raw', '--log-output', 'stdout',
               '-o', 'experimental-prometheus-rw',
               '--tag', 'run_id=' + run_id, 'k6/chat-load.js']
    if cfg['raw_output'] == 'all':
        command += ['-o', 'json=' + str(output / 'raw.json.gz')]
    (output / 'k6-command.json').write_text(json.dumps({
        'cwd': str(ROOT), 'argv': command,
        'environment': {key: environment[key] for key in ['FIXTURE_FILE', 'CHAT_CONFIG', 'CHAT_SUMMARY',
            'K6_PROMETHEUS_RW_SERVER_URL', 'K6_PROMETHEUS_RW_TREND_STATS', 'K6_PROMETHEUS_RW_STALE_MARKERS']},
        'authentication_summary': str(ROOT / 'results' / ('fixtures-' + fixture.parent.name.removeprefix('sessions-') + '.json')),
        'notes': 'Contains private fixture PATH only; no token, cookie or password values.'
    }, indent=2) + '\n')
    print('Output:', output, flush=True)
    print('Profile:', args.profile, 'targets:', cfg['targets'], 'room-wide rate:', cfg['rate'], 'messages/s', flush=True)
    started_at = time.time()
    child = subprocess.Popen(command, cwd=ROOT, env=environment, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    stop = threading.Event()
    sampler = threading.Thread(target=resource_samples, args=(child, output / 'resources.jsonl', stop, run_id), daemon=True)
    sampler.start()
    phases, histograms = [], []
    try:
        raw_context = gzip.open(output / 'raw.json.gz', 'xt') if cfg['raw_output'] == 'selected' else nullcontext(None)
        with (output / 'console.log').open('x') as log, raw_context as raw:
            for line in child.stdout:
                log.write(line); log.flush()
                if not line.startswith(('CHAT_LATENCY_HISTOGRAM ', 'CHAT_RAW_BATCH ')):
                    print(line, end='', flush=True)
                if line.startswith('CHAT_PHASE '):
                    phases.append(json.loads(line[len('CHAT_PHASE '):]))
                elif line.startswith('CHAT_LATENCY_HISTOGRAM '):
                    histograms.append(json.loads(line[len('CHAT_LATENCY_HISTOGRAM '):]))
                elif line.startswith('CHAT_RAW_BATCH ') and raw is not None:
                    batch = json.loads(line[len('CHAT_RAW_BATCH '):])
                    for point in batch['points']:
                        raw.write(json.dumps({'metric': point['metric'], 'type': 'Point',
                            'data': {'time': batch['time'], 'value': point['value'], 'tags': point['tags']}}) + '\n')
    except KeyboardInterrupt:
        child.send_signal(signal.SIGINT)
    finally:
        code = child.wait()
        if cfg['latency_output'] == 'histogram':
            (output / 'latency-histograms.json').write_text(json.dumps(histograms) + '\n')
        stop.set(); sampler.join(timeout=12)
        try:
            final_state = fixtures.state()
        except Exception:
            final_state = {'verification_failed': True}
        cleanup_clean = not any(final_state.get(key, 1) for key in ['active_memberships', 'active_views', 'open_intervals'])
        reports_complete = [r['target'] for r in phases if r['phase'] == 'measure'] == cfg['targets']
        result = {'reports_complete': reports_complete, 'run_id': run_id, 'k6_exit_code': code, 'phases': phases, 'final_db': final_state,
                  'cleanup_clean': cleanup_clean, 'formal_baseline': args.profile == 'round'}
        (output / 'result.json').write_text(json.dumps(result, indent=2) + '\n')
        ended_at = time.time()
        export_report = helper('export-timeseries.py').export(output, run_id, started_at, ended_at)
        result['run_window'] = {'start': started_at, 'end': ended_at}
        result['timeseries_export'] = export_report['groups']
        (output / 'result.json').write_text(json.dumps(result, indent=2) + '\n')
        print('Time series export:', export_report['groups'], flush=True)
        print('k6 exit:', code, 'cleanup clean:', cleanup_clean, flush=True)
    raise SystemExit(code if code else (0 if cleanup_clean and reports_complete else 1))


if __name__ == '__main__':
    main()
