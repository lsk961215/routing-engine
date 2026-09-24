#!/usr/bin/env python3
"""Smoke checks against a running Seoul graph; sequential requests, no load claim."""
import argparse
import hashlib
import json
import math
import time
import urllib.parse
import urllib.request
from pathlib import Path

fixture = Path(__file__).resolve().parents[1] / 'backend/data/seoul-smoke-results.json'
parser = argparse.ArgumentParser()
parser.add_argument('--record', action='store_true', help='Update the local smoke result report after all checks pass')
args = parser.parse_args()
cases = json.loads(fixture.read_text())
measurements = []
for case in cases['results']:
    start, end = cases['points'][case['from']], cases['points'][case['to']]
    params = dict(zip(('startLon', 'startLat', 'endLon', 'endLat'), start + end))
    before = time.monotonic()
    with urllib.request.urlopen('http://localhost:8080/api/route?' + urllib.parse.urlencode(params), timeout=60) as response:
        assert response.status == 200
        result = json.load(response)
    route = result['routes'][0]
    assert result['code'] == 'Ok' and route['duration'] is None
    assert math.isfinite(route['distance']) and route['distance'] > 1000
    assert len(route['geometry']['coordinates']) > 2
    assert route['geometry']['coordinates'][0] == result['waypoints'][0]['location']
    assert route['geometry']['coordinates'][-1] == result['waypoints'][1]['location']
    assert all(0 <= w['distance'] <= 50 for w in result['waypoints'])
    measurements.append({'from': case['from'], 'to': case['to'], 'status': 200,
                         'metres': route['distance'], 'coordinates': len(route['geometry']['coordinates']),
                         'ms': round((time.monotonic()-before)*1000, 2)})
    print(f"{case['from']} → {case['to']}: {route['distance']:.1f} m, {(time.monotonic()-before)*1000:.1f} ms")

if args.record:
    graph = fixture.parent / 'processed/seoul.rgraph'
    cases['graphSha256'] = hashlib.sha256(graph.read_bytes()).hexdigest()
    cases['results'] = measurements
    fixture.write_text(json.dumps(cases, ensure_ascii=False, indent=2) + '\n')
