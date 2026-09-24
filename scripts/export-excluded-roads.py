#!/usr/bin/env python3
"""Join the exclusion audit to original OSM geometry; no editor metadata is exported."""
import argparse
import csv
import json
import subprocess
import tempfile
import xml.etree.ElementTree as ET
from collections import defaultdict
from pathlib import Path


def export(source, audit, output):
    with audit.open() as stream:
        rows = list(csv.DictReader(stream, delimiter='\t'))
    excluded = {r['id'] for r in rows if r['kind'] in ('excluded_accepted_way', 'excluded_way')}
    reasons = defaultdict(set)
    relation_reasons = {}
    for row in rows:
        if row['kind'] == 'way':
            reasons[row['id']].add(row['reason'])
        elif row['kind'] == 'relation':
            relation_reasons[row['id']] = row['reason']
    with tempfile.TemporaryDirectory() as tmp:
        ids = Path(tmp) / 'ids'
        ids.write_text('\n'.join(['w' + i for i in sorted(excluded)] + ['r' + i for i in sorted(relation_reasons)]))
        xml = Path(tmp) / 'selected.osm'
        subprocess.run(['osmium', 'getid', '-r', '-i', str(ids), str(source), '-o', str(xml)], check=True)
        root = ET.parse(xml).getroot()
    nodes = {n.attrib['id']: [float(n.attrib['lon']), float(n.attrib['lat'])] for n in root.findall('node')}
    related = defaultdict(set)
    node_relations = defaultdict(set)
    relation_nodes = defaultdict(set)
    for relation in root.findall('relation'):
        rid = relation.attrib['id']
        if rid not in relation_reasons:
            continue
        for member in relation.findall('member'):
            if member.attrib['type'] == 'way':
                related[member.attrib['ref']].add(rid)
            elif member.attrib['type'] == 'node':
                node_relations[member.attrib['ref']].add(rid)
                relation_nodes[rid].add(member.attrib['ref'])
    features = []
    for way in root.findall('way'):
        wid = way.attrib['id']
        if wid not in excluded:
            continue
        refs = [n.attrib['ref'] for n in way.findall('nd')]
        coords = [nodes[n] for n in refs]  # Missing geometry must fail, never silently shorten a road.
        tags = {t.attrib['k']: t.attrib['v'] for t in way.findall('tag')}
        node_ids = sorted({r.split(':', 1)[1] for r in reasons[wid] if r.startswith(('node_access_or_barrier:', 'quarantined_node:'))})
        relations = set() if "explicit_car_access_no" in reasons[wid] else set(related[wid])
        for nid in node_ids:
            relations.update(node_relations[nid])
        node_ids = sorted(set(node_ids) | {nid for rid in relations for nid in relation_nodes[rid] if nid in refs})
        detail = sorted(reasons[wid] | {f'relation:{rid}: {relation_reasons[rid]}' for rid in relations})
        direct = 'explicit_car_access_no' in reasons[wid] or any(r.startswith('node_access_or_barrier:') for r in reasons[wid]) or any(relation_reasons[r] != 'quarantined_member' for r in relations)
        if not detail:
            raise ValueError(f'No reason found for excluded way {wid}')
        features.append({'type': 'Feature', 'geometry': {'type': 'LineString', 'coordinates': coords},
                         'properties': {'wayId': wid, 'name': tags.get('name', ''),
                                        'category': 'direct' if direct else 'propagated',
                                        'reasons': detail, 'nodeIds': node_ids, 'relationIds': sorted(relations)}})
    if len(features) != len(excluded):
        raise ValueError('Excluded ways missing from source')
    with output.open('x') as f:
        json.dump({'type': 'FeatureCollection', 'features': features}, f, ensure_ascii=False, separators=(',', ':'))
    print(f'Exported {len(features)} excluded roads: {output}')


if __name__ == '__main__':
    p = argparse.ArgumentParser()
    p.add_argument('source', type=Path)
    p.add_argument('audit', type=Path)
    p.add_argument('output', type=Path)
    a = p.parse_args()
    export(a.source, a.audit, a.output)
