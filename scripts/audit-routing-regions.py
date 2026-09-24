#!/usr/bin/env python3
"""Audit unfiltered neighborhoods around the first 20 passing via-way relations."""
import csv
import json
from pathlib import Path
import subprocess
import tempfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
source = ROOT / 'backend/data/processed/seoul-routing-complete.osm.pbf'
rows = list(csv.DictReader((ROOT / 'backend/data/via-way-audit.tsv').open(), delimiter='\t'))
ids = sorted(int(r['relation']) for r in rows if r['status'] == 'PASS')[:20]

def run(*args):
    subprocess.run([str(a) for a in args], check=True)

with tempfile.TemporaryDirectory(prefix='routing-region-audit-') as tmp:
    folder = Path(tmp)
    xml = subprocess.check_output(['osmium', 'getid', str(source), *(f'r{i}' for i in ids), '-r', '-f', 'osm'])
    root = ET.fromstring(xml)
    nodes = {n.get('id'): (float(n.get('lon')), float(n.get('lat'))) for n in root.findall('node')}
    ways = {w.get('id'): [n.get('ref') for n in w.findall('nd')] for w in root.findall('way')}
    extracts = []
    for relation in root.findall('relation'):
        if int(relation.get('id')) not in ids:
            continue
        via = next(m.get('ref') for m in relation.findall('member') if m.get('role') == 'via')
        lon, lat = nodes[ways[via][0]]
        extracts.append({'output': f"{relation.get('id')}.osm.pbf", 'bbox': [lon-.0015, lat-.0015, lon+.0015, lat+.0015]})
    config = folder / 'extracts.json'
    config.write_text(json.dumps({'directory': str(folder), 'extracts': extracts}))
    # Keep every road and restriction; complete restriction references at boundaries.
    run('osmium', 'extract', source, '-c', config, '-s', 'smart', '-S',
        'types=restriction,restriction:motorcar,restriction:motor_vehicle,restriction:vehicle')
    (ROOT / 'backend/data/region-audit-bounds.json').write_text(json.dumps(extracts, indent=2)+'\n')
    run(ROOT / 'backend/gradlew', '-p', ROOT / 'backend', ':routing-core:auditRegions',
        f'-Pcases={folder}', f'-Preport={ROOT / "backend/data/region-audit.tsv"}')
