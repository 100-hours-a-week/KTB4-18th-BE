#!/bin/sh
set -eu
PERF_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
SCENARIO=${1:-}
PHASE=${2:-smoke}
case "$SCENARIO" in recommendation|speech|chat|chat-policy) ;; *) echo 'Usage: run.sh recommendation|speech|chat|chat-policy smoke|warmup|baseline|explore|validation' >&2; exit 2;; esac
case "$PHASE" in smoke|warmup|baseline|explore|validation) ;; *) echo 'Invalid phase' >&2; exit 2;; esac
if [ ! -f "$PERF_DIR/generated/accounts.json" ]; then echo 'Run prepare.py first.' >&2; exit 2; fi
RUN_ID=$(date -u '+%Y%m%dT%H%M%SZ')-$SCENARIO-$PHASE
RESULT_DIR="$PERF_DIR/results/$RUN_ID"
mkdir -p "$RESULT_DIR"
python3 "$PERF_DIR/scripts/capture.py" "$RESULT_DIR"
export SCENARIO PHASE RUN_ID
export RATE=${RATE:-1}
export DURATION=${DURATION:-5m}
export VUS=${VUS:-50}
export MAX_VUS=${MAX_VUS:-150}
export ROOMS=${ROOMS:-1}
export INTERVAL_MS=${INTERVAL_MS:-2000}
if [ "$PHASE" = smoke ]; then
  export PER_ROOM=${PER_ROOM:-2}
  export MESSAGES=${MESSAGES:-2}
else
  export PER_ROOM=${PER_ROOM:-25}
  export MESSAGES=${MESSAGES:-150}
fi
if [ "$PHASE" = warmup ]; then
  export DURATION=${WARMUP_DURATION:-2m}
  export MESSAGES=${WARMUP_MESSAGES:-60}
fi
export INPUT_TYPE=${INPUT_TYPE:-TEXT}
export AUDIO_FILE=${AUDIO_FILE:-$PERF_DIR/generated/audio-10s.mp4}
if [ "$SCENARIO" = chat ]; then
  export P95_MS=${P95_MS:-500}
else
  export P95_MS=${P95_MS:-5000}
fi
python3 - "$RESULT_DIR/scenario.json" <<'PYMETA'
import json, os, sys
keys = ('SCENARIO', 'PHASE', 'RATE', 'DURATION', 'VUS', 'MAX_VUS', 'ROOMS', 'PER_ROOM', 'MESSAGES', 'INTERVAL_MS', 'P95_MS', 'AUDIO_FILE', 'INPUT_TYPE', 'TRIAL')
with open(sys.argv[1], 'w') as file:
    json.dump({key: os.environ.get(key, 'scenario-default') for key in keys}, file, indent=2)
PYMETA
cd "$RESULT_DIR"
export PHASE RUN_ID
export ACCOUNTS_FILE="$PERF_DIR/generated/accounts.json"
export AUDIO_FILE=${AUDIO_FILE:-$PERF_DIR/generated/audio-10s.mp4}
export BASE_URL=http://127.0.0.1:18080
export COORDINATOR_URL=http://127.0.0.1:18090
export K6_PROMETHEUS_RW_SERVER_URL=http://127.0.0.1:19090/api/v1/write
export K6_PROMETHEUS_RW_TREND_STATS='p(95),p(99),avg,max'
export K6_WEB_DASHBOARD=true
export K6_WEB_DASHBOARD_EXPORT="$RESULT_DIR/report.html"
k6 inspect --include-system-env-vars "$PERF_DIR/k6/$SCENARIO.js" > resolved-options.json
# Capture resource use separately from the workload; includes mock/monitoring overhead.
STATS_STOP="$RESULT_DIR/stats-stop"
(
  while [ ! -f "$STATS_STOP" ]; do
    date -u '+{"captured_at":"%Y-%m-%dT%H:%M:%SZ"}'
    docker compose --env-file "$PERF_DIR/.env" -f "$PERF_DIR/compose.yaml" stats --no-stream --format '{{json .}}'
    sleep 5
  done
) > "$RESULT_DIR/container-stats.jsonl" 2>&1 &
STATS_PID=$!
trap 'kill "$STATS_PID" 2>/dev/null || true' EXIT INT TERM
set +e
k6 run --tag "run_id=$RUN_ID" --tag "phase=$PHASE" --out json=raw.jsonl \
  --out experimental-prometheus-rw "$PERF_DIR/k6/$SCENARIO.js" > console.txt 2>&1
STATUS=$?
set -e
printf '%s\n' "$STATUS" > exit-code.txt
touch "$STATS_STOP"
wait "$STATS_PID" 2>/dev/null || true
python3 "$PERF_DIR/scripts/record-run.py" "$RESULT_DIR"
cat console.txt
exit "$STATUS"
