#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
GRAPH="$ROOT/backend/data/processed/seoul.rgraph"
if [[ ! -f "$GRAPH" ]]; then
  echo "Run scripts/build-seoul.sh first." >&2; exit 1
fi
exec "$ROOT/backend/gradlew" -p "$ROOT/backend" :routing-api:bootRun \
  "--args=--routing.graph.path=$GRAPH --routing.exclusions.path=$GRAPH.exclusions.geojson"
