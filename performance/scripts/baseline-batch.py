"""Nine serial trials; backups and step logs stay in private results, never production."""
import datetime
import argparse
import json
import os
from pathlib import Path
import subprocess
from prepare import PERF, ROOT, compose

parser = argparse.ArgumentParser()
parser.add_argument('--resume', type=Path)
args = parser.parse_args()
invocation = datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ')
if args.resume:
    batch = args.resume.resolve()
    assert batch.parent == (PERF / 'results').resolve(), 'Resume must be a local results batch'
    plan = json.loads((batch / 'plan.json').read_text())
else:
    batch = PERF / 'results' / (invocation + '-baseline-batch')
    batch.mkdir(parents=True)
    plan = {'batch_id': batch.name, 'repetitions': 3, 'warmup_seconds': 120, 'measurement_seconds': 300,
        'conditions': ['text-1rps', 'voice-1rps', 'chat-5users'],
        'scope': 'Mac local initial baseline; no optimization or capacity claim'}
    (batch / 'plan.json').write_text(json.dumps(plan, indent=2))
completed = {e['trial'] for e in (json.loads(line) for line in (batch / 'steps.jsonl').read_text().splitlines())
             if e['event'] == 'trial-complete'} if args.resume else set()


def log(event, **values):
    entry = {'at': datetime.datetime.now(datetime.timezone.utc).isoformat(), 'event': event, **values}
    with (batch / 'steps.jsonl').open('a') as file:
        file.write(json.dumps(entry) + '\n')
    print(json.dumps(entry), flush=True)


def command(label, args, env=None):
    log('step-start', step=label)
    with (batch / (label + '-' + invocation + '.txt')).open('w') as file:
        result = subprocess.run(args, cwd=ROOT, env=env, stdout=file, stderr=subprocess.STDOUT)
    log('step-end', step=label, exit_code=result.returncode)
    if result.returncode:
        raise RuntimeError('Step failed; evidence retained: ' + label)


try:
    log('batch-resume' if args.resume else 'batch-start', plan=plan, completed=sorted(completed))
    # Existing data is synthetic, but preserve it before every destructive reset.
    for condition in plan['conditions']:
        for repetition in range(1, 4):
            trial = f'{condition}-r{repetition}'
            if trial in completed:
                continue
            log('trial-start', trial=trial)
            command(trial + '-stop', ['docker', 'compose', '--env-file', str(PERF / '.env'),
                                      '-f', str(PERF / 'compose.yaml'), 'stop', 'backend'])
            try:
                dump = compose('exec', '-T', 'mysql', 'sh', '-c',
                           'set -eu; test "$MYSQL_DATABASE" = meomuneum_perf; '
                           'MYSQL_PWD="$MYSQL_PASSWORD" exec mysqldump --no-tablespaces --skip-comments '
                           '--single-transaction --set-gtid-purged=OFF -u "$MYSQL_USER" "$MYSQL_DATABASE" '
                           'recommendation_sessions recommendation_items chat_room_members chat_room_messages',
                           capture_output=True).stdout
            except subprocess.CalledProcessError as error:
                (batch / (trial + '-backup-error-' + invocation + '.txt')).write_bytes(error.stderr or b'')
                raise
            (batch / (trial + '-before-reset-' + invocation + '.sql')).write_bytes(dump)
            log('db-backup', trial=trial, bytes=len(dump))
            command(trial + '-reset', ['python3', str(PERF / 'scripts/reset-data.py'), '--confirm-disposable-db'])
            command(trial + '-start', ['docker', 'compose', '--env-file', str(PERF / '.env'),
                                       '-f', str(PERF / 'compose.yaml'), 'up', '-d', '--wait', 'backend'])
            command(trial + '-tokens', ['python3', str(PERF / 'scripts/prepare.py'), '--init-only'])
            command(trial + '-preflight', ['python3', str(PERF / 'scripts/preflight.py')])
            env = {**os.environ, 'RATE': '1', 'PER_ROOM': '5', 'ROOMS': '1', 'INTERVAL_MS': '2000',
                   'INPUT_TYPE': 'VOICE' if condition.startswith('voice') else 'TEXT',
                   'DURATION': '5m', 'MESSAGES': '150', 'VUS': '50', 'MAX_VUS': '150',
                   'AUDIO_FILE': str(PERF / 'generated/audio-10s.mp4'), 'TRIAL': trial}
            scenario = 'chat' if condition.startswith('chat') else 'recommendation'
            for phase in ('warmup', 'baseline'):
                before = set((PERF / 'results').glob('*-' + scenario + '-' + phase))
                command(trial + '-' + phase, [str(PERF / 'scripts/run.sh'), scenario, phase], env)
                after = set((PERF / 'results').glob('*-' + scenario + '-' + phase))
                created = sorted(after - before)
                if len(created) != 1:
                    raise RuntimeError('Cannot identify trial evidence')
                log('run-recorded', trial=trial, phase=phase, run_id=created[0].name)
            log('trial-complete', trial=trial)
    log('batch-complete')
except Exception as error:
    log('batch-failed', error=str(error))
    raise
