#!/usr/bin/env python3
"""Build two unfiltered regional graphs, verify HTTP coordinates and measure serial costs."""
import csv
import json
import math
import os
from pathlib import Path
import socket
import subprocess
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
GRADLE = ROOT / 'backend/gradlew'
bounds = json.loads((ROOT / 'backend/data/region-audit-bounds.json').read_text())
report = []

def run(*args):
    result = subprocess.run([str(a) for a in args], text=True, capture_output=True)
    if result.returncode:
        raise RuntimeError(result.stdout + result.stderr)
    return result.stdout

def gradle(task, *properties):
    return run(GRADLE, '-p', ROOT / 'backend', task, *properties)

def request(base, q):
    url = base + '/api/route?' + urllib.parse.urlencode(q)
    try:
        with urllib.request.urlopen(url, timeout=10) as response:
            return response.status, json.load(response)
    except urllib.error.HTTPError as error:
        return error.code, None

print(gradle(':routing-api:bootJar'), flush=True)
jars = [p for p in (ROOT/'backend/routing-api/build/libs').glob('*.jar') if not p.name.endswith('-plain.jar')]
if len(jars) != 1:
    raise SystemExit('Expected exactly one bootJar')
java = Path(os.environ['JAVA_HOME'])/'bin/java' if os.environ.get('JAVA_HOME') else 'java'
with tempfile.TemporaryDirectory(prefix='routing-api-audit-') as temporary:
    folder = Path(temporary)
    for region in (8603390, 9305993):
        print(f'Checking region {region}', flush=True)
        bbox = next(b['bbox'] for b in bounds if b['output'] == f'{region}.osm.pbf')
        pbf, graph, cases = folder/'area.osm.pbf', folder/'area.rgraph', folder/'queries.tsv'
        for file in (pbf, graph, cases):
            file.unlink(missing_ok=True)
        run('osmium', 'extract', ROOT/'backend/data/processed/seoul-routing-complete.osm.pbf', '-b',
            ','.join(map(str,bbox)), '-s', 'smart', '-S',
            'types=restriction,restriction:motorcar,restriction:motor_vehicle,restriction:vehicle', '-o', pbf)
        gradle(':routing-core:buildGraph', f'-Ppbf={pbf}', f'-Pgraph={graph}')
        measured = gradle(':routing-core:benchmarkRegionCoordinates', f'-Pgraph={graph}', f'-Pqueries={cases}')
        line = next(line for line in measured.splitlines() if line.startswith('java='))
        report.append(f'region={region} {line}')
        # OS selects a free loopback port. A competing bind makes startup fail, never reuses another server.
        with socket.socket() as sock:
            sock.bind(('127.0.0.1',0)); port=sock.getsockname()[1]
        base = f'http://127.0.0.1:{port}'
        log = (folder/'server.log').open('w+')
        process = subprocess.Popen([str(java), '-Xmx512m', '-jar', str(jars[0]),
                                    '--server.address=127.0.0.1', f'--server.port={port}',
                                    f'--routing.graph.path={graph}'], stdout=log, stderr=subprocess.STDOUT)
        try:
            deadline=time.monotonic()+40
            while True:
                if process.poll() is not None:
                    log.seek(0); raise RuntimeError(log.read())
                try:
                    with urllib.request.urlopen(base+'/actuator/health',timeout=1) as response:
                        if response.status==200: break
                except (OSError, urllib.error.URLError): pass
                if time.monotonic()>deadline: raise TimeoutError('API startup')
                time.sleep(.2)
            queries=list(csv.DictReader(cases.open(),delimiter='\t')); durations=[]; counts={}
            for row in queries:
                q={k:row[k] for k in ('startLon','startLat','endLon','endLat')}
                start=time.perf_counter();status,data=request(base,q);durations.append((time.perf_counter()-start)*1000)
                assert status==int(row['status']), (region,q,status,row)
                counts[status]=counts.get(status,0)+1
                if status==200:
                    route=data['routes'][0]; points=route['geometry']['coordinates']
                    assert math.isclose(route['distance'],float(row['distance']),abs_tol=1e-5)
                    assert data['code']=='Ok' and route['duration'] is None
                    assert route['geometry']['type']=='LineString' and len(points)>=2
                    assert len(data['waypoints'])==2
                    for point,waypoint,keys in zip((points[0],points[-1]),data['waypoints'],(('startLon','startLat'),('endLon','endLat'))):
                        assert point==waypoint['location'] and waypoint['distance']<1e-5
                        assert all(abs(point[i]-float(q[key]))<1e-8 for i,key in enumerate(keys))
            for q,expected in [({'startLon':181,'startLat':37,'endLon':127,'endLat':37},400),
                               ({'startLon':0,'startLat':0,'endLon':0,'endLat':0},422),
                               ({'startLon':'NaN','startLat':37,'endLon':127,'endLat':37},400)]:
                assert request(base,q)[0]==expected
            durations.sort()
            report.append(f'http region={region} cases=60 statuses={counts} extraErrors=3 p50Ms={durations[30]:.4f} p95Ms={durations[56]:.4f} (no HTTP warmup, serial loopback)')
            print(report[-2]+'\n'+report[-1],flush=True)
        finally:
            process.terminate()
            try: process.wait(timeout=10)
            except subprocess.TimeoutExpired: process.kill();process.wait()
            log.close()
(ROOT/'backend/data/region-coordinate-performance.txt').write_text('\n'.join(report)+'\n')
