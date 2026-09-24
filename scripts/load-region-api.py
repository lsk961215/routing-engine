#!/usr/bin/env python3
"""Bounded loopback load test; process RSS samples and post-GC heap observations."""
import concurrent.futures
import threading
import re
import platform
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

def rss_kib(pid):
    return int(run('ps', '-o', 'rss=', '-p', pid).strip())

def heap_after_gc(pid):
    run('jcmd', pid, 'GC.run')
    info = run('jcmd', pid, 'GC.heap_info')
    # This harness starts the server with G1 explicitly; do not parse metaspace as heap.
    found = re.search(r'garbage-first heap\s+total (\d+)K, used (\d+)K', info)
    if not found:
        raise RuntimeError('Unrecognized G1 heap info: '+info)
    return {'heapCommittedKiB': int(found[1]), 'heapUsedKiB': int(found[2]), 'rssKiB': rss_kib(pid)}

def check(base, row):
    q={k: row[k] for k in ('startLon','startLat','endLon','endLat')}
    started=time.perf_counter(); status,data=request(base,q)
    elapsed=(time.perf_counter()-started)*1000
    if status != int(row['status']):
        raise AssertionError(f'Unexpected HTTP status {status}, expected {row["status"]}')
    if status==200:
        route=data['routes'][0]
        if not math.isclose(route['distance'],float(row['distance']),abs_tol=1e-5):
            raise AssertionError('Distance mismatch')
        points=route['geometry']['coordinates']
        if route['geometry']['type']!='LineString' or len(points)<2:
            raise AssertionError('Invalid geometry')
        for point,wp,keys in zip((points[0],points[-1]),data['waypoints'],(('startLon','startLat'),('endLon','endLat'))):
            if point!=wp['location'] or any(abs(point[i]-float(q[key]))>1e-8 for i,key in enumerate(keys)):
                raise AssertionError('Response geometry crossed between concurrent requests')
    return elapsed,status

def phase(base,pid,queries,workers):
    count=12000; samples=[]; stop=threading.Event(); sample_errors=[]
    def sample():
        while not stop.is_set():
            try: samples.append(rss_kib(pid))
            except Exception as error: sample_errors.append(str(error));return
            stop.wait(.1)
    monitor=threading.Thread(target=sample);monitor.start()
    gate=threading.Barrier(workers+1)
    def worker(index):
        values=[]; gate.wait()
        for i in range(index,count,workers): values.append(check(base,queries[i%len(queries)]))
        return values
    try:
        with concurrent.futures.ThreadPoolExecutor(max_workers=workers) as pool:
            jobs=[pool.submit(worker,i) for i in range(workers)]
            start=time.perf_counter();gate.wait()
            values=[item for job in jobs for item in job.result()]
            elapsed=time.perf_counter()-start
    finally:
        stop.set();monitor.join()
    if sample_errors or not samples: raise RuntimeError('RSS sampling failed: '+str(sample_errors))
    durations=sorted(item[0] for item in values)
    return {'concurrency':workers,'requests':count,'seconds':round(elapsed,4),
            'requestsPerSecond':round(count/elapsed,1),'p50Ms':round(durations[count//2],4),
            'p95Ms':round(durations[math.ceil(count*.95)-1],4),'p99Ms':round(durations[math.ceil(count*.99)-1],4),
            'http200':sum(status==200 for _,status in values),'http404':sum(status==404 for _,status in values),
            'unexpectedResponses':0,'rssSamplePeakKiB':max(samples),'rssSamples':len(samples)}

print(gradle(':routing-api:bootJar'),flush=True)
jars=[p for p in (ROOT/'backend/routing-api/build/libs').glob('*.jar') if not p.name.endswith('-plain.jar')]
if len(jars)!=1: raise SystemExit('Expected one bootJar')
java=Path(os.environ['JAVA_HOME'])/'bin/java' if os.environ.get('JAVA_HOME') else 'java'
with tempfile.TemporaryDirectory(prefix='routing-load-') as temporary:
    folder=Path(temporary)
    for region in (8603390,9305993):
        bbox=next(b['bbox'] for b in bounds if b['output']==f'{region}.osm.pbf')
        pbf,graph,cases=folder/'area.osm.pbf',folder/'area.rgraph',folder/'queries.tsv'
        for file in (pbf,graph,cases): file.unlink(missing_ok=True)
        run('osmium','extract',ROOT/'backend/data/processed/seoul-routing-complete.osm.pbf','-b',','.join(map(str,bbox)),
            '-s','smart','-S','types=restriction,restriction:motorcar,restriction:motor_vehicle,restriction:vehicle','-o',pbf)
        gradle(':routing-core:buildGraph',f'-Ppbf={pbf}',f'-Pgraph={graph}')
        gradle(':routing-core:benchmarkRegionCoordinates',f'-Pgraph={graph}',f'-Pqueries={cases}')
        queries=list(csv.DictReader(cases.open(),delimiter='\t'))
        with socket.socket() as sock:
            sock.bind(('127.0.0.1',0));port=sock.getsockname()[1]
        base=f'http://127.0.0.1:{port}'
        with (folder/'server.log').open('w+') as log:
            process=subprocess.Popen([str(java),'-Xmx512m','-XX:+UseG1GC','-jar',str(jars[0]),
                '--server.address=127.0.0.1',f'--server.port={port}',f'--routing.graph.path={graph}'],stdout=log,stderr=subprocess.STDOUT)
            try:
                deadline=time.monotonic()+40
                while True:
                    if process.poll() is not None:
                        log.seek(0);raise RuntimeError(log.read())
                    try:
                        with urllib.request.urlopen(base+'/actuator/health',timeout=1) as response:
                            if response.status==200:break
                    except OSError:pass
                    if time.monotonic()>deadline:raise TimeoutError('Startup')
                    time.sleep(.2)
                for i in range(300):check(base,queries[i%len(queries)])
                entry={'region':region,'warmup':300,'before':heap_after_gc(process.pid),'phases':[]}
                for workers in (1,4,8,16,32):
                    result=phase(base,process.pid,queries,workers)
                    result['afterGc']=heap_after_gc(process.pid)
                    entry['phases'].append(result)
                    print(json.dumps({'region':region,**result}),flush=True)
                report.append(entry)
            finally:
                process.terminate()
                try:process.wait(timeout=10)
                except subprocess.TimeoutExpired:process.kill();process.wait()
output={'os':platform.system(),'architecture':platform.machine(),'serverMaxHeapMiB':512,'gc':'G1',
        'rssSamplingIntervalSeconds':.1,'regions':report}
(ROOT/'backend/data/region-load-results.json').write_text(json.dumps(output,indent=2)+'\n')
