#!/usr/bin/env python3
"""Compare failed extracts with Seoul and Korea snapshots, excluding editor metadata."""
import csv
import json
from pathlib import Path
import subprocess
import tempfile
import xml.etree.ElementTree as ET

ROOT=Path(__file__).resolve().parents[1]
DATA=ROOT/'backend/data'
seoul=DATA/'processed/seoul-routing-complete.osm.pbf'
korea=DATA/'raw/south-korea-260919.osm.pbf'
bounds=json.loads((DATA/'region-audit-bounds.json').read_text())
failed={r['region'] for r in csv.DictReader((DATA/'region-audit.tsv').open(),delimiter='\t') if r['status']=='BUILD_FAILED'}

def run(*args):
    subprocess.run([str(a) for a in args],check=True)

def entities(file):
    root=ET.fromstring(subprocess.check_output(['osmium','cat',str(file),'-f','osm']))
    result={}
    for e in root:
        if e.tag not in ('node','way','relation'):continue
        value={'tags':sorted((t.get('k'),t.get('v')) for t in e.findall('tag'))}
        if e.tag=='node':value['coordinates']=[e.get('lon'),e.get('lat')]
        if e.tag=='way':value['refs']=[n.get('ref') for n in e.findall('nd')]
        if e.tag=='relation':value['members']=[(m.get('type'),m.get('ref'),m.get('role')) for m in e.findall('member')]
        result[e.tag[0]+e.get('id')]=value
    return result

report=[]
with tempfile.TemporaryDirectory(prefix='region-failure-') as tmp:
    folder=Path(tmp);extracts=[]
    for b in bounds:
        if b['output'] in failed:extracts.append({'output':'region-'+b['output'],'bbox':b['bbox']})
    config=folder/'extracts.json';config.write_text(json.dumps({'directory':str(folder),'extracts':extracts}))
    run('osmium','extract',seoul,'-c',config,'-s','smart','-S','types=restriction,restriction:motorcar,restriction:motor_vehicle,restriction:vehicle')
    for extract in extracts:
        region=folder/extract['output'];current=entities(region)
        # Road/node data and restriction relations only; unrelated administrative parents are irrelevant.
        relevant={k:v for k,v in current.items() if not k.startswith('r') or dict(v['tags']).get('type','').startswith('restriction')}
        ids=folder/'ids.txt';ids.write_text('\n'.join(sorted(relevant))+'\n')
        entry={'region':region.name,'entitiesCompared':len(relevant),'comparisons':{}}
        for label,source in [('seoul',seoul),('korea',korea)]:
            target=folder/(label+'-'+region.name.removeprefix('region-'))
            run('osmium','getid',source,'-i',ids,'-r','-o',target)
            original=entities(target)
            entry['comparisons'][label]={'missing':[k for k in relevant if k not in original],
                'different':[k for k,v in relevant.items() if k in original and v!=original[k]]}
        report.append(entry)
    run(ROOT/'backend/gradlew','-p',ROOT/'backend',':routing-core:inspectRegionFailures',
        f'-Pcases={folder}',f'-Preport={DATA/"region-failure-details.tsv"}')
diagnostics=list(csv.DictReader((DATA/'region-failure-details.tsv').open(),delimiter='\t'))
for entry in report:
    name=entry['region'].removeprefix('region-')
    def issues(prefix):
        return sorted((r['entity'],r['id'],r['issues']) for r in diagnostics if r['input']==prefix+name)
    baseline=issues('region-')
    entry['diagnosticRows']=len(baseline)
    entry['diagnosticsMatchSeoul']=baseline==issues('seoul-')
    entry['diagnosticsMatchKorea']=baseline==issues('korea-')
(DATA/'region-source-comparison.json').write_text(json.dumps(report,indent=2)+'\n')
print(json.dumps(report,indent=2))
