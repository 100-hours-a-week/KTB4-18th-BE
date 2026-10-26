#!/usr/bin/env python3
"""Renew existing private test sessions through real CSRF/refresh/session APIs."""
import argparse
from concurrent.futures import ThreadPoolExecutor, as_completed
import http.cookiejar
import json
from pathlib import Path
import time
import uuid

import fixtures


def renew(limit=1000, workers=8):
    source = Path((fixtures.PRIVATE / 'latest-sessions-path').read_text().strip()).resolve()
    assert source.is_relative_to(fixtures.PRIVATE.resolve())
    users = json.loads(source.read_text())[:limit]
    assert len(users) == limit
    stamp = time.strftime('%Y%m%dT%H%M%SZ', time.gmtime()) + '-renew-' + uuid.uuid4().hex[:6]
    output = fixtures.PRIVATE / ('sessions-' + stamp)
    output.mkdir(mode=0o700)
    candidates = {}
    for path in fixtures.PRIVATE.glob('sessions-*/cookies-*.json'):
        candidates.setdefault(path.name, []).append(path)

    def one(user):
        client = fixtures.Client()
        for path in sorted(candidates.get(f"cookies-{user['user_id']}.json", []), key=lambda p: p.stat().st_mtime, reverse=True):
            cookies = json.loads(path.read_text())
            def same_session(cookie):
                try:
                    return fixtures.decode_claims(cookie['value']).get('sid') == user['sid']
                except (ValueError, IndexError, UnicodeError):
                    return False
            if not any(same_session(cookie) for cookie in cookies):
                continue
            for cookie in cookies:
                client.jar.set_cookie(http.cookiejar.Cookie(0, cookie['name'], cookie['value'], None, False,
                    cookie['domain'], bool(cookie['domain']), cookie['domain'].startswith('.'),
                    cookie['path'], True, cookie['secure'], cookie['expires'], False, None, None, {}))
            break
        else:
            raise RuntimeError('No matching private refresh cookie')
        csrf = client.request('GET', '/api/v1/auth/token/csrf')['data']['csrf_token']
        operation = str(uuid.uuid4())
        fixtures.private_write(output / f"refresh-operation-{user['user_id']}.json",
                               json.dumps({'operation_id': operation, 'source_cookie_file': str(path)}))
        response = client.request('POST', '/api/v1/auth/token/refresh',
                                  headers={'X-CSRF-TOKEN': csrf, 'X-Refresh-Operation-Id': operation})['data']
        # Persist rotated cookies before later validation: a validation failure must
        # not strand the caller with only the now-old refresh token.
        cookies = [{'name': c.name, 'value': c.value, 'domain': c.domain, 'path': c.path,
                    'secure': c.secure, 'expires': c.expires} for c in client.jar]
        fixtures.private_write(output / f"cookies-{user['user_id']}.json", json.dumps(cookies))
        token = response['access_token']
        claims = fixtures.decode_claims(token)
        assert int(claims['sub']) == user['user_id'] and claims['sid'] == user['sid']
        client.request('GET', '/api/v1/users/me', token=token)
        sessions = client.request('GET', '/api/v1/auth/sessions', token=token)['data']
        assert any(s['sid'] == user['sid'] and s['current'] for s in sessions)
        client.request('POST', '/api/v1/locations/resolve', user['coordinate'], token=token)
        saved = {**user, 'access_token': token, 'expires_at': claims['exp']}
        fixtures.private_write(output / f"user-{user['user_id']}.json", json.dumps(saved))
        return saved

    records, failures = [], []
    with ThreadPoolExecutor(max_workers=workers) as executor:
        jobs = {executor.submit(one, user): user for user in users}
        for future in as_completed(jobs):
            try:
                records.append(future.result())
            except Exception as error:
                # fixtures.Client exposes only HTTP stage and allowlisted error codes.
                failures.append({'user_id': jobs[future]['user_id'], 'error': str(error) if isinstance(error, RuntimeError) else type(error).__name__})
            count = len(records) + len(failures)
            if count % 100 == 0 or limit <= 10:
                print(f'Renewed {count}/{limit}: success={len(records)}, failures={len(failures)}', flush=True)
    fixtures.private_write(output / 'users.json', json.dumps(sorted(records, key=lambda u: u['user_id']), indent=2))
    report = {'requested': limit, 'success': len(records), 'failures': failures,
              'method': 'real CSRF + refresh rotation + users/me + session + location verification',
              'login_503_resolved': False, 'source_private_file': str(source)}
    (fixtures.ROOT / 'results' / ('fixtures-' + stamp + '.json')).write_text(json.dumps(report, indent=2) + '\n')
    if failures:
        raise SystemExit('Refresh preparation failed; private rotated cookies retained for inspection, no load started.')
    assert len({u['user_id'] for u in records}) == len({u['sid'] for u in records}) == limit
    if limit == 1000:
        fixtures.private_write(fixtures.PRIVATE / 'latest-sessions-path', str(output / 'users.json'))
    return output / 'users.json'


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--limit', type=int, choices=[10, 1000], default=1000)
    args = parser.parse_args()
    print('Private renewed sessions:', renew(args.limit))
