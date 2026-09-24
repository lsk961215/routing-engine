package com.lsk.routing.core.graph;

import com.lsk.routing.core.osm.*;
import de.topobyte.osm4j.core.model.iface.*;
import de.topobyte.osm4j.pbf.seq.PbfIterator;
import java.nio.file.*;
import java.util.*;

/** Entity-level diagnostics; uses the same profile and validator as graph production. */
public final class RegionFailureAnalysis {
    private static Map<String,String> tags(OsmEntity entity) {
        var result=new TreeMap<String,String>();
        for(int i=0;i<entity.getNumberOfTags();i++) {
            var t=entity.getTag(i);result.put(t.getKey(),t.getValue());
        }
        return result;
    }
    static List<String> inspect(Path file) throws Exception {
        var profile=new CarProfile();var ways=new HashMap<Long,RestrictionValidator.Way>();
        var nodes=new HashSet<Long>();var relationIds=new HashSet<Long>();
        var restrictions=new ArrayList<RestrictionValidator.Restriction>();var result=new ArrayList<String>();
        try(var input=Files.newInputStream(file)) {
            var iterator=new PbfIterator(input,false);
            while(iterator.hasNext()) {
                var container=iterator.next();var entity=container.getEntity();var tags=tags(entity);
                switch(container.getType()) {
                    case Node -> nodes.add(entity.getId());
                    case Way -> {
                        var w=(OsmWay)entity;long[] refs=new long[w.getNumberOfNodes()];
                        for(int i=0;i<refs.length;i++)refs[i]=w.getNodeId(i);
                        var decision=profile.evaluate(tags,refs.length);
                        ways.put(w.getId(),new RestrictionValidator.Way(refs,decision));
                        if(decision.status()==CarProfile.Status.DEFERRED)
                            result.add("way\t"+w.getId()+"\t"+decision.reason()+"\t"+tags);
                    }
                    case Relation -> {
                        relationIds.add(entity.getId());
                        if(!tags.getOrDefault("type", "").startsWith("restriction"))continue;
                        var r=(OsmRelation)entity;var members=new ArrayList<RestrictionValidator.Member>();
                        for(int i=0;i<r.getNumberOfMembers();i++) {
                            var m=r.getMember(i);
                            var kind=switch(m.getType()) {
                                case Node -> RestrictionValidator.Kind.NODE;
                                case Way -> RestrictionValidator.Kind.WAY;
                                case Relation -> RestrictionValidator.Kind.RELATION;
                            };
                            members.add(new RestrictionValidator.Member(kind,m.getId(),m.getRole()));
                        }
                        restrictions.add(new RestrictionValidator.Restriction(r.getId(),tags,members));
                    }
                }
            }
        }
        var validator=new RestrictionValidator();
        for(var restriction:restrictions) {
            var issues=new TreeSet<>(validator.validate(restriction,ways,nodes,relationIds));
            issues.remove(RestrictionValidator.Issue.VIA_WAY_DEFERRED);
            if(!issues.isEmpty())result.add("relation\t"+restriction.id()+"\t"+issues+"\t"+restriction.tags()+" "+restriction.members());
        }
        Collections.sort(result);return result;
    }
    public static void main(String[] args) throws Exception {
        var rows=new ArrayList<String>();rows.add("input\tentity\tid\tissues\tdetail");
        try(var files=Files.list(Path.of(args[0]))) {
            for(var file:files.filter(p->p.toString().endsWith(".osm.pbf")).sorted().toList())
                for(var row:inspect(file))rows.add(file.getFileName()+"\t"+row.replace('\n',' '));
        }
        Files.write(Path.of(args[1]),rows);
        System.out.println("Diagnostic rows="+(rows.size()-1));
    }
}
