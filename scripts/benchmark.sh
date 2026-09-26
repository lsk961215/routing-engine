#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
exec "$ROOT/backend/gradlew" -p "$ROOT/backend" :routing-api:benchmarkAlgorithms "$@"
