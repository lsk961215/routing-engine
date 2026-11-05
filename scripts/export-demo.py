#!/usr/bin/env python3
"""Export real route geometries and matching repeated benchmark summaries for Pages."""
import argparse
import hashlib
import json
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import urlencode
from urllib.request import urlopen

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('report', type=Path)
parser.add_argument('--api-url', default='http://127.0.0.1:8080')
args = parser.parse_args()
report = json.loads(args.report.read_text())
if report['version'] != 2:
    raise SystemExit('A version 2 benchmark report with allocation metrics is required.')
graph_hash = hashlib.sha256((ROOT / 'backend/data/processed/seoul.rgraph').read_bytes()).hexdigest()
queries_hash = hashlib.sha256((ROOT / 'backend/data/seoul-benchmark-queries.json').read_bytes()).hexdigest()
assert all(f['graphSha256'] == graph_hash and f['queriesSha256'] == queries_hash for f in report['forks']), 'Benchmark inputs differ from local inputs'
summaries = {(s['queryId'], s['algorithm']): s for s in report['summaries']}
examples = []
for query in report['querySet']['queries']:
    params = {k: query[k] for k in ('startLon', 'startLat', 'endLon', 'endLat')}
    with urlopen(args.api_url.rstrip('/') + '/api/compare?' + urlencode(params), timeout=60) as response:
        pair = json.load(response)
    routes = []
    assert [r['algorithm'] for r in pair['results']] == ['dijkstra', 'astar']
    for result in pair['results']:
        assert result['code'] == 'Ok', query['id']
        route = result['routes'][0]
        summary = summaries[(query['id'], result['algorithm'])]
        samples = [s for fork in report['forks'] for s in fork['samples'] if s['queryId'] == query['id'] and s['algorithm'] == result['algorithm']]
        assert len(samples) == report['settings']['forks'] * report['settings']['measuredRounds']
        assert all(s['code'] == 'Ok' and abs(s['distanceMetres'] - route['distance']) < 1e-6 and s['expandedStates'] == result['metrics']['expandedStates'] for s in samples), 'API and benchmark results differ'
        assert route['geometry']['type'] == 'LineString' and len(route['geometry']['coordinates']) >= 2
        routes.append({
            'algorithm': result['algorithm'], 'distance': route['distance'], 'geometry': route['geometry'],
            'searchMillis': summary['searchMillis'], 'expandedStates': summary['expandedStates']['median'],
            'allocatedBytes': summary['allocatedBytes'],
        })
    assert abs(routes[0]['distance'] - routes[1]['distance']) < 1e-6
    examples.append({'id': query['id'], 'name': query['name'], 'start': [query['startLon'], query['startLat']], 'end': [query['endLon'], query['endLat']], 'routes': routes})
output = {
    'version': 1, 'generatedAt': datetime.now(timezone.utc).isoformat(),
    'source': {
        'gitRevision': report['gitRevision'], 'benchmarkAt': report['generatedAt'],
        'graphSha256': graph_hash, 'queriesSha256': queries_hash,
        'settings': report['settings'], 'environment': report['forks'][0]['environment'],
    },
    'examples': examples,
}
target = ROOT / 'frontend/src/demo-data.json'
target.write_text(json.dumps(output, ensure_ascii=False, separators=(',', ':'), allow_nan=False) + '\n')
print(f'Exported {len(examples)} examples to {target.relative_to(ROOT)} ({target.stat().st_size:,} bytes)')
