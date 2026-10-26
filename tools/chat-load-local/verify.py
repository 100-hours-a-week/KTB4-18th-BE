#!/usr/bin/env python3
"""Read-only environment checks; no load or user creation."""
import json
from urllib.request import urlopen
from urllib.error import HTTPError

def get(url):
    with urlopen(url, timeout=10) as response:
        return response.status, response.read()

assert get("http://127.0.0.1:18080/api/v1/terms")[0] == 200
assert get("http://127.0.0.1:13000/api/health")[0] == 200
assert get("http://127.0.0.1:19090/-/ready")[0] == 200
for path, expected in [("/api/v1/users/me", 401), ("/actuator/prometheus", 404)]:
    try:
        status, _ = get("http://127.0.0.1:18080" + path)
    except HTTPError as error:
        status = error.code
    assert status == expected, (path, status)
_, body = get("http://127.0.0.1:19090/api/v1/targets")
targets = json.loads(body)["data"]["activeTargets"]
backend = [t for t in targets if t["labels"]["job"] == "backend"]
assert backend and all(t["health"] == "up" for t in backend), "Wait for backend scrape (5 seconds)."
print("PASS: proxy API, auth rejection, management isolation, Prometheus scrape, Grafana health.")
