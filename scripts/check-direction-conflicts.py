#!/usr/bin/env python3
"""Record the eight diagnosed directional conflicts and build their isolated graphs."""
import csv
import json
from pathlib import Path
import subprocess
import tempfile
import xml.etree.ElementTree as ET

ROOT=Path(__file__).resolve().parents[1]
DATA=ROOT/'backend/data'
rows=list(csv.DictReader((DATA/'region-failure-details.tsv').open(),delimiter='\t'))
ids=sorted({int(r['id']) for r in rows if r['input'].startswith('region-') and 'DIRECTION_CONFLICT' in r['issues']})
report=[]
with tempfile.TemporaryDirectory(prefix='direction-conflicts-') as tmp:
    folder=Path(tmp);combined=folder/'combined.osm.pbf'
    subprocess.run(['osmium','getid',str(DATA/'processed/seoul-routing-complete.osm.pbf'),
                    *(f'r{i}' for i in ids),'-r','-o',str(combined)],check=True)
    root=ET.fromstring(subprocess.check_output(['osmium','cat',str(combined),'-f','osm']))
    ways={w.get('id'):w for w in root.findall('way')}
    relations={int(r.get('id')):r for r in root.findall('relation')}
    for rid in ids:
        relation=relations[rid];members=[]
        for m in relation.findall('member'):
            entry={'role':m.get('role'),'type':m.get('type'),'ref':m.get('ref')}
            if m.get('type')=='way':
                way=ways[m.get('ref')]
                entry['nodes']=[n.get('ref') for n in way.findall('nd')]
                entry['tags']={t.get('k'):t.get('v') for t in way.findall('tag') if t.get('k') in ('highway','name','oneway')}
            members.append(entry)
        pbf,graph=folder/f'{rid}.osm.pbf',folder/f'{rid}.rgraph'
        subprocess.run(['osmium','getid',str(combined),f'r{rid}','-r','-o',str(pbf)],check=True)
        built=subprocess.run([str(ROOT/'backend/gradlew'),'-p',str(ROOT/'backend'),':routing-core:buildGraph',
                              f'-Ppbf={pbf}',f'-Pgraph={graph}'],text=True,capture_output=True)
        if built.returncode:raise RuntimeError(built.stdout+built.stderr)
        expected=f'Direction-blocked no restrictions: [{rid}]'
        if expected not in built.stdout:raise AssertionError('Expected explicit redundancy classification')
        summary=next(line for line in built.stdout.splitlines() if line.startswith('Nodes='))
        if 'forbiddenTurns=0 viaRules=0' not in summary:raise AssertionError(summary)
        report.append({'relation':rid,'members':members,'classification':'STATIC_DIRECTION_BLOCKED_NO',
                       'graph':summary.split(' loadMs=')[0]})
        print(rid,summary,flush=True)
(DATA/'direction-conflict-review.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
