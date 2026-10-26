#!/usr/bin/env python3
"""Explicitly authorized three-round baseline series with an append-only command log."""
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import shutil
import signal
import subprocess
import sys
import time
from urllib.request import urlopen

ROOT = Path(__file__).resolve().parent
STAMP = datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ')
OUT = ROOT / 'results' / (STAMP + '-before-official-series')
OUT.mkdir(mode=0o700)
(ROOT / '.runtime/latest-before-series').write_text(str(OUT))
STATE = {'series_id': OUT.name, 'started_at': datetime.now(timezone.utc).isoformat(),
         'mode': 'before', 'requested_rounds': 3, 'rounds': [], 'status': 'preparing',
         'authentication': 'Every round invokes real CSRF/refresh renewal via --renew-auth before k6 starts.',
         'failed_rounds_preserved': True, 'condition_changes_during_series': False}
ACTIVE = None


def save():
    temporary = OUT / 'series.tmp'
    temporary.write_text(json.dumps(STATE, indent=2) + '\n')
    temporary.replace(OUT / 'series.json')


def event(record):
    with (OUT / 'commands.jsonl').open('a') as stream:
        stream.write(json.dumps(record) + '\n')


def execute(label, command, round_number=None):
    global ACTIVE
    started = datetime.now(timezone.utc).isoformat()
    event({'event': 'start', 'at': started, 'round': round_number, 'label': label,
           'cwd': str(ROOT), 'argv': command})
    print('COMMAND ' + json.dumps({'round': round_number, 'label': label, 'argv': command}), flush=True)
    transcript = OUT / (label + '.log')
    ACTIVE = subprocess.Popen(command, cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                              text=True, start_new_session=True)
    lines = []
    try:
        with transcript.open('x') as stream:
            for line in ACTIVE.stdout:
                stream.write(line); stream.flush()
                print(line, end='', flush=True)
                lines.append(line)
        code = ACTIVE.wait()
    except KeyboardInterrupt:
        ACTIVE.send_signal(signal.SIGINT)
        ACTIVE.wait(timeout=90)
        raise
    finally:
        ACTIVE = None
    event({'event': 'finish', 'at': datetime.now(timezone.utc).isoformat(), 'started_at': started,
           'round': round_number, 'label': label, 'exit_code': code, 'transcript': str(transcript)})
    return code, lines


def read_command(command):
    result = subprocess.run(command, cwd=ROOT, text=True, capture_output=True, timeout=20)
    if result.returncode:
        raise RuntimeError('Read-only environment check failed: ' + command[0])
    return result.stdout.strip()


def environment(number):
    power = read_command(['pmset', '-g', 'batt'])
    assert 'AC Power' in power, 'AC power disconnected; next round not started'
    docker = json.loads(read_command(['docker', 'info', '--format', '{"cpu":{{.NCPU}},"memory_bytes":{{.MemTotal}}}']))
    assert docker == {'cpu': 10, 'memory_bytes': 8321515520}, 'Docker allocation changed'
    ids = read_command(['docker', 'compose', '--profile', 'redis', 'ps', '-q']).split()
    fmt = '{"name":{{json .Name}},"service":{{json (index .Config.Labels "com.docker.compose.service")}},"state":{{json .State.Status}},"memory":{{.HostConfig.Memory}},"cpu":{{.HostConfig.NanoCpus}},"entrypoint":{{json .Config.Entrypoint}},"image":{{json .Config.Image}}}'
    containers = [json.loads(read_command(['docker', 'inspect', '--format', fmt, cid])) for cid in ids]
    assert all(c['state'] == 'running' for c in containers), 'A test container stopped'
    backend = [c for c in containers if c['service'] == 'backend']
    assert len(backend) == 1 and backend[0]['cpu'] == 2000000000 and backend[0]['memory'] == 1610612736
    grafana = next(c for c in containers if c['service'] == 'grafana')
    assert grafana['memory'] == 536870912
    existing = subprocess.run(['pgrep', '-x', 'k6'], capture_output=True)
    assert existing.returncode == 1, 'An existing k6 process is running or lookup failed'
    assert shutil.disk_usage(ROOT).free > 5 * 1024**3, 'Low host disk space'
    with urlopen('http://127.0.0.1:19090/api/v1/targets', timeout=10) as response:
        targets = json.load(response)['data']['activeTargets']
    required = [t for t in targets if t['labels']['job'] in ['backend', 'local-resources']]
    assert len(required) == 2 and all(t['health'] == 'up' for t in required)
    record = {'at': datetime.now(timezone.utc).isoformat(), 'power': power, 'docker': docker,
              'containers': containers, 'host_disk_free_bytes': shutil.disk_usage(ROOT).free,
              'memory_pressure': read_command(['sysctl', 'kern.memorystatus_vm_pressure_level']),
              'swap': read_command(['sysctl', 'vm.swapusage']), 'prometheus_targets': required}
    (OUT / f'round-{number:02}-environment.json').write_text(json.dumps(record, indent=2) + '\n')


