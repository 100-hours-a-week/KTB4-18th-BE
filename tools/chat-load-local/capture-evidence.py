#!/usr/bin/env python3
"""Save selected non-secret runtime evidence; never dump container environments."""
import base64
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import subprocess
from urllib.parse import urlencode
from urllib.request import Request, urlopen

root = Path(__file__).resolve().parent
stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
out = root / "results" / (stamp + "-environment-evidence")
out.mkdir()
private = dict(line.split("=", 1) for line in (root / ".env").read_text().splitlines() if "=" in line)

def get(path, grafana=False):
    base = "http://127.0.0.1:13000" if grafana else "http://127.0.0.1:19090"
    request = Request(base + path)
    if grafana:
        token = base64.b64encode(("localtester:" + private["LOCAL_GRAFANA_PASSWORD"]).encode()).decode()
        request.add_header("Authorization", "Basic " + token)
    with urlopen(request, timeout=15) as response:
        return json.load(response)

evidence = {"at": datetime.now(timezone.utc).isoformat(), "targets": get("/api/v1/targets")}
for metric in ["up{job=\"backend\"}", "process_cpu_usage{job=\"backend\"}", "jvm_memory_used_bytes{job=\"backend\",area=\"heap\"}", "last_over_time(k6_http_reqs_total[30m])"]:
    response = get("/api/v1/query?" + urlencode({"query": metric}))
    assert response["status"] == "success" and response["data"]["result"], metric
    evidence[metric] = response
assert get("/api/dashboards/uid/chat-local-environment", True)["dashboard"]["uid"] == "chat-local-environment"
evidence["grafana_datasource"] = get("/api/datasources/uid/chat-local-prometheus/health", True)
assert evidence["grafana_datasource"]["status"] == "OK"
ids = subprocess.check_output(["docker", "compose", "--profile", "redis", "ps", "-q"], cwd=root, text=True).split()
selected = []
for cid in ids:
    fmt = '{{json .Name}}|{{json .Config.Image}}|{{json .HostConfig.Memory}}|{{json .HostConfig.NanoCpus}}|{{json .HostConfig.PortBindings}}'
    raw = subprocess.check_output(["docker", "inspect", "--format", fmt, cid], text=True).strip()
    name, image, memory, cpu, ports = [json.loads(part) for part in raw.split("|")]
    selected.append({"name": name, "image": image, "memory_bytes": memory, "nanocpus": cpu, "host_ports": ports})
evidence["containers"] = selected
evidence["resource_sample"] = [json.loads(line) for line in subprocess.check_output(["docker", "stats", "--no-stream", "--format", "{{json .}}", *ids], text=True).splitlines()]
evidence["config_sha256"] = {str(p.relative_to(root)): hashlib.sha256(p.read_bytes()).hexdigest() for p in root.rglob("*") if p.is_file() and p.suffix in {".yaml", ".json", ".conf", ".py", ".sh"} and not any(x in p.relative_to(root).parts for x in [".runtime", "results"])}
(out / "evidence.json").write_text(json.dumps(evidence, indent=2) + "\n")
print("PASS: JVM and k6 metrics present, provisioned dashboard and datasource healthy; evidence:", out)
