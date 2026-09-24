#!/usr/bin/env python3
"""Inspect overlapping restriction roles from the local OSM snapshot (no inferred repairs)."""
import argparse
import json
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--pbf', type=Path, default=ROOT / 'backend/data/processed/seoul-routing-complete.osm.pbf')
parser.add_argument('--output', type=Path, default=ROOT / 'backend/data/via-overlap-geometry.json')
args = parser.parse_args()
ids = [19911061, 19911062, 19911097, 19911107]
root = ET.fromstring(subprocess.check_output([
    'osmium', 'getid', str(args.pbf), *(f'r{i}' for i in ids), '-r', '-f', 'osm']))
nodes = {n.get('id'): [float(n.get('lon')), float(n.get('lat'))] for n in root.findall('node')}
ways = {}
for way in root.findall('way'):
    tags = {t.get('k'): t.get('v') for t in way.findall('tag')}
    refs = [n.get('ref') for n in way.findall('nd')]
    ways[way.get('id')] = {'name': tags.get('name'), 'oneway': tags.get('oneway'),
                          'nodes': refs, 'coordinates': [nodes[n] for n in refs]}
relations = []
for relation in root.findall('relation'):
    members = [{'role': m.get('role'), 'way': m.get('ref')} for m in relation.findall('member')]
    roles = {m['role']: m['way'] for m in members}
    a, b = ways[roles['from']], ways[roles['to']]
    shared = sorted(set(a['nodes']) & set(b['nodes']))
    relations.append({'id': relation.get('id'), 'members': members,
                      'restriction': next(t.get('v') for t in relation.findall('tag') if t.get('k') == 'restriction'),
                      'from_to_shared_nodes': shared,
                      'shared_is_endpoint_of_both': len(shared) == 1 and shared[0] in (a['nodes'][0], a['nodes'][-1])
                      and shared[0] in (b['nodes'][0], b['nodes'][-1])})
if len(relations) != len(ids):
    raise SystemExit('Missing requested relations')
args.output.write_text(json.dumps({'ways': ways, 'relations': relations}, ensure_ascii=False, indent=2)+'\n')
print(f'Inspected {len(relations)} relations, {len(ways)} ways: {args.output.name}')
