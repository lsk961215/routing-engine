#!/usr/bin/env python3
"""Functional equivalence on fixed Seoul queries; timings are single requests, not benchmarks."""
import json
import math
import urllib.parse
import urllib.request
import urllib.error
from pathlib import Path

cases=json.loads((Path(__file__).resolve().parents[1]/'backend/data/seoul-smoke-results.json').read_text())
for case in cases['results']:
    points=cases['points'][case['from']]+cases['points'][case['to']]
    params=dict(zip(('startLon','startLat','endLon','endLat'),points))
    results=[]
    for algorithm in ('dijkstra','astar'):
        params['algorithm']=algorithm
        with urllib.request.urlopen('http://localhost:8080/api/route?'+urllib.parse.urlencode(params),timeout=60) as r:
            result=json.load(r)
        assert result['metrics']['algorithm']==algorithm
        assert result['metrics']['searchMillis']>=0 and result['metrics']['expandedStates']>=0
        results.append(result)
    a,b=results
    assert math.isclose(a['routes'][0]['distance'],b['routes'][0]['distance'],abs_tol=1e-6)
    assert a['waypoints']==b['waypoints']
    print(case['from'], '→', case['to'],round(a['routes'][0]['distance'],2),
          [(r['metrics']['algorithm'],round(r['metrics']['searchMillis'],2),r['metrics']['expandedStates']) for r in results],flush=True)
params['algorithm']='unknown'
try:
    urllib.request.urlopen('http://localhost:8080/api/route?'+urllib.parse.urlencode(params))
    raise AssertionError('Unknown algorithm accepted')
except urllib.error.HTTPError as e:
    assert e.code==400
