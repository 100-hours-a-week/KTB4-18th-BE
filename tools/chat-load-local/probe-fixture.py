#!/usr/bin/env python3
"""Use private fixture tokens without placing them in command arguments or logs."""
from datetime import datetime, timezone
import os
from pathlib import Path
import subprocess

root = Path(__file__).resolve().parent
pointer = root / '.runtime/fixtures/latest-sessions-path'
if not pointer.exists():
    raise SystemExit('Run: python3 fixtures.py login')
fixture = Path(pointer.read_text().strip())
if not fixture.is_relative_to(root / '.runtime/fixtures'):
    raise SystemExit('Unexpected fixture path')
output = root / 'results' / (datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ') + '-fixture-probe')
output.mkdir()
environment = {**os.environ, 'FIXTURE_FILE': str(fixture)}
result = subprocess.run(['k6', 'run', '--summary-export', str(output / 'summary.json'), 'k6/fixture-probe.js'], cwd=root, env=environment)
raise SystemExit(result.returncode)
