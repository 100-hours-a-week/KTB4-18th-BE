"""Deterministic AI/map fixture and readiness coordinator; local performance use only."""
import json
import os
import threading
import time
import uuid
from email.parser import BytesParser
from email.policy import default
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs

REGIONS = ['11110', '11140', '11170', '11200', '11215', '11230', '11260', '11290', '11305', '11320']
STATE_LOCK = threading.Lock()
ACTIVE = 0
REQUESTS = 0
BARRIERS = {}
STARTED = time.monotonic()


def settings():
    result = {key: int(os.getenv(key, value)) for key, value in {
        'MOCK_FIRST_TEXT_MS': '200', 'MOCK_TOTAL_MS': '2000',
        'MOCK_STT_MS': '1000', 'MOCK_TRACK_COUNT': '5'}.items()}
    result['MOCK_MODE'] = os.getenv('MOCK_MODE', 'normal')
    if not 0 <= result['MOCK_TRACK_COUNT'] <= 5:
        raise ValueError('track count must be 0..5')
    if not 0 <= result['MOCK_FIRST_TEXT_MS'] <= result['MOCK_TOTAL_MS']:
        raise ValueError('first text delay must be within total delay')
    if result['MOCK_STT_MS'] < 0 or result['MOCK_MODE'] not in {
            'normal', 'error', 'early-close', 'unavailable', 'slow-headers'}:
        raise ValueError('invalid mock mode or delay')
    return result


