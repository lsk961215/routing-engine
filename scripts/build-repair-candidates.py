#!/usr/bin/env python3
"""Create unapproved historical-member proposals and validate them in temporary extracts."""
import copy
import csv
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
import xml.etree.ElementTree as ET

ROOT=Path(__file__).resolve().parents[1]
DATA=ROOT/'backend/data'
source=DATA/'processed/seoul-routing-complete.osm.pbf'
history=json.loads((DATA/'osm-history-review.json').read_text())
local=json.loads((DATA/'restriction-repair-review.json').read_text())
current={str(r['relation']):r for r in local['relations']}
ids={f'r{s["relation"]}' for s in history['summaries']}
for s in history['summaries']:
    for m in s['lastAllRolesPresent']['members']:ids.add(m['type'][0]+m['ref'])
report=[]
with tempfile.TemporaryDirectory(prefix='repair-candidates-') as tmp:
    folder=Path(tmp);idfile=folder/'ids.txt';idfile.write_text('\n'.join(sorted(ids))+'\n')
    # Missing historical IDs are evidence, not a reason to invent replacements.
    extracted=subprocess.run(['osmium','getid',str(source),'-i',str(idfile),'-r','-f','osm'],capture_output=True)
    if extracted.returncode not in (0,1):raise RuntimeError(extracted.stderr.decode())
    root=ET.fromstring(extracted.stdout)
    entities={e.tag[0]+e.get('id'):e for e in root if e.tag in ('node','way','relation')}
    if extracted.returncode and not (ids-set(entities)):
        raise RuntimeError('Extraction failed without expected missing historical IDs')
    if any('r'+s['relation'] not in entities for s in history['summaries']):
        raise RuntimeError('Current relation missing from snapshot')
    for s in history['summaries']:
        rid=s['relation'];prior=s['lastAllRolesPresent'];before=current[rid]['members'];after=prior['members']
        original=entities['r'+rid]
        actual=[{'type':m.get('type'),'ref':m.get('ref'),'role':m.get('role')} for m in original.findall('member')]
        if actual!=before:raise RuntimeError('Local evidence does not match current snapshot relation '+rid)
        tags={t.get('k'):t.get('v') for t in original.findall('tag')}
        candidate={'relation':rid,'approved':False,'apply':False,
                   'sourceRelationVersion':original.get('version'),'expectedBeforeMembers':before,'expectedBeforeTags':tags,
                   'evidenceUrl':f'https://api.openstreetmap.org/api/0.6/relation/{rid}/{prior["version"]}',
                   'historicalVersion':prior['version'],'historicalTimestamp':prior['timestamp'],
                   'proposedMembers':after if after!=before else None,
                   'uncertainties':['Current restriction validity unverified','Historical topology may not match current roads'],
                   'missingReferences':[]}
        if after==before:
            candidate['status']='NO_MEMBER_REPAIR_FROM_HISTORY';report.append(candidate);continue
        required=set()
        for m in after:
            key=m['type'][0]+m['ref'];required.add(key)
            if key in entities and m['type']=='way':required.update('n'+n.get('ref') for n in entities[key].findall('nd'))
        candidate['missingReferences']=sorted(k for k in required if k not in entities)
        if candidate['missingReferences']:
            candidate['status']='HISTORICAL_REFERENCE_MISSING';report.append(candidate);continue
        osm=ET.Element('osm',version='0.6',generator='routing-engine-candidate-review')
        for kind in ('n','w'):
            for key in sorted(required,key=lambda k:int(k[1:])):
                if key[0]!=kind:continue
                e=copy.deepcopy(entities[key])
                for attribute in ('user','uid','changeset','timestamp'):e.attrib.pop(attribute,None)
                osm.append(e)
        relation=ET.SubElement(osm,'relation',id=rid)
        for m in after:ET.SubElement(relation,'member',type=m['type'],ref=m['ref'],role=m['role'])
        for k,v in tags.items():ET.SubElement(relation,'tag',k=k,v=v)
        file=folder/f'{rid}.osm';ET.ElementTree(osm).write(file,encoding='utf-8',xml_declaration=True)
        subprocess.run(['osmium','cat',str(file),'-o',str(file)+'.pbf'],check=True)
        candidate['status']='PENDING_VALIDATION';report.append(candidate)
    subprocess.run([str(ROOT/'backend/gradlew'),'-p',str(ROOT/'backend'),':routing-core:inspectRegionFailures',
                    f'-Pcases={folder}',f'-Preport={folder/"issues.tsv"}'],check=True)
    issues=list(csv.DictReader((folder/'issues.tsv').open(),delimiter='\t'))
    for candidate in report:
        if candidate['status']!='PENDING_VALIDATION':continue
        rid=candidate['relation']
        candidate['diagnostics']=[{'entity':r['entity'],'id':r['id'],'issues':r['issues']} for r in issues if r['input']==rid+'.osm.pbf']
        built=subprocess.run([str(ROOT/'backend/gradlew'),'-p',str(ROOT/'backend'),':routing-core:buildGraph',
                              f'-Ppbf={folder/(rid+".osm.pbf")}',f'-Pgraph={folder/(rid+".rgraph")}'],text=True,capture_output=True)
        candidate['status']='BUILDABLE_UNAPPROVED' if built.returncode==0 else 'CURRENT_TOPOLOGY_OR_RULE_FAILURE'
        if built.returncode==0:
            candidate['buildSummary']=[line.split(' loadMs=')[0] for line in built.stdout.splitlines()
                                       if line.startswith(('Nodes=','Direction-blocked'))]
            if any('forbiddenTurns=0 viaRules=0' in line for line in candidate['buildSummary']):
                candidate['status']='BUILDABLE_NO_EFFECT_UNAPPROVED'
                candidate['uncertainties'].append('No additional forbidden transitions in the isolated current graph')
        else:
            candidate['buildFailure']=[line.strip() for line in (built.stdout+built.stderr).splitlines() if 'Exception' in line][:3]
        print(rid,candidate['status'],flush=True)
output={'sourceFile':source.name,'sourceSha256':hashlib.sha256(source.read_bytes()).hexdigest(),
        'policy':'Review only. No proposals approved or applied. Revalidate snapshot and members before any future application.',
        'candidates':report}
(DATA/'restriction-repair-candidates.json').write_text(json.dumps(output,ensure_ascii=False,indent=2)+'\n')
from collections import Counter
print(dict(Counter(c['status'] for c in report)))
