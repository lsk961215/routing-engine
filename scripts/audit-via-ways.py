#!/usr/bin/env python3
"""Extract isolated via-way relations, run the Java audit, retain only a TSV report."""
import argparse
from pathlib import Path
import subprocess
import tempfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--pbf', type=Path, default=ROOT / 'backend/data/processed/seoul-routing-complete.osm.pbf')
parser.add_argument('--report', type=Path, default=ROOT / 'backend/data/via-way-audit.tsv')
args = parser.parse_args()

def run(*command):
    subprocess.run([str(part) for part in command], check=True)

with tempfile.TemporaryDirectory(prefix='routing-via-audit-') as temporary:
    folder = Path(temporary)
    relations = folder / 'relations.osm'
    run('osmium', 'tags-filter', args.pbf.resolve(), 'r/type=restriction', 'r/type=restriction:*', '-R', '-o', relations)
    ids = []
    for _, relation in ET.iterparse(relations, events=('end',)):
        if relation.tag == 'relation':
            if any(m.get('role') == 'via' and m.get('type') == 'way' for m in relation.findall('member')):
                ids.append(int(relation.get('id')))
            relation.clear()
    if not ids:
        raise SystemExit('No via-way relations found')
    id_file = folder / 'ids.txt'
    id_file.write_text(''.join(f'r{i}\n' for i in sorted(ids)))
    combined = folder / 'combined.osm.pbf'
    run('osmium', 'getid', args.pbf.resolve(), '-i', id_file, '-r', '-o', combined)
    cases = folder / 'cases'
    cases.mkdir()
    for relation_id in sorted(ids):
        run('osmium', 'getid', combined, f'r{relation_id}', '-r', '-o', cases / f'{relation_id}.osm.pbf')
    print(f'Extracted {len(ids)} isolated relations', flush=True)
    run(ROOT / 'backend/gradlew', '-p', ROOT / 'backend', ':routing-core:auditViaWays',
        f'-Pcases={cases}', f'-Preport={args.report.resolve()}')