class Handler(BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'

    def log_message(self, *args):
        pass  # Never log payloads, tokens, request identifiers or coordinates.

    def json(self, payload, status=200):
        content = json.dumps(payload, ensure_ascii=False).encode()
        self.send_response(status)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(content)))
        self.end_headers()
        self.wfile.write(content)

    def do_GET(self):
        parsed = urlparse(self.path)
        if parsed.path == '/health':
            return self.json({'status': 'ok'})
        if parsed.path == '/test/config':
            return self.json(settings())
        if parsed.path.startswith('/test/barriers/'):
            with STATE_LOCK:
                state = BARRIERS.get(parsed.path, {'expected': 0, 'ready': set()})
                payload = {'ready': len(state['ready']), 'expected': state['expected'],
                           'complete': state['expected'] > 0 and len(state['ready']) == state['expected'],
                           'start_at': state.get('start_at', 0)}
            return self.json(payload)
        if parsed.path == '/v2/local/geo/coord2regioncode.json':
            # Synthetic mapping to seeded regions, deliberately independent of real geography.
            try:
                longitude = float(parse_qs(parsed.query)['x'][0])
                index = round((longitude - 126.95) / 0.001)
                if not 0 <= index < len(REGIONS):
                    raise ValueError()
                return self.json({'documents': [{'region_type': 'B', 'code': REGIONS[index] + '00000'}]})
            except (ValueError, KeyError):
                return self.json({'documents': []}, 400)
        if parsed.path == '/metrics':
            with STATE_LOCK:
                content = (f'mock_active_requests {ACTIVE}\nmock_requests_total {REQUESTS}\n'
                           f'mock_uptime_seconds {time.monotonic()-STARTED}\n').encode()
            self.send_response(200)
            self.send_header('Content-Type', 'text/plain; version=0.0.4')
            self.send_header('Content-Length', str(len(content)))
            self.end_headers()
            self.wfile.write(content)
            return
        self.json({'error': 'not found'}, 404)

    def do_POST(self):
        global ACTIVE, REQUESTS
        length = int(self.headers.get('Content-Length', '0'))
        if not 0 < length <= 11 * 1024 * 1024:
            return self.json({'error': 'invalid body size'}, 413)
        body = self.rfile.read(length)
        path = urlparse(self.path).path
        if path.startswith('/test/barriers/'):
            try:
                data = json.loads(body)
                expected = int(data['expected'])
                participant = int(data['participant'])
                if not 1 <= expected <= 250 or not 1 <= participant <= expected:
                    raise ValueError()
                with STATE_LOCK:
                    # Limit coordinator state; only new runs evict an old run.
                    if path not in BARRIERS and len(BARRIERS) >= 100:
                        BARRIERS.pop(next(iter(BARRIERS)))
                    state = BARRIERS.setdefault(path, {'expected': expected, 'ready': set()})
                    if state['expected'] != expected:
                        raise ValueError()
                    state['ready'].add(participant)
                    if len(state['ready']) == expected and 'start_at' not in state:
                        state['start_at'] = int(time.time() * 1000) + 2000
                return self.json({'ready': True})
            except (ValueError, KeyError):
                return self.json({'error': 'invalid barrier'}, 400)
        with STATE_LOCK:
            ACTIVE += 1
            REQUESTS += 1
        try:
            config = settings()
            if path == '/v1/chat/messages':
                data = json.loads(body)
                uuid.UUID(data['thread_id'])
                uuid.UUID(data['request_id'])
                if not isinstance(data['message'], str) or not 1 <= len(data['message']) <= 200:
                    return self.json({'error': 'invalid request'}, 400)
                return self.recommend(config)
            if path == '/v1/transcriptions':
                mime = self.headers.get('Content-Type', '')
                message = BytesParser(policy=default).parsebytes(
                    ('Content-Type: ' + mime + '\r\nMIME-Version: 1.0\r\n\r\n').encode() + body)
                parts = list(message.iter_parts()) if message.is_multipart() else []
                audio = next((part for part in parts if part.get_param('name', header='content-disposition') == 'audio'), None)
                if audio is None or audio.get_content_type() not in {'audio/mp4', 'audio/webm'}:
                    return self.json({'error': 'unsupported audio'}, 415)
                time.sleep(config['MOCK_STT_MS'] / 1000)
                if config['MOCK_MODE'] == 'unavailable':
                    return self.json({'error': 'TRANSCRIPTION_SERVICE_UNAVAILABLE'}, 503)
                return self.json({'transcript': '산책할 때 듣기 좋은 음악을 추천해줘'})
            self.json({'error': 'not found'}, 404)
        except (ValueError, KeyError, TypeError):
            self.json({'error': 'invalid request'}, 400)
        except (BrokenPipeError, ConnectionResetError):
            pass
        finally:
            with STATE_LOCK:
                ACTIVE -= 1

    def event(self, name, data):
        self.wfile.write(('event: ' + name + '\ndata: ' + json.dumps(data, ensure_ascii=False) + '\n\n').encode())
        self.wfile.flush()

    def recommend(self, config):
        if config['MOCK_MODE'] == 'unavailable':
            return self.json({'error': 'SERVICE_UNAVAILABLE'}, 503)
        if config['MOCK_MODE'] == 'slow-headers':
            time.sleep(config['MOCK_TOTAL_MS'] / 1000)
        self.send_response(200)
        self.send_header('Content-Type', 'text/event-stream; charset=utf-8')
        self.send_header('Cache-Control', 'no-cache')
        self.send_header('Connection', 'close')
        self.end_headers()
        self.close_connection = True
        time.sleep(config['MOCK_FIRST_TEXT_MS'] / 1000)
        self.event('text', {'delta': '산책에 어울리는 '})
        if config['MOCK_MODE'] == 'early-close':
            return
        time.sleep((config['MOCK_TOTAL_MS'] - config['MOCK_FIRST_TEXT_MS']) / 1000)
        if config['MOCK_MODE'] == 'error':
            self.event('error', {'detail': 'deterministic mock error'})
            return
        self.event('text', {'delta': '음악을 골랐어요.'})
        self.event('tracks', {'tracks': [
            {'track_id': str(900000000 + index), 'title': f'Fixture Track {index}',
             'artist': f'Fixture Artist {index}', 'preview_url': None,
             'artwork_url': None, 'store_url': 'https://example.com/fixture-track',
             'reason': '고정된 부하 테스트 자료'} for index in range(config['MOCK_TRACK_COUNT'])]})
        self.event('done', {})


if __name__ == '__main__':
    settings()
    ThreadingHTTPServer(('0.0.0.0', int(os.getenv('PORT', '8090'))), Handler).serve_forever()
