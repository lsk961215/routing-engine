#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SOURCE="${1:-$ROOT/backend/data/processed/seoul-routing-complete.osm.pbf}"
OUTPUT="${2:-$ROOT/backend/data/processed/seoul.rgraph}"
SOURCE="$(cd "$(dirname "$SOURCE")" && pwd)/$(basename "$SOURCE")"
OUTPUT="$(cd "$(dirname "$OUTPUT")" && pwd)/$(basename "$OUTPUT")"
# Preserve existing artifacts; review or choose a new output path to rebuild.
if [[ -e "$OUTPUT" || -e "$OUTPUT.exclusions.tsv" || -e "$OUTPUT.exclusions.geojson" ]]; then
  echo "Output already exists: $OUTPUT (choose a new output path)" >&2; exit 1
fi
command -v osmium >/dev/null
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
"$ROOT/backend/gradlew" -p "$ROOT/backend" :routing-core:prepareSeoul \
  "-Ppbf=$SOURCE" "-Pids=$TMP/selected.ids" "-Preport=$TMP/exclusions.tsv"
osmium getid -r -i "$TMP/selected.ids" "$SOURCE" -o "$TMP/selected.osm.pbf"
"$ROOT/backend/gradlew" -p "$ROOT/backend" :routing-core:buildGraph \
  "-Ppbf=$TMP/selected.osm.pbf" "-Pgraph=$TMP/graph.rgraph" -Pcomparison=true
python3 "$ROOT/scripts/export-excluded-roads.py" "$SOURCE" "$TMP/exclusions.tsv" "$TMP/exclusions.geojson"
mv "$TMP/exclusions.geojson" "$OUTPUT.exclusions.geojson"
cp "$TMP/exclusions.tsv" "$OUTPUT.exclusions.tsv"
mv "$TMP/graph.rgraph" "$OUTPUT"
echo "Graph: $OUTPUT"
echo "Exclusion audit: $OUTPUT.exclusions.tsv"
echo "Exclusion overlay: $OUTPUT.exclusions.geojson"
