#!/bin/bash
set -euo pipefail
cd -- "$(dirname -- "$0")"
case "${1:-help}" in
  init) python3 init.py ;;
  build)
    test -f .env || { echo 'Run: bash run.sh init' >&2; exit 1; }
    (cd ../..; ./gradlew bootJar --no-daemon)
    python3 stage.py
    docker compose build backend
    ;;
  up) docker compose up -d --wait --wait-timeout 240 ;;
  status) docker compose ps ;;
  logs) docker compose logs --tail 80 backend ;;
  stop) docker compose --profile redis stop ;;
  down) docker compose --profile redis down ;; # Volumes and results are preserved.
  verify) python3 verify.py ;;
  smoke)
    stamp=$(date -u +%Y%m%dT%H%M%SZ)
    mkdir -p "results/$stamp-env-smoke"
    K6_PROMETHEUS_RW_SERVER_URL=http://127.0.0.1:19090/api/v1/write \
    K6_PROMETHEUS_RW_TREND_STATS='p(95),p(99),avg,max' \
    k6 run -o experimental-prometheus-rw --tag "run_id=$stamp-env-smoke" \
      --out "json=results/$stamp-env-smoke/raw.json" \
      --summary-export "results/$stamp-env-smoke/summary.json" k6/env-smoke.js
    ;;
  *) echo 'Usage: bash run.sh {init|build|up|status|logs|verify|smoke|stop|down}' ;;
esac
