#!/usr/bin/env python3
"""Stage the freshly built JAR with immutable source and artifact evidence."""
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
from datetime import datetime, timezone

root = Path(__file__).resolve().parent
repo = root.parent.parent
jars = [p for p in (repo / "build/libs").glob("*.jar") if not p.name.endswith("-plain.jar")]
if len(jars) != 1:
    raise SystemExit("Expected exactly one boot JAR after bootJar.")
(root / ".runtime").mkdir(exist_ok=True)
shutil.copy2(jars[0], root / ".runtime/app.jar")
manifest = {"created_at": datetime.now(timezone.utc).isoformat(),
            "source_head": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=repo, text=True).strip(),
            "source_dirty": bool(subprocess.check_output(["git", "diff", "--name-only", "HEAD", "--", "src", "build.gradle", "settings.gradle"], cwd=repo, text=True).strip()),
            "jar_sha256": hashlib.sha256(jars[0].read_bytes()).hexdigest()}
(root / ".runtime/build.json").write_text(json.dumps(manifest, indent=2) + "\n")
print("Staged JAR and build provenance.")
