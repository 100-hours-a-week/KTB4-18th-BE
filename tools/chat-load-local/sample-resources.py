#!/usr/bin/env python3
"""Capture only this Compose project's Docker CPU/memory statistics."""
import json
from pathlib import Path
import subprocess
import time
from datetime import datetime, timezone

root = Path(__file__).resolve().parent
stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
out = root / "results" / (stamp + "-resources.jsonl")
print("Sampling every 5 seconds; Ctrl-C to stop:", out)
try:
    with out.open("x") as output:
        while True:
            ids = subprocess.check_output(["docker", "compose", "ps", "-q"], cwd=root, text=True).split()
            if not ids:
                raise SystemExit("No running containers in this project.")
            raw = subprocess.check_output(["docker", "stats", "--no-stream", "--format", "{{json .}}", *ids], text=True)
            output.write(json.dumps({"at": datetime.now(timezone.utc).isoformat(), "containers": [json.loads(line) for line in raw.splitlines()]}) + "\n")
            output.flush()
            time.sleep(5)
except KeyboardInterrupt:
    print("Saved resource samples.")