try:
    shutil.copytree(ROOT, OUT / 'frozen-tools', ignore=shutil.ignore_patterns('.runtime', 'results', '__pycache__', '*.pyc', '.env', '.git'))
    archive = ROOT / '.runtime' / (STAMP + '-before-official-archive')
    archive.mkdir(mode=0o700)
    shutil.copyfile(ROOT / '.runtime/app.jar', archive / 'app.jar')
    shutil.copyfile(ROOT / '.runtime/build.json', archive / 'build.json')
    operational = [p for p in ROOT.rglob('*') if p.is_file() and p.suffix in ['.py', '.js', '.mjs', '.yaml', '.json', '.conf', '.sh']
                   and not any(part in ['.runtime', 'results', '__pycache__'] for part in p.relative_to(ROOT).parts)]
    frozen = {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in operational}
    (OUT / 'frozen-source-sha256.json').write_text(json.dumps(frozen, indent=2) + '\n')
    STATE['build'] = json.loads((archive / 'build.json').read_text())
    STATE['archived_jar'] = str(archive / 'app.jar')
    STATE['jar_sha256'] = hashlib.sha256((archive / 'app.jar').read_bytes()).hexdigest()
    assert STATE['jar_sha256'] == STATE['build']['jar_sha256']
    STATE['status'] = 'running'; save()
    for number in [1, 2, 3]:
        assert all(hashlib.sha256((ROOT / path).read_bytes()).hexdigest() == digest for path, digest in frozen.items()), 'Operational source/config changed'
        assert hashlib.sha256((ROOT / '.runtime/app.jar').read_bytes()).hexdigest() == STATE['jar_sha256'], 'Staged JAR changed'
        STATE['current_round'] = number; STATE['current_step'] = 'backup-reset-restart'; save()
        code, lines = execute(f'round-{number:02}-reset', [sys.executable, '-u', 'prepare-baseline.py', '--restore'], number)
        if code:
            raise RuntimeError('Baseline restoration failed; no next load started')
        reset_path = next((line.removeprefix('Evidence: ').strip() for line in lines if line.startswith('Evidence: ')), None)
        STATE['current_step'] = 'health-environment'; save()
        code, _ = execute(f'round-{number:02}-health', [sys.executable, '-u', 'verify.py'], number)
        if code:
            raise RuntimeError('Environment health failed; no next load started')
        environment(number)
        STATE['current_step'] = 'auth-and-official-load'; save()
        code, lines = execute(f'round-{number:02}-measurement', [sys.executable, '-u', 'run-chat.py', 'round', '--mode', 'before', '--round', str(number), '--renew-auth'], number)
        run_path = next((Path(line.removeprefix('Output: ').strip()) for line in lines if line.startswith('Output: ')), None)
        if run_path is None or not (run_path / 'result.json').exists():
            STATE['rounds'].append({'round': number, 'preparation_failed': True, 'command_exit_code': code, 'baseline': reset_path}); save()
            raise RuntimeError('Authentication/preparation/runtime prevented a formal result; evidence retained')
        result = json.loads((run_path / 'result.json').read_text())
        row = {'round': number, 'run_id': result['run_id'], 'directory': str(run_path), 'baseline': reset_path,
               'command_exit_code': code, 'cleanup_clean': result['cleanup_clean'], 'reports_complete': result['reports_complete'],
               'phases': result['phases'], 'timeseries_export': result.get('timeseries_export')}
        STATE['rounds'].append(row); STATE['current_step'] = 'independent-analysis'; save()
        analysis_command = [sys.executable, '-u', 'analyze-chat.py', str(run_path)]
        if code:
            analysis_command.append('--allow-failed')
        analysis_code, _ = execute(f'round-{number:02}-analysis', analysis_command, number)
        row['analysis_exit_code'] = analysis_code; save()
        if not result['cleanup_clean']:
            raise RuntimeError('Active participation remains; automatic next-round reset is not allowed')
        # A performance failure is preserved as a result; repeat the same conditions.
    STATE['status'] = 'completed'
    STATE['finished_at'] = datetime.now(timezone.utc).isoformat()
    STATE['current_step'] = 'finished'; save()
    print('SERIES_COMPLETE ' + str(OUT), flush=True)
except BaseException as error:
    STATE['status'] = 'interrupted' if isinstance(error, KeyboardInterrupt) else 'stopped'
    STATE['error_type'] = type(error).__name__
    STATE['error'] = str(error)
    STATE['finished_at'] = datetime.now(timezone.utc).isoformat(); save()
    print('SERIES_STOPPED ' + json.dumps({'type': type(error).__name__, 'reason': str(error), 'evidence': str(OUT)}), flush=True)
    raise
