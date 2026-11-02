#!/usr/bin/env python3
"""Seed isolated synthetic users; use real queue/login/session/location APIs.

No token signing, auth bypass, production DB connection, or automatic data deletion.
"""
import argparse
import base64
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime, timezone
import http.cookiejar
import json
import os
from pathlib import Path
import re
import secrets
import subprocess
import time
from urllib.error import HTTPError
from urllib.request import Request, build_opener, HTTPCookieProcessor
import uuid
import zipfile

ROOT = Path(__file__).resolve().parent
PRIVATE = ROOT / '.runtime/fixtures'
BASE = 'http://127.0.0.1:18080'
COORD = {'latitude': 37.4979, 'longitude': 127.0276, 'accuracy_meters': 10}
FIRST_ID = 91000000


def private_write(path, data):
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    os.chmod(path, 0o600)
    with os.fdopen(fd, 'w') as stream:
        stream.write(data)


def sql(query):
    cmd = ['docker', 'compose', 'exec', '-T', 'mysql', 'sh', '-c',
           'MYSQL_PWD="$MYSQL_PASSWORD" mysql --default-character-set=utf8mb4 -u "$MYSQL_USER" -D "$MYSQL_DATABASE" --batch --raw --skip-column-names']
    completed = subprocess.run(cmd, cwd=ROOT, input=query, text=True, capture_output=True)
    if completed.returncode:
        # MySQL errors can include SQL values; never print stderr containing the hash.
        raise RuntimeError('Test DB command failed; no credentials or SQL values logged')
    return completed.stdout.strip()


def accounts():
    return [{'user_id': FIRST_ID + i, 'email': f'chatload{i:04}@local.test', 'nickname': f'LT{i:04}'} for i in range(1, 1001)]


def state():
    return json.loads(sql('''SELECT JSON_OBJECT(
      'database',DATABASE(),
      'fixture_users',(SELECT COUNT(*) FROM users WHERE id BETWEEN 91000001 AND 91001000 AND email LIKE 'chatload%@local.test' AND deleted_at IS NULL),
      'active_memberships',(SELECT COUNT(*) FROM chat_room_members WHERE deleted_at IS NULL),
      'active_views',(SELECT COUNT(*) FROM chat_view_sessions WHERE ended_at IS NULL),
      'open_intervals',(SELECT COUNT(*) FROM chat_view_intervals WHERE ends_at IS NULL),
      'active_bans',(SELECT COUNT(*) FROM chat_bans WHERE deleted_at IS NULL AND expires_at>UTC_TIMESTAMP()),
      'messages',(SELECT COUNT(*) FROM chat_room_messages));'''))


def room():
    result = sql("SELECT c.id,r.id,c.capacity FROM chat_rooms c JOIN regions r ON r.id=c.region_id WHERE r.code='11680' AND r.is_active=1 AND c.status='ACTIVE';")
    room_id, region_id, capacity = map(int, result.split('\t'))
    assert capacity == 1000
    return {'room_id': room_id, 'region_id': region_id, 'capacity': capacity, 'region_code': '11680', 'coordinate': COORD}


def hash_password():
    PRIVATE.mkdir(mode=0o700, parents=True, exist_ok=True)
    os.chmod(PRIVATE, 0o700)
    if not (PRIVATE / 'password').exists():
        private_write(PRIVATE / 'password', secrets.token_urlsafe(24))
    with zipfile.ZipFile(ROOT / '.runtime/app.jar') as jar:
        name = next(name for name in jar.namelist() if 'spring-security-crypto-' in name)
        (PRIVATE / 'crypto.jar').write_bytes(jar.read(name))
    subprocess.run(['java', '--class-path', str(PRIVATE / 'crypto.jar'), str(ROOT / 'fixtures/HashPassword.java'), str(PRIVATE)], check=True, capture_output=True)
    os.chmod(PRIVATE / 'password.bcrypt', 0o600)
    hashed = (PRIVATE / 'password.bcrypt').read_text().strip()
    assert re.fullmatch(r'\$2[aby]\$08\$[A-Za-z0-9./]{53}', hashed)
    return hashed


