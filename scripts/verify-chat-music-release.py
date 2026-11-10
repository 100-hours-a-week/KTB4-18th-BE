#!/usr/bin/env python3
"""Reproduce the local BE/FE release gates; never deploy or activate production flags."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parent.parent

def source_hash(root, backend=False):
    if backend:
        files = [root / n for n in ('build.gradle', 'settings.gradle', 'gradle.properties',
                                   'gradle/wrapper/gradle-wrapper.properties') if (root / n).is_file()]
        for directory in ('src/main/java', 'src/main/resources', 'src/core/java', 'src/core/resources',
                          'src/chat/java', 'src/chat/resources', 'src/test/java'):
            files += [p for p in (root / directory).rglob('*') if p.is_file() and p.name != '.DS_Store']
    else:
        names = subprocess.check_output(['git', 'ls-files', '-z'], cwd=root).split(b'\0')
        names += subprocess.check_output(['git', 'ls-files', '--others', '--exclude-standard', '-z'], cwd=root).split(b'\0')
        files = [root / os.fsdecode(n) for n in set(names) if n and (root / os.fsdecode(n)).is_file()
                 and (os.fsdecode(n).startswith(('src/', 'tests/', 'scripts/'))
                      or os.fsdecode(n) in ('package.json', 'package-lock.json', 'vite.config.ts', 'tsconfig.chatVerification.json'))]
    digest = hashlib.sha256()
    for p in sorted(set(files), key=lambda p: p.relative_to(root).as_posix()):
        digest.update(p.relative_to(root).as_posix().encode() + b'\0' + p.read_bytes() + b'\0')
    return digest.hexdigest()

def test_xml(task, output):
    files = list((ROOT / 'build/test-results' / task).glob('TEST-*.xml'))
    if not files:
        raise RuntimeError(f'No fresh XML for {task}')
    target = output / task
    target.mkdir(exist_ok=True)
    total = dict(tests=0, failures=0, errors=0, skipped=0)
    classes = []
    for p in files:
        shutil.copy2(p, target / p.name)
        item = ET.parse(p).getroot()
        classes.append(item.get('name'))
        for key in total:
            total[key] += int(item.get(key, 0))
    if total['failures'] or total['errors'] or total['tests'] <= total['skipped']:
        raise RuntimeError(f'Invalid/failed {task} results')
    return {**total, 'classes': classes}

def main():
    args = argparse.ArgumentParser(description=__doc__)
    args.add_argument('--frontend-dir', type=Path, default=ROOT.parent / 'meomuneum-frontend')
    args.add_argument('--live-youtube', action='store_true', help='Also probe the real SDK/demo video (network dependent)')
    options = args.parse_args()
    frontend = options.frontend_dir.resolve()
    stamp = datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ')
    output = ROOT / 'build/chat-music-release-verification' / stamp
    output.mkdir(parents=True, exist_ok=False)
    report = {'startedAt': stamp, 'localVerificationPassed': False, 'operationalActivationAllowed': False,
              'steps': [], 'results': {}, 'output': str(output)}
    def save():
        (output / 'summary.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    def run(name, command, cwd):
        step = {'name': name, 'command': command, 'cwd': str(cwd), 'passed': False}
        report['steps'].append(step)
        save()
        print('Verifying ' + name, flush=True)
        start = time.monotonic()
        env = dict(os.environ)
        env['CHAT_BACKEND_DIR'] = str(ROOT)
        with (output / (name + '.log')).open('w') as log:
            completed = subprocess.run(command, cwd=cwd, env=env, stdout=log, stderr=subprocess.STDOUT)
        step.update(exitCode=completed.returncode, seconds=round(time.monotonic()-start, 2), passed=completed.returncode == 0)
        save()
        if completed.returncode:
            raise RuntimeError(f'{name} failed; inspect its log')
    try:
        report['backendHead'] = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()
        report['frontendHead'] = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=frontend, text=True).strip()
        report['backendSource'] = source_hash(ROOT, True)
        report['frontendSource'] = source_hash(frontend)
        for file in (ROOT / 'src/chat/resources').glob('*.yaml'):
            if 'CHAT_MUSIC_QUEUE_ENABLED:false' in file.read_text():
                chat_config = file.read_text()
                break
        else:
            raise RuntimeError('Chat default configuration not found')
        for flag in ('CHAT_MUSIC_QUEUE_ENABLED', 'CHAT_MUSIC_PLAYBACK_ENABLED', 'CHAT_MUSIC_EVENTS_ENABLED'):
            if '${' + flag + ':false}' not in chat_config:
                raise RuntimeError('Music flag default must remain false: ' + flag)
        for flag in ('VITE_CHAT_MUSIC_QUEUE_ENABLED', 'VITE_CHAT_MUSIC_PLAYER_ENABLED'):
            if flag + '=false' not in (frontend / '.env.example').read_text():
                raise RuntimeError('Frontend flag default must remain false: ' + flag)
        gates = json.loads((ROOT / 'api/chat-music-release-gates.json').read_text())['gates']
        if len({g['id'] for g in gates}) != len(gates):
            raise RuntimeError('Duplicate operational gate')
        report['operationalGates'] = gates
        report['pendingOperationalGates'] = [g['id'] for g in gates if g['required'] and
                                           not (g['status'] == 'passed' and g['owner'] and g['evidence'])]
        run('backend-full', [sys.executable, 'scripts/verify-backend.py', 'spotlessCheck', 'checkstyleMain',
                             'checkstyleCore', 'checkstyleChat', 'checkstyleTest', 'test', 'bootJar', 'coreBootJar', 'chatBootJar'], ROOT)
        report['results']['backendFull'] = test_xml('test', output)
        run('server-recovery', ['./gradlew', 'chatMusicRecoveryTest', '--no-daemon'], ROOT)
        report['results']['serverRecovery'] = test_xml('chatMusicRecoveryTest', output)
        run('participation', ['./gradlew', 'chatParticipationTest', '--tests', '*ChatParticipationMySqlTest', '--no-daemon'], ROOT)
        report['results']['participation'] = test_xml('chatParticipationTest', output)
        for name, command in (
            ('frontend-format', ['npm', 'run', 'format:check']),
            ('frontend-lint', ['npm', 'run', 'lint']),
            ('frontend-types', ['npm', 'run', 'verify:chat:types']),
            ('frontend-tests', ['npm', 'test', '--', '--maxWorkers=2', '--reporter=json', '--outputFile=' + str(output / 'frontend-tests.json')]),
            ('frontend-build', ['npm', 'run', 'build']),
            ('real-browser-server', ['npm', 'run', 'verify:chat:music:release', '--', '--worktree']),
            ('contracts', ['node', 'scripts/verifyChatMusicContracts.mjs']),
            ('browser-recovery', ['npm', 'run', 'verify:chat:music:sync:browser']),
        ):
            run(name, command, frontend)
        fe_evidence = frontend / 'node_modules/.tmp/chat-music-release-verification'
        for name in ('browser/result.json', 'browser/wire-examples.json', 'contracts.json', 'backend-worktree.json'):
            shutil.copy2(fe_evidence / name, output / Path(name).name)
        if options.live_youtube:
            run('youtube-live', ['npm', 'run', 'verify:chat:music:player:browser', '--', '--live'], frontend)
            live = json.loads((frontend / 'node_modules/.tmp/chat-music-player-verification/browser/live-youtube.json').read_text())
            report['results']['youtubeLive'] = live
            if not (live.get('sdkLoaded') and live.get('iframeCount') == 1 and live.get('playingObserved')):
                raise RuntimeError('Real YouTube playback not observed; not a passed live-media gate')
        report['jarManifests'] = {}
        for name in ('meomuneum-backend-0.0.1-SNAPSHOT.jar', 'meomuneum-core.jar', 'meomuneum-chat.jar'):
            with zipfile.ZipFile(ROOT / 'build/libs' / name) as jar:
                manifest = jar.read('META-INF/MANIFEST.MF').decode().replace('\r\n ', '').replace('\r\n', '\n')
                values = dict(line.split(': ', 1) for line in manifest.splitlines() if ': ' in line)
            if values.get('Build-Source-SHA256') != report['backendSource']:
                raise RuntimeError('JAR/source identity mismatch')
            report['jarManifests'][name] = {'sourceSha256': values['Build-Source-SHA256'],
                                          'sha256': hashlib.sha256((ROOT / 'build/libs' / name).read_bytes()).hexdigest()}
        if source_hash(ROOT, True) != report['backendSource'] or source_hash(frontend) != report['frontendSource']:
            raise RuntimeError('Sources changed during verification; rerun the candidate')
        report['localVerificationPassed'] = True
        report['operationalActivationAllowed'] = not report['pendingOperationalGates']
    except Exception as error:
        report['failure'] = str(error)
        print(str(error), file=sys.stderr)
    finally:
        save()
        print('Evidence: ' + str(output / 'summary.json'), flush=True)
    return 0 if report['localVerificationPassed'] else 1

if __name__ == '__main__':
    raise SystemExit(main())
