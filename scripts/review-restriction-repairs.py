#!/usr/bin/env python3
"""Produce geometry evidence for unresolved relations; never invent or apply repairs."""
import csv
import json
import math
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET

ROOT=Path(__file__).resolve().parents[1]
DATA=ROOT/'backend/data'
rows=list(csv.DictReader((DATA/'region-failure-details.tsv').open(),delimiter='\t'))
selected={int(r['id']):r for r in rows if r['input'].startswith('region-') and r['entity']=='relation'
          and ('MISSING_' in r['issues'] or 'DISCONNECTED_VIA' in r['issues'])}
xml=subprocess.check_output(['osmium','getid',str(DATA/'processed/seoul-routing-complete.osm.pbf'),
                             *(f'r{i}' for i in sorted(selected)),'-r','-f','osm'])
root=ET.fromstring(xml)
nodes={n.get('id'):[float(n.get('lon')),float(n.get('lat'))] for n in root.findall('node')}
ways={w.get('id'):{'nodes':[n.get('ref') for n in w.findall('nd')],
                       'tags':{t.get('k'):t.get('v') for t in w.findall('tag') if t.get('k') in ('highway','name','oneway')}}
      for w in root.findall('way')}

def metres(a,b):
    lon,lat=map(math.radians,nodes[a]);x,y=map(math.radians,nodes[b])
    h=math.sin((y-lat)/2)**2+math.cos(lat)*math.cos(y)*math.sin((x-lon)/2)**2
    return round(6371008.8*2*math.asin(math.sqrt(min(1,h))),3)

result=[]
for relation in root.findall('relation'):
    rid=int(relation.get('id'))
    if rid not in selected:continue
    members=[{'type':m.get('type'),'ref':m.get('ref'),'role':m.get('role')} for m in relation.findall('member')]
    roles={role:[m for m in members if m['role']==role] for role in ('from','via','to')}
    entry={'relation':rid,'issues':selected[rid]['issues'],'members':members,
           'missingRoles':[role for role,values in roles.items() if not values],
           'roleCounts':{role:len(values) for role,values in roles.items()},'fromToIntersections':[]}
    for a in roles['from']:
        for b in roles['to']:
            if a['type']!='way' or b['type']!='way':continue
            shared=sorted(set(ways[a['ref']]['nodes'])&set(ways[b['ref']]['nodes']))
            entry['fromToIntersections'].append({'from':a['ref'],'to':b['ref'],'sameWay':a['ref']==b['ref'],'nodes':shared})
    entry['viaNodeMembership']=[]
    for via in roles['via']:
        if via['type']=='node':
            for m in roles['from']+roles['to']:
                if m['type']!='way':continue
                refs=ways[m['ref']]['nodes'];nearest=min(refs,key=lambda n:metres(via['ref'],n))
                entry['viaNodeMembership'].append({'via':via['ref'],'role':m['role'],'way':m['ref'],
                    'contained':via['ref'] in refs,'nearestExistingNode':nearest,'distanceMetres':metres(via['ref'],nearest)})
    if len(roles['from'])==len(roles['to'])==1 and roles['via'] and all(m['type']=='way' for m in roles['via']):
        chain=roles['from']+roles['via']+roles['to']
        entry['chainIntersections']=[{'a':a['ref'],'b':b['ref'],'sharedNodes':sorted(set(ways[a['ref']]['nodes'])&set(ways[b['ref']]['nodes']))}
                                     for a,b in zip(chain,chain[1:])]
    entry['decision']='REQUIRES_EXTERNAL_EVIDENCE'
    result.append(entry)
if {r['relation'] for r in result} != set(selected):
    raise RuntimeError('Missing requested relations in input snapshot')
(DATA/'restriction-repair-review.json').write_text(json.dumps({'relations':result,'referencedWays':ways,'nodes':nodes},ensure_ascii=False,indent=2)+'\n')
for e in result:
    print(e['relation'],e['missingRoles'],e['roleCounts'],e.get('chainIntersections',e['viaNodeMembership']))
print('Reviewed',len(result),'relations; applied 0 repairs')
