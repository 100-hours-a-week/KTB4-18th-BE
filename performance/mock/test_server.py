import json
import os
import threading
import unittest
import urllib.request
from unittest.mock import patch
from http.server import ThreadingHTTPServer
from server import Handler

class ContractTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()
        cls.base = 'http://127.0.0.1:' + str(cls.server.server_port)

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        cls.thread.join()

    def request(self, path, data=None):
        req = urllib.request.Request(self.base + path, data=data, headers={'Content-Type': 'application/json'})
        with urllib.request.urlopen(req, timeout=3) as response:
            return response.read().decode()

    def recommendation(self):
        return self.request('/v1/chat/messages', json.dumps({
            'thread_id': '12345678-1234-4234-8234-123456789abc',
            'request_id': '12345678-1234-4234-8234-123456789abd', 'message': '추천해줘'}).encode())

    @patch.dict(os.environ, {'MOCK_FIRST_TEXT_MS': '0', 'MOCK_TOTAL_MS': '0', 'MOCK_MODE': 'normal'})
    def test_sse_order_and_tracks(self):
        content = self.recommendation()
        self.assertEqual([line[7:] for line in content.splitlines() if line.startswith('event: ')],
                         ['text', 'text', 'tracks', 'done'])
        tracks = json.loads(next(line[6:] for line in content.splitlines() if '"tracks"' in line))['tracks']
        self.assertEqual(len(tracks), 5)
        self.assertEqual(len({track['track_id'] for track in tracks}), 5)

    @patch.dict(os.environ, {'MOCK_FIRST_TEXT_MS': '0', 'MOCK_TOTAL_MS': '0', 'MOCK_MODE': 'early-close'})
    def test_early_close_has_no_done(self):
        self.assertNotIn('event: done', self.recommendation())

    @patch.dict(os.environ, {'MOCK_FIRST_TEXT_MS': '0', 'MOCK_TOTAL_MS': '0', 'MOCK_MODE': 'error'})
    def test_error_terminal(self):
        self.assertIn('event: error', self.recommendation())

    def test_barrier_requires_all_participants(self):
        path = '/test/barriers/contract-test'
        self.request(path, b'{"expected":2,"participant":1}')
        self.assertFalse(json.loads(self.request(path))['complete'])
        self.request(path, b'{"expected":2,"participant":2}')
        self.assertTrue(json.loads(self.request(path))['complete'])

if __name__ == '__main__':
    unittest.main()