def seed():
    assert sql('SELECT DATABASE();') == 'chat_load_local'
    hashed = hash_password()
    before = state()
    assert before['active_memberships'] == 0 and before['active_views'] == 0 and before['open_intervals'] == 0
    users = accounts()
    existing = sql("SELECT id,CONCAT(email,'|',nickname,'|',IF(deleted_at IS NULL,'active','deleted')) FROM users WHERE id BETWEEN 91000001 AND 91001000;")
    expected = {u['user_id']: f"{u['email']}|{u['nickname']}|active" for u in users}
    for line in existing.splitlines():
        identifier, actual = line.split('\t', 1)
        if expected[int(identifier)] != actual:
            raise RuntimeError('Fixture ID conflict; existing data preserved')
    values = ','.join(f"({u['user_id']},'{u['email']}','{hashed}','{u['nickname']}','USER')" for u in users)
    # Re-running inserts only absent fixtures; it never overwrites existing user rows.
    statement = f'''START TRANSACTION;
    INSERT IGNORE INTO users(id,email,password_hash,nickname,role) VALUES {values};
    INSERT IGNORE INTO user_settings(user_id)
      SELECT id FROM users WHERE id BETWEEN 91000001 AND 91001000 AND email LIKE 'chatload%@local.test';
    INSERT INTO terms_agreements(user_id,terms_id,created_at)
      SELECT u.id,t.id,UTC_TIMESTAMP(6) FROM users u JOIN terms t ON t.effective_at<=UTC_TIMESTAMP(6)
      WHERE u.id BETWEEN 91000001 AND 91001000 AND u.email LIKE 'chatload%@local.test'
      AND NOT EXISTS(SELECT 1 FROM terms newer WHERE newer.type=t.type AND newer.effective_at<=UTC_TIMESTAMP(6) AND newer.effective_at>t.effective_at)
      AND NOT EXISTS(SELECT 1 FROM terms_agreements a WHERE a.user_id=u.id AND a.terms_id=t.id AND a.deleted_at IS NULL);
    COMMIT;'''
    sql(statement)
    assert state()['fixture_users'] == 1000
    private_write(PRIVATE / 'accounts.json', json.dumps({'users': users, 'room': room()}, indent=2))
    print('PASS: 1000 distinct synthetic users; settings/term fixtures; capacity=1000; no pre-created memberships.')


class Client:
    def __init__(self):
        self.jar = http.cookiejar.CookieJar()
        self.opener = build_opener(HTTPCookieProcessor(self.jar))

    def request(self, method, path, payload=None, token=None, headers=None):
        values = {'Origin': BASE, 'User-Agent': 'ChatLoadFixture/1.0'}
        if payload is not None:
            values['Content-Type'] = 'application/json'
        if token:
            values['Authorization'] = 'Bearer ' + token
        values.update(headers or {})
        request = Request(BASE + path, data=json.dumps(payload).encode() if payload is not None else None, headers=values, method=method)
        try:
            with self.opener.open(request, timeout=20) as response:
                data = response.read()
                return json.loads(data) if data else None
        except HTTPError as error:
            # Print only an allowlisted symbolic error, never response payloads/tokens.
            code = None
            try:
                message = json.loads(error.read()).get('message')
                if message in ['AUTH_STATE_UNAVAILABLE', 'AUTH_STATE_BUSY', 'DEVICE_SESSION_LIMIT',
                               'LOGIN_RECOVERY_UNAVAILABLE', 'INVALID_CREDENTIALS', 'TOKEN_ISSUE_FAILED']:
                    code = message
            except (ValueError, AttributeError):
                pass
            raise RuntimeError(f'{method} {path} HTTP {error.code}' + (f' code={code}' if code else '')) from None


def decode_claims(token):
    # Diagnostic correlation only. Server APIs perform the actual cryptographic validation.
    segment = token.split('.')[1]
    return json.loads(base64.urlsafe_b64decode(segment + '=' * (-len(segment) % 4)))


