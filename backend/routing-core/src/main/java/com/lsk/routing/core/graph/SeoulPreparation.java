package com.lsk.routing.core.graph;

import com.lsk.routing.core.osm.*;
import de.topobyte.osm4j.core.model.iface.*;
import de.topobyte.osm4j.pbf.seq.PbfIterator;
import java.nio.file.*;
import java.util.*;

/** Offline algorithm-comparison selection; unsupported relations do not remove roads. */
public final class SeoulPreparation {
    public static void main(String[] args) throws Exception {
        if(args.length!=3)throw new IllegalArgumentException("Expected source PBF, selected IDs, audit TSV");
        var ways=new HashMap<Long,RestrictionValidator.Way>();
        var restrictions=new ArrayList<RestrictionValidator.Restriction>();
        var viaNodes=new HashSet<Long>();
        var audit=new ArrayList<String>();
        audit.add("kind\tid\treason");
        try(var input=Files.newInputStream(Path.of(args[0]))) {
            var it=new PbfIterator(input,false);
            while(it.hasNext()) {
                var c=it.next();var entity=c.getEntity();var tags=tags(entity);
                if(c.getType()==EntityType.Node) {
                    if(ComparisonProfile.blockedNode(tags))audit.add("blocked_node\t"+entity.getId()+"\tcar_passage_denied");
                    else if(tags.containsKey("barrier"))audit.add("ignored_node_rule\t"+entity.getId()+"\tbarrier_without_clear_denial");
                } else if(c.getType()==EntityType.Way) {
                    var w=(OsmWay)entity;long[] ns=new long[w.getNumberOfNodes()];for(int i=0;i<ns.length;i++)ns[i]=w.getNodeId(i);
                    var d=ComparisonProfile.evaluate(tags,ns.length);
                    if(d.accepted() && !ComparisonProfile.ignoredRules(tags).equals("{}"))audit.add("ignored_way_rule\t"+w.getId()+"\t"+ComparisonProfile.ignoredRules(tags));
                    ways.put(w.getId(),new RestrictionValidator.Way(ns,d));
                } else if(tags.getOrDefault("type","").startsWith("restriction")) {
                    var r=(OsmRelation)entity;var members=new ArrayList<RestrictionValidator.Member>();
                    for(int i=0;i<r.getNumberOfMembers();i++) {
                        var m=r.getMember(i);var kind=switch(m.getType()){case Node->RestrictionValidator.Kind.NODE;case Way->RestrictionValidator.Kind.WAY;case Relation->RestrictionValidator.Kind.RELATION;};
                        members.add(new RestrictionValidator.Member(kind,m.getId(),m.getRole()));
                        if(kind==RestrictionValidator.Kind.NODE)viaNodes.add(m.getId());
                    }
                    restrictions.add(new RestrictionValidator.Restriction(r.getId(),tags,members));
                }
            }
        }
        var existingNodes=new HashSet<Long>();var existingRelations=new HashSet<Long>();
        try(var input=Files.newInputStream(Path.of(args[0]))) {
            var it=new PbfIterator(input,false);while(it.hasNext()) {var c=it.next();long id=c.getEntity().getId();
                if(c.getType()==EntityType.Node && viaNodes.contains(id))existingNodes.add(id);
                if(c.getType()==EntityType.Relation)existingRelations.add(id);
            }
        }
        var removed=new HashSet<Long>();var validator=new RestrictionValidator();
        for(var entry:ways.entrySet()) {
            var w=entry.getValue();
            if(!w.decision().accepted()) {
                if(w.decision().reason().equals("access_denied")) {
                    audit.add("excluded_way\t"+entry.getKey()+"\texplicit_car_access_no");
                    audit.add("way\t"+entry.getKey()+"\texplicit_car_access_no");
                }
            }
        }
        for(var r:restrictions) {
            var issues=validator.validate(r,ways,existingNodes,existingRelations);
            if(validator.isDirectionBlockedNo(r,issues)) {removed.add(r.id());audit.add("redundant_relation\t"+r.id()+"\tdirection_blocked_no");continue;}
            String value=r.tags().get("restriction");for(String k:List.of("restriction:motorcar","restriction:motor_vehicle","restriction:vehicle"))if(r.tags().containsKey(k)){value=r.tags().get(k);break;}
            var from=r.members().stream().filter(m->m.role().equals("from")).findFirst();var to=r.members().stream().filter(m->m.role().equals("to")).findFirst();
            boolean ambiguous=from.isPresent() && to.isPresent() && from.get().id()==to.get().id() && !"no_u_turn".equals(value);
            var seen=new HashSet<Long>();
            for(var m:r.members())if(m.kind()==RestrictionValidator.Kind.WAY && m.role().equals("via"))
                ambiguous |= !seen.add(m.id()) || from.isPresent() && from.get().id()==m.id() || to.isPresent() && to.get().id()==m.id();
            if(ambiguous || issues.stream().anyMatch(i->i!=RestrictionValidator.Issue.VIA_WAY_DEFERRED)) {
                removed.add(r.id());audit.add("relation\t"+r.id()+"\t"+(ambiguous?"ambiguous_geometry ":"")+new TreeSet<>(issues));
            }
        }
        var ids=new ArrayList<String>();long accepted=0;
        for(var entry:new TreeMap<>(ways).entrySet())if(entry.getValue().decision().accepted()) {
            accepted++;ids.add("w"+entry.getKey());
        }
        for(var r:restrictions)if(!removed.contains(r.id()))ids.add("r"+r.id());
        Files.write(Path.of(args[1]),ids,StandardOpenOption.CREATE_NEW);
        Files.write(Path.of(args[2]),audit,StandardOpenOption.CREATE_NEW);
        System.out.printf("Comparison roads=%d restrictions=%d applied=%d skipped=%d%n",accepted,restrictions.size(),restrictions.size()-removed.size(),removed.size());
    }
    private static Map<String,String> tags(OsmEntity e) {var tags=new HashMap<String,String>();for(int i=0;i<e.getNumberOfTags();i++){var t=e.getTag(i);tags.put(t.getKey(),t.getValue());}return tags;}
}
