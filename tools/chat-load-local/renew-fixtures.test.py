#!/usr/bin/env python3
"""Ensure a post-refresh validation failure cannot discard the rotated cookie."""
import base64
import http.cookiejar
import importlib.util
import json
from pathlib import Path
import tempfile
from unittest.mock import patch
import fixtures

spec = importlib.util.spec_from_file_location('renew_fixtures', Path(__file__).with_name('renew-fixtures.py'))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)

def cookie(value):
    return http.cookiejar.Cookie(0, 'refresh_token', value, None, False, '127.0.0.1', True, False,
                                 '/', True, False, 4102444800, False, None, None, {})

class Client:
    def __init__(self):
        self.jar = http.cookiejar.CookieJar()
    def request(self, method, path, **kwargs):
        if path.endswith('/csrf'):
            return {'data': {'csrf_token': 'synthetic-csrf'}}
        if path.endswith('/refresh'):
            assert kwargs['headers']['X-Refresh-Operation-Id']
            self.jar.set_cookie(cookie('synthetic-rotated-refresh'))
            return {'data': {'access_token': 'unused-before-forced-validation-failure'}}
        raise AssertionError('Unexpected test request')

with tempfile.TemporaryDirectory() as temporary:
    root = Path(temporary)
    private = root / 'private'
    source = private / 'sessions-source'
    source.mkdir(parents=True)
    (root / 'results').mkdir()
    user = {'user_id': 91000001, 'sid': 'synthetic-sid', 'coordinate': {}}
    (source / 'users.json').write_text(json.dumps([user]))
    pointer = private / 'latest-sessions-path'
    pointer.write_text(str(source / 'users.json'))
    encoded = base64.urlsafe_b64encode(json.dumps({'sid': user['sid']}).encode()).decode().rstrip('=')
    (source / 'cookies-91000001.json').write_text(json.dumps([{'name': 'refresh_token',
        'value': 'header.' + encoded + '.signature', 'domain': '127.0.0.1', 'path': '/',
        'secure': False, 'expires': 4102444800}]))
    # Fail immediately after refresh response/cookie rotation, before downstream validation.
    with patch.multiple(fixtures, ROOT=root, PRIVATE=private, Client=Client), patch.object(fixtures, 'decode_claims', side_effect=lambda token: {'sid': user['sid']} if token.startswith('header.') else (_ for _ in ()).throw(ValueError('synthetic validation failure'))):
        try:
            module.renew(1, 1)
            raise AssertionError('Failed preparation must not be accepted')
        except SystemExit:
            pass
    renewed = next(private.glob('sessions-*-renew-*'))
    saved = json.loads((renewed / 'cookies-91000001.json').read_text())
    assert saved[0]['value'] == 'synthetic-rotated-refresh'
    assert (renewed / 'cookies-91000001.json').stat().st_mode & 0o777 == 0o600
    assert (renewed / 'refresh-operation-91000001.json').exists()
    assert pointer.read_text() == str(source / 'users.json')
print('PASS: rotated cookie and operation retained after validation failure; original session pointer preserved')
