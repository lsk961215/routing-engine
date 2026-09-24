#!/usr/bin/env python3
"""Read public OSM history; retain topology/tags only, never editor identities."""
import concurrent.futures
from datetime import datetime, timezone
import json
from pathlib import Path
import urllib.request
import xml.etree.ElementTree as ET

ROOT=Path(__file__).resolve().parents[1]
DATA=ROOT/'backend/data'
local=json.loads((DATA/'restriction-repair-review.json').read_text())

def fetch(kind,identifier):
    url=f'https://api.openstreetmap.org/api/0.6/{kind}/{identifier}/history'
    try:
        request=urllib.request.Request(url,headers={'User-Agent':'routing-engine-local-audit/1.0'})
        with urllib.request.urlopen(request,timeout=30) as response:root=ET.fromstring(response.read())
        versions=[]
        for e in root:
            if e.tag!=kind:continue
            v={'version':int(e.get('version')),'timestamp':e.get('timestamp'),'visible':e.get('visible')!='false',
               'tags':{t.get('k'):t.get('v') for t in e.findall('tag')}}
            if kind=='relation':v['members']=[{'type':m.get('type'),'ref':m.get('ref'),'role':m.get('role')} for m in e.findall('member')]
            if kind=='way':v['nodes']=[n.get('ref') for n in e.findall('nd')]
            versions.append(v)
        return {'type':kind,'id':str(identifier),'url':url,'versions':versions}
    except Exception as error:return {'type':kind,'id':str(identifier),'url':url,'error':str(error)}

def retrieve(tasks):
    with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
        return list(pool.map(lambda task:fetch(*task),tasks))

relations=retrieve([('relation',r['relation']) for r in local['relations']])
needed=set();summaries=[]
for history in relations:
    if 'error' in history:continue
    versions=history['versions'];transitions=[]
    for before,after in zip(versions,versions[1:]):
        removed=[m for m in before['members'] if m not in after['members']]
        added=[m for m in after['members'] if m not in before['members']]
        if removed or added:
            transitions.append({'version':after['version'],'timestamp':after['timestamp'],'removed':removed,'added':added})
    original=next(r for r in local['relations'] if str(r['relation'])==history['id'])
    matches=[v['version'] for v in versions if v['members']==original['members']]
    # Last structurally complete version is evidence, not an approved correction.
    complete=[v for v in versions if v['visible'] and all(any(m['role']==role for m in v['members']) for role in ('from','via','to'))]
    prior=complete[-1] if complete else None
    if prior:
        for m in prior['members']:
            if m['type']=='way':needed.add(m['ref'])
    for m in original['members']:
        if m['type']=='way':needed.add(m['ref'])
    summaries.append({'relation':history['id'],'localMemberMatchingVersions':matches,
                      'latestVersion':versions[-1]['version'],'latestVisible':versions[-1]['visible'],
                      'latestMembers':versions[-1]['members'],'lastAllRolesPresent':prior,'memberTransitions':transitions})
ways=retrieve([('way',i) for i in sorted(needed,key=int)])
report={'queriedAtUtc':datetime.now(timezone.utc).isoformat(),'relations':relations,'summaries':summaries,'ways':ways}
(DATA/'osm-history-review.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
print(f'Relation histories={len(relations)} way histories={len(ways)} failures={sum("error" in x for x in relations+ways)}')
for s in summaries:
    p=s['lastAllRolesPresent']
    print(s['relation'],'local',s['localMemberMatchingVersions'],'latest',s['latestVersion'],'lastAllRoles',p['version'] if p else None,
          'changes',[(t['version'],t['timestamp'],t['removed'],t['added']) for t in s['memberTransitions'][-1:]])
if any('error' in x for x in relations+ways):raise SystemExit('Incomplete history review; inspect failures')
