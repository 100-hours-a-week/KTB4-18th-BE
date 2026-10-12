"""Capture reproducibility evidence without writing secrets or fixture JWTs."""
import datetime
import hashlib
import json
import platform
import subprocess
import sys
from pathlib import Path
from prepare import PERF, ROOT, env_values, compose


def output(*command):
    result = subprocess.run(command, cwd=ROOT, text=True, capture_output=True)
    return result.stdout.strip() if result.returncode == 0 else 'unavailable'


def main():
    destination = Path(sys.argv[1]).resolve()
    destination.mkdir(parents=True, exist_ok=True)
    env = env_values()
    source_paths = sorted(p for directory in ('src', 'performance') for p in (ROOT / directory).rglob('*')
                          if p.is_file() and not any(part in {'generated', 'results', '__pycache__', '.env'}
                                                     for part in p.relative_to(ROOT).parts))
    source_paths += [ROOT / 'build.gradle', ROOT / 'gradle/wrapper/gradle-wrapper.properties']
    files = {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in source_paths}
    source_hash = hashlib.sha256(json.dumps(files, sort_keys=True).encode()).hexdigest()
    config = json.loads(compose('config', '--format', 'json', capture_output=True).stdout)
    services = {name: {key: service[key] for key in ('image', 'cpus', 'mem_limit', 'command') if key in service}
                for name, service in config['services'].items()}
    containers = compose('ps', '-q', capture_output=True).stdout.decode().split()
    images = []
    for container in containers:
        # Inspect only image identifiers. Never dump Config.Env.
        image_id = output('docker', 'inspect', '--format', '{{.Image}}', container)
        images.append({'container': container, 'image_id': image_id,
                       'repo_digests': output('docker', 'image', 'inspect', '--format', '{{json .RepoDigests}}', image_id)})
    manifest = {'captured_at': datetime.datetime.now(datetime.timezone.utc).isoformat(),
                'git_commit': output('git', 'rev-parse', 'HEAD'), 'git_branch': output('git', 'branch', '--show-current'),
                'git_status': output('git', 'status', '--short'), 'source_hash': source_hash, 'files': files,
                'jar_sha256': {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in (ROOT / 'build/libs').glob('*-SNAPSHOT.jar')},
                'host': {'platform': platform.platform(), 'architecture': platform.machine(),
                         'memory_bytes': output('sysctl', '-n', 'hw.memsize'),
                         'logical_cpus': output('sysctl', '-n', 'hw.logicalcpu')},
                'k6_version': output('k6', 'version'), 'services': services, 'images': images,
                'mock': {key: value for key, value in env.items() if key.startswith('MOCK_')},
                'fixtures': {'accounts': 250, 'per_room': 25,
                             'audio_sha256': {p.name: hashlib.sha256(p.read_bytes()).hexdigest()
                                              for p in (PERF / 'generated').glob('audio-*.mp4')}},
                'scope': 'Mac local backend benchmark with synthetic AI and map responses'}
    (destination / 'manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2))
    print('Saved reproducibility manifest.')


if __name__ == '__main__':
    main()
