#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
mkdir -p "$ROOT/load-test/k6/results"

BASE_URL="${BASE_URL:-http://host.docker.internal:8080}"
JWT_SECRET="${JWT_SECRET:-playback-gate-local-secret-key-32bytes-min}"
MEMBER_ID_MIN="${MEMBER_ID_MIN:-9}"
MEMBER_ID_MAX="${MEMBER_ID_MAX:-2008}"
CONTENT_ID="${CONTENT_ID:-1}"

if command -v k6 >/dev/null 2>&1; then
  BASE_URL="${BASE_URL/host.docker.internal/localhost}"
  k6 run \
    -e BASE_URL="$BASE_URL" \
    -e JWT_SECRET="$JWT_SECRET" \
    -e MEMBER_ID_MIN="$MEMBER_ID_MIN" \
    -e MEMBER_ID_MAX="$MEMBER_ID_MAX" \
    -e CONTENT_ID="$CONTENT_ID" \
    "$ROOT/load-test/k6/playback-baseline.js"
else
  docker run --rm \
    --add-host=host.docker.internal:host-gateway \
    -v "$ROOT/load-test/k6:/scripts" \
    -e BASE_URL="$BASE_URL" \
    -e JWT_SECRET="$JWT_SECRET" \
    -e MEMBER_ID_MIN="$MEMBER_ID_MIN" \
    -e MEMBER_ID_MAX="$MEMBER_ID_MAX" \
    -e CONTENT_ID="$CONTENT_ID" \
    grafana/k6:0.55.0 run \
    --out json=/scripts/results/baseline-raw.json \
    /scripts/playback-baseline.js
fi
