"""Run with python3 scripts/test-export-demo.py; no graph files or running API needed."""
import copy
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('export_demo', Path(__file__).with_name('export-demo.py'))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class DemoExportTest(unittest.TestCase):
    def setUp(self):
        self.query = {'id': 'q', 'name': 'route', 'startLon': 127, 'startLat': 37, 'endLon': 127, 'endLat': 37.01}
        self.counts = {'dijkstra': 100, 'astar': 20, 'alt': 5}
        def report(algorithms):
            samples = [{'queryId': 'q', 'algorithm': a, 'code': 'Ok', 'distanceMetres': 100,
                        'expandedStates': self.counts[a]} for a in algorithms for _ in range(2)]
            return {'version': 2, 'gitRevision': 'abc', 'gitDirty': ' M engine.java', 'generatedAt': '2026-11-09',
                    'settings': {'forks': 1, 'measuredRounds': 2, 'warmupRounds': 1},
                    'querySet': {'id': 'test', 'queries': [self.query]},
                    'forks': [{'graphSha256': 'graph', 'queriesSha256': 'queries', 'environment': {'javaVersion': '21'},
                               'samples': samples, 'landmarks': {'count': 8, 'distanceTableBytes': 800, 'preprocessingMillis': 5}}],
                    'summaries': [{'queryId': 'q', 'algorithm': a, 'searchMillis': {'count': 2, 'median': self.counts[a]},
                                   'expandedStates': {'median': self.counts[a]}, 'allocatedBytes': None} for a in algorithms]}
        self.base = report(['dijkstra', 'astar'])
        self.alt = report(['dijkstra', 'alt'])

    def fetch(self, query, algorithm):
        return {'waypoints': [query['startLon'], query['endLon']], 'results': [
            {'algorithm': a, 'code': 'Ok', 'metrics': {'expandedStates': self.counts[a]},
             'routes': [{'distance': 100, 'geometry': {'type': 'LineString', 'coordinates': [[127, 37], [127, 37.01]]}}]}
            for a in ['dijkstra', algorithm]]}

    def export(self, fetch=None):
        return module.export(self.base, self.alt, 'graph', 'queries', fetch or self.fetch)

    def test_three_routes_keep_separate_provenance_and_preprocessing(self):
        data = self.export()
        self.assertEqual(['dijkstra', 'astar', 'alt'], [r['algorithm'] for r in data['examples'][0]['routes']])
        self.assertEqual([100, 20, 5], [r['searchMillis']['median'] for r in data['examples'][0]['routes']])
        self.assertTrue(data['source']['gitDirty'])
        self.assertEqual(800, data['source']['altBenchmark']['landmarks'][0]['distanceTableBytes'])

    def test_rejects_incompatible_reports_before_querying_api(self):
        original = copy.deepcopy(self.alt)
        mutations = [lambda r: r['settings'].update(measuredRounds=4),
                     lambda r: r['forks'][0].update(graphSha256='wrong'),
                     lambda r: r['forks'][0].update(landmarks=None),
                     lambda r: r['forks'][0]['environment'].update(javaVersion='other'),
                     lambda r: r['summaries'].append(r['summaries'][0])]
        for mutate in mutations:
            self.alt = copy.deepcopy(original)
            mutate(self.alt)
            with self.assertRaises(ValueError):
                self.export(lambda *_: self.fail('Should reject metadata before API calls'))

    def test_rejects_stale_trace_counts_and_missing_samples(self):
        self.alt['forks'][0]['samples'][2]['expandedStates'] = 6
        with self.assertRaisesRegex(ValueError, 'API and benchmark'):
            self.export()
        self.alt['forks'][0]['samples'].pop()
        with self.assertRaisesRegex(ValueError, 'Incomplete measured'):
            self.export()

    def test_rejects_changed_waypoints_and_wrong_alt_distance(self):
        def wrong(query, algorithm):
            pair = self.fetch(query, algorithm)
            if algorithm == 'alt':
                pair['waypoints'] = []
            return pair
        with self.assertRaisesRegex(ValueError, 'snapped waypoints'):
            self.export(wrong)
        def wrong_distance(query, algorithm):
            pair = self.fetch(query, algorithm)
            if algorithm == 'alt':
                pair['results'][1]['routes'][0]['distance'] = 99
            return pair
        with self.assertRaisesRegex(ValueError, 'API and benchmark'):
            self.export(wrong_distance)


if __name__ == '__main__':
    unittest.main()
