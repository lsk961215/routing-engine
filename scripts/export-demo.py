#!/usr/bin/env python3
"""Export four real algorithm routes with separately attributed paired benchmarks for Pages."""
import argparse
import hashlib
import json
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import urlencode
from urllib.request import urlopen

ROOT = Path(__file__).resolve().parents[1]


def validate_report(report, graph_hash, queries_hash, algorithms):
    if report['version'] != 3:
        raise ValueError('A version 3 benchmark report with work metrics is required.')
    if len(report['forks']) != report['settings']['forks'] or not report['forks']:
        raise ValueError('Incomplete benchmark forks')
    if not all(f['graphSha256'] == graph_hash and f['queriesSha256'] == queries_hash for f in report['forks']):
        raise ValueError('Benchmark inputs differ from local inputs')
    expected = {(q['id'], algorithm) for q in report['querySet']['queries'] for algorithm in algorithms}
    actual = [(s['queryId'], s['algorithm']) for s in report['summaries']]
    if set(actual) != expected or len(actual) != len(expected):
        raise ValueError('Benchmark must contain exactly the expected queries and algorithms')


def measured_route(report, query_id, result):
    if result['code'] != 'Ok':
        raise ValueError(f'No route for {query_id}: {result["algorithm"]}')
    route = result['routes'][0]
    summary = next(s for s in report['summaries'] if (s['queryId'], s['algorithm']) == (query_id, result['algorithm']))
    samples = [s for fork in report['forks'] for s in fork['samples'] if s['queryId'] == query_id and s['algorithm'] == result['algorithm']]
    expected_count = report['settings']['forks'] * report['settings']['measuredRounds']
    if len(samples) != expected_count or summary['searchMillis']['count'] != expected_count:
        raise ValueError('Incomplete measured samples')
    if not all(s['code'] == 'Ok' and abs(s['distanceMetres'] - route['distance']) < 1e-6
               and s['expandedStates'] == result['metrics']['expandedStates'] for s in samples):
        raise ValueError('API and benchmark results differ')
    if summary['expandedStates']['median'] != result['metrics']['expandedStates']:
        raise ValueError('Summary and API expanded state counts differ')
    for key in ('inspectedEdges', 'maxQueueSize'):
        value = result['metrics'].get(key)
        if type(value) is not int or value < 0:
            raise ValueError('Missing or invalid API work metrics')
        if any(type(s.get(key)) is not int or s[key] != value for s in samples):
            raise ValueError('API and benchmark work metrics differ')
        stats = summary.get(key)
        if not stats or stats.get('count') != expected_count or any(stats.get(k) != value for k in ('min', 'median', 'max')):
            raise ValueError('Summary and API work metrics differ')
    baseline = next(s for s in report['summaries'] if s['queryId'] == query_id and s['algorithm'] == 'dijkstra')
    if route['geometry']['type'] != 'LineString' or len(route['geometry']['coordinates']) < 2:
        raise ValueError('Invalid route geometry')
    return {
        'algorithm': result['algorithm'], 'distance': route['distance'], 'geometry': route['geometry'],
        'searchMillis': summary['searchMillis'], 'expandedStates': result['metrics']['expandedStates'],
        'allocatedBytes': summary['allocatedBytes'],
        'inspectedEdges': result['metrics']['inspectedEdges'], 'maxQueueSize': result['metrics']['maxQueueSize'],
        'baselineSearchMillis': baseline['searchMillis'],
    }


def provenance(report):
    return {
        'gitRevision': report['gitRevision'], 'gitDirty': bool(report['gitDirty']), 'benchmarkAt': report['generatedAt'],
        'settings': report['settings'], 'environment': report['forks'][0]['environment'],
    }