def login_one(user):
    client = Client()
    ticket = client.request('POST', '/api/v1/auth/login-queue/tickets', {}, headers={'Idempotency-Key': str(uuid.uuid4())})['data']
    deadline = time.monotonic() + 90
    while ticket['status'] != 'READY':
        if time.monotonic() > deadline:
            raise RuntimeError('Login queue readiness timed out')
        time.sleep(max(5, ticket.get('nextPollSeconds', 6)))
        ticket = client.request('GET', '/api/v1/auth/login-queue/tickets/current')['data']
    result = client.request('POST', '/api/v1/auth/login', {'email': user['email'], 'password': (PRIVATE / 'password').read_text()}, headers={'X-Login-Operation-Id': str(uuid.uuid4())})['data']
    token = result['access_token']
    claims = decode_claims(token)
    assert int(claims['sub']) == user['user_id']
    client.request('GET', '/api/v1/users/me', token=token)
    sessions = client.request('GET', '/api/v1/auth/sessions', token=token)['data']
    assert any(s['sid'] == claims['sid'] and s['current'] for s in sessions)
    location = client.request('POST', '/api/v1/locations/resolve', COORD, token=token)['data']
    # Validate the server-issued location token's session/region claims, without signing a token locally.
    location_claims = decode_claims(location['location_resolution_token'][4:])
    assert location_claims['sid'] == claims['sid'] and location_claims['sigungu_code'] == '11680'
    saved = {**user, 'access_token': token, 'sid': claims['sid'], 'expires_at': claims['exp'], 'room_id': room_cache['room_id'], 'region_id': room_cache['region_id'], 'coordinate': COORD}
    cookies = [{'name': c.name, 'value': c.value, 'domain': c.domain, 'path': c.path, 'secure': c.secure, 'expires': c.expires} for c in client.jar]
    return saved, cookies


def login(limit, workers):
    global room_cache
    room_cache = room()
    assert state()['fixture_users'] == 1000
    stamp = datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ') + '-' + uuid.uuid4().hex[:6]
    output = PRIVATE / ('sessions-' + stamp)
    output.mkdir(mode=0o700)
    records, failures = [], []
    private_write(output / 'progress.jsonl', '')
    with ThreadPoolExecutor(max_workers=workers) as executor:
        jobs = {executor.submit(login_one, user): user for user in accounts()[:limit]}
        for future in as_completed(jobs):
            user = jobs[future]
            try:
                saved, cookies = future.result()
                records.append(saved)
                private_write(output / f"cookies-{user['user_id']}.json", json.dumps(cookies))
                with (output / 'progress.jsonl').open('a') as stream:
                    stream.write(json.dumps(saved) + '\n')
            except Exception as error:
                failures.append({'user_id': user['user_id'], 'error': str(error)})
            if (len(records) + len(failures)) % 100 == 0 or limit <= 2:
                print(f'Completed {len(records)+len(failures)}/{limit}: success={len(records)}, failures={len(failures)}', flush=True)
    records.sort(key=lambda row: row['user_id'])
    private_write(output / 'users.json', json.dumps(records, indent=2))
    summary = {'at': datetime.now(timezone.utc).isoformat(), 'requested': limit, 'success': len(records), 'failures': failures, 'distinct_user_ids': len({u['user_id'] for u in records}), 'distinct_sids': len({u['sid'] for u in records}), 'earliest_access_expiry': min((u['expires_at'] for u in records), default=None), 'room': room_cache, 'state': state(), 'location_token_lifetime_seconds': 300, 'location_tokens_cached': False, 'signup_api_tested': False}
    (ROOT / 'results' / ('fixtures-' + stamp + '.json')).write_text(json.dumps(summary, indent=2) + '\n')
    if failures:
        print('Failed account IDs and HTTP stages saved to non-secret results; successful tokens retained privately.')
        raise SystemExit(1)
    assert summary['distinct_user_ids'] == limit and summary['distinct_sids'] == limit
    if limit == 1000:
        private_write(PRIVATE / 'latest-sessions-path', str(output / 'users.json'))
    print(f'PASS: {limit} real queue/login/session/location validations. Private session file: {output / "users.json"}')
    print('Location tokens last 5 minutes: obtain a fresh token at each actual room entry; access tokens last 1 hour.')
    return output / 'users.json'


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('command', choices=['seed', 'login', 'state'])
    parser.add_argument('--limit', type=int, default=1000)
    parser.add_argument('--workers', type=int, default=32)
    args = parser.parse_args()
    assert 1 <= args.limit <= 1000 and 1 <= args.workers <= 32
    if args.command == 'seed':
        seed()
    elif args.command == 'state':
        print(json.dumps(state(), indent=2))
    else:
        login(args.limit, args.workers)
