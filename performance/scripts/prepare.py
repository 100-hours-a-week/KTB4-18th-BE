"""Seed only the named Compose DB and create local-only JWT/audio fixtures."""
import argparse
import base64
import hashlib
import hmac
import json
import os
from pathlib import Path
import subprocess
import time

PERF = Path(__file__).resolve().parents[1]
ROOT = PERF.parent
GENERATED = PERF / 'generated'


def env_values():
    path = PERF / '.env'
    if not path.exists():
        path.write_text((PERF / '.env.example').read_text())
        path.chmod(0o600)
    return dict(line.split('=', 1) for line in path.read_text().splitlines()
                if line and not line.startswith('#') and '=' in line)


def compose(*args, **kwargs):
    return subprocess.run(['docker', 'compose', '--env-file', str(PERF / '.env'),
                           '-f', str(PERF / 'compose.yaml'), *args], check=True, **kwargs)


def token(user_id, secret, issued_at):
    def encode(value):
        return base64.urlsafe_b64encode(json.dumps(value, separators=(',', ':')).encode()).rstrip(b'=').decode()
    unsigned = encode({'alg': 'HS256', 'typ': 'JWT'}) + '.' + encode({
        'iss': 'project-api', 'aud': ['project-api'], 'sub': str(user_id),
        'iat': issued_at, 'exp': issued_at + 3600, 'typ': 'access', 'roles': ['USER']})
    signed = hmac.new(secret.encode(), unsigned.encode(), hashlib.sha256).digest()
    return unsigned + '.' + base64.urlsafe_b64encode(signed).rstrip(b'=').decode()


def accounts(secret):
    now = int(time.time())
    return [{'user_id': 8000000 + index + 1,
             'access_token': token(8000000 + index + 1, secret, now), 'expires_at': now + 3600,
             'latitude': 37.55, 'longitude': round(126.95 + (index // 25) * .001, 3)}
            for index in range(250)]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--init-only', action='store_true', help='Create .env and fixture JWTs without touching Docker/DB.')
    args = parser.parse_args()
    env = env_values()
    GENERATED.mkdir(exist_ok=True)
    GENERATED.chmod(0o700)
    data = accounts(env['AUTH_JWT_SECRET'])
    if not args.init_only:
        # Refuse to seed before Flyway and profile validation have succeeded.
        response = compose('exec', '-T', 'backend', 'curl', '-fsS', 'http://localhost:9091/actuator/health',
                           capture_output=True).stdout
        if json.loads(response)['status'] != 'UP':
            raise SystemExit('Backend is not healthy; fixture seed refused.')
        rows = ',\n'.join(f"({a['user_id']},'perf{index+1:04d}@example.test',"
                           f"'unused-load-fixture-password','perf{index+1:04d}','USER')"
                           for index, a in enumerate(data))
        sql = "INSERT INTO users (id,email,password_hash,nickname,role) VALUES\n" + rows + ";\n"
        existing = compose('exec', '-T', 'mysql', 'sh', '-c',
                           'MYSQL_PWD="$MYSQL_PASSWORD" exec mysql -N -u "$MYSQL_USER" "$MYSQL_DATABASE"',
                           input=b'SELECT COUNT(*) FROM users WHERE id BETWEEN 8000001 AND 8000250;\n',
                           capture_output=True).stdout.strip()
        if existing == b'0':
            compose('exec', '-T', 'mysql', 'sh', '-c',
                    'MYSQL_PWD="$MYSQL_PASSWORD" exec mysql -u "$MYSQL_USER" "$MYSQL_DATABASE"', input=sql.encode())
        elif existing != b'250':
            raise SystemExit('Partial fixture dataset found; recreate the dedicated DB before preparing.')
        music_rows = ','.join(f"('ITUNES','{900000000+i}','Fixture Track {i}','Fixture Artist {i}')" for i in range(5))
        compose('exec', '-T', 'mysql', 'sh', '-c',
                'MYSQL_PWD="$MYSQL_PASSWORD" exec mysql -u "$MYSQL_USER" "$MYSQL_DATABASE"',
                input=("INSERT INTO music (provider,external_music_id,title,artist_name) VALUES " + music_rows +
                       " ON DUPLICATE KEY UPDATE title=VALUES(title),artist_name=VALUES(artist_name);\n").encode())
        for seconds in (10, 30, 60):
            # A seekable output gives mvhd a real duration; fragmented empty_moov
            # on stdout produces duration=0 and fails the service's input validator.
            audio = compose('exec', '-T', 'backend', 'sh', '-c',
                            'set -eu; fixture=$(mktemp /tmp/perf-audio-XXXXXX.mp4); '
                            'trap \'rm -f "$fixture"\' EXIT; '
                            'ffmpeg -y -hide_banner -loglevel error -f lavfi '
                            '-i sine=frequency=440:sample_rate=16000 -t "$1" '
                            '-c:a aac -b:a 32k -movflags +faststart "$fixture"; cat "$fixture"',
                            'fixture', str(seconds - 0.2), capture_output=True).stdout
            offset = audio.find(b'mvhd')
            if offset < 0 or audio[offset + 4] != 0:
                raise SystemExit('Fixture mvhd is missing or unsupported.')
            scale = int.from_bytes(audio[offset + 16:offset + 20], 'big')
            ticks = int.from_bytes(audio[offset + 20:offset + 24], 'big')
            if not scale or not 0 < ticks / scale <= 60:
                raise SystemExit('Fixture duration must be positive and at most 60 seconds.')
            path = GENERATED / f'audio-{seconds}s.mp4'
            if path.exists() and path.read_bytes() != audio:
                history = GENERATED / 'audio-history'
                history.mkdir(exist_ok=True)
                previous = path.read_bytes()
                (history / (hashlib.sha256(previous).hexdigest() + '.mp4')).write_bytes(previous)
            path.write_bytes(audio)
    path = GENERATED / 'accounts.json'
    path.write_text(json.dumps(data, indent=2))
    path.chmod(0o600)
    print('Prepared 250 local fixture identities.' + (' Audio/DB preparation deferred.' if args.init_only else ' DB and audio fixtures ready.'))


if __name__ == '__main__':
    main()
