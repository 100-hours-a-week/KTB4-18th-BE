#!/usr/bin/env python3
"""Generate only local secrets; preserve any existing environment file."""
import base64
import os
from pathlib import Path
import secrets

root = Path(__file__).resolve().parent
keys = ["LOCAL_DB_PASSWORD", "LOCAL_DB_ROOT_PASSWORD", "LOCAL_AUTH_JWT_SECRET",
        "LOCAL_AUTH_HMAC_KEY", "LOCAL_LOCATION_SECRET", "LOCAL_GRAFANA_PASSWORD"]
try:
    fd = os.open(root / ".env", os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
except FileExistsError:
    print("Existing .env preserved.")
else:
    with os.fdopen(fd, "w") as output:
        for key in keys:
            output.write(f"{key}={secrets.token_hex(32)}\n")
        output.write("LOCAL_AUTH_REPLAY_KEY=" + base64.b64encode(secrets.token_bytes(32)).decode() + "\n")
    print("Created private .env (mode 600); no secret values printed.")
(root / ".runtime").mkdir(exist_ok=True)
(root / ".runtime/metrics").mkdir(exist_ok=True)
(root / "results").mkdir(exist_ok=True)