def export(report, alt_report, bidirectional_report, graph_hash, queries_hash, fetch_pair):
    runs = [('astar', report), ('alt', alt_report), ('bidirectional', bidirectional_report)]
    for algorithm, run in runs:
        validate_report(run, graph_hash, queries_hash, ['dijkstra', algorithm])
        if (report['querySet'] != run['querySet'] or report['settings'] != run['settings']
                or report['gitRevision'] != run['gitRevision']):
            raise ValueError('Paired benchmark query sets, settings and base revision must match')
    environments = [f['environment'] for _, run in runs for f in run['forks']]
    if any(env != environments[0] for env in environments):
        raise ValueError('Benchmark environments must match')
    landmarks = [f.get('landmarks') for f in alt_report['forks']]
    if any(not item or item['count'] <= 0 or item['distanceTableBytes'] <= 0 or item['preprocessingMillis'] < 0 for item in landmarks):
        raise ValueError('ALT preprocessing metadata is missing')
    if len({(item['count'], item['distanceTableBytes']) for item in landmarks}) != 1:
        raise ValueError('ALT landmark configurations differ between forks')
    reverse_indexes = [f.get('bidirectional') for f in bidirectional_report['forks']]
    if any(not item or item['arrayPayloadBytes'] <= 0 or item['preprocessingMillis'] < 0 for item in reverse_indexes):
        raise ValueError('Bidirectional reverse index metadata is missing')
    if len({item['arrayPayloadBytes'] for item in reverse_indexes}) != 1:
        raise ValueError('Bidirectional reverse index configurations differ between forks')
    examples = []
    for query in report['querySet']['queries']:
        routes = []
        waypoints = None
        for algorithm, run in runs:
            pair = fetch_pair(query, algorithm)
            if ([r['algorithm'] for r in pair['results']] != ['dijkstra', algorithm]
                    or (waypoints is not None and pair['waypoints'] != waypoints)):
                raise ValueError('Unexpected algorithms or inconsistent snapped waypoints')
            waypoints = pair['waypoints']
            baseline = measured_route(run, query['id'], pair['results'][0])
            if not routes:
                routes.append(baseline)
            elif (baseline['geometry'] != routes[0]['geometry']
                    or any(baseline[key] != routes[0][key] for key in ('expandedStates', 'inspectedEdges', 'maxQueueSize'))
                    or abs(baseline['distance'] - routes[0]['distance']) >= 1e-6):
                raise ValueError('Dijkstra baseline differs between paired runs')
            routes.append(measured_route(run, query['id'], pair['results'][1]))
        if any(abs(r['distance'] - routes[0]['distance']) >= 1e-6 for r in routes[1:]):
            raise ValueError('Algorithms disagree on shortest distance')
        examples.append({'id': query['id'], 'name': query['name'], 'start': [query['startLon'], query['startLat']],
                         'end': [query['endLon'], query['endLat']], 'routes': routes})
    return {
        'version': 3, 'generatedAt': datetime.now(timezone.utc).isoformat(),
        'source': {**provenance(report), 'graphSha256': graph_hash, 'queriesSha256': queries_hash,
                   'altBenchmark': {**provenance(alt_report), 'landmarks': landmarks},
                   'bidirectionalBenchmark': {**provenance(bidirectional_report), 'reverseIndexes': reverse_indexes}},
        'examples': examples,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('report', type=Path, help='Dijkstra/A* paired benchmark report')
    parser.add_argument('--alt-report', type=Path, required=True, help='Matching Dijkstra/ALT paired benchmark report')
    parser.add_argument('--bidirectional-report', type=Path, required=True, help='Matching Dijkstra/bidirectional paired benchmark report')
    parser.add_argument('--api-url', default='http://127.0.0.1:8080')
    args = parser.parse_args()
    graph_hash = hashlib.sha256((ROOT / 'backend/data/processed/seoul.rgraph').read_bytes()).hexdigest()
    queries_hash = hashlib.sha256((ROOT / 'backend/data/seoul-benchmark-queries.json').read_bytes()).hexdigest()

    def fetch_pair(query, algorithm):
        params = {k: query[k] for k in ('startLon', 'startLat', 'endLon', 'endLat')}
        params['algorithm'] = algorithm
        with urlopen(args.api_url.rstrip('/') + '/api/compare?' + urlencode(params), timeout=60) as response:
            return json.load(response)

    output = export(json.loads(args.report.read_text()), json.loads(args.alt_report.read_text()), json.loads(args.bidirectional_report.read_text()), graph_hash, queries_hash, fetch_pair)
    target = ROOT / 'frontend/src/demo-data.json'
    # Validate every query before replacing the previous dataset.
    temporary = target.with_suffix('.json.tmp')
    temporary.write_text(json.dumps(output, ensure_ascii=False, separators=(',', ':'), allow_nan=False) + '\n')
    temporary.replace(target)
    print(f'Exported {len(output["examples"])} examples to {target.relative_to(ROOT)} ({target.stat().st_size:,} bytes)')


if __name__ == '__main__':
    main()
