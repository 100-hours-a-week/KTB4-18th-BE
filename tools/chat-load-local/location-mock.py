#!/usr/bin/env python3
"""Restricted synthetic Gangnam location; no external service calls."""
import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_GET(self):
        route = urlparse(self.path)
        if route.path == "/health":
            body, status = {"status": "UP"}, 200
        elif route.path == "/v2/local/geo/coord2regioncode.json":
            query = parse_qs(route.query)
            try:
                valid = abs(float(query["x"][0]) - 127.0276) < 0.000001 and abs(float(query["y"][0]) - 37.4979) < 0.000001
            except (KeyError, ValueError, IndexError):
                valid = False
            if valid and self.headers.get("Authorization") == "KakaoAK local-fixture-only":
                body, status = {"documents": [{"region_type": "B", "code": "1168010100"}]}, 200
            else:
                body, status = {"error": "unsupported synthetic coordinate"}, 400
        else:
            body, status = {"error": "not found"}, 404
        payload = json.dumps(body).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

ThreadingHTTPServer(("0.0.0.0", 8080), Handler).serve_forever()
