package com.lsk.routing.core.graph;

import com.lsk.routing.core.osm.CarDataAnalysis;
import java.nio.file.*;
import java.util.*;

/** Unfiltered regional inputs; failures are reported, never repaired by dropping rules. */
public final class RegionAudit {
    static int verify(RoutingGraph graph) {
        var pairs = new TreeSet<Long>();
        int[] sources = new int[graph.edgeCount()];
        for (int n=0;n<graph.nodeCount();n++)
            for (int e=graph.edgeStart(n);e<graph.edgeEnd(n);e++) sources[e]=n;
        for (var rule:graph.sequenceRestrictions()) for (var path:rule.paths()) {
            for (int i=0;i<path.size();i++)
                pairs.add(((long)sources[path.get(i)]<<32)|(graph.target(path.getLast())&0xffffffffL));
        }
        var random = new Random(20260924);
        for (int i=0;i<40;i++) pairs.add(((long)random.nextInt(graph.nodeCount())<<32)|random.nextInt(graph.nodeCount()));
        for (long pair:pairs) {
            int start=(int)(pair>>>32), end=(int)pair;
            double expected=ViaWayAudit.referenceDistance(graph,start,end);
            var route=new DijkstraRouter(graph).route(start,end);
            if (route.isPresent()!=Double.isFinite(expected)
                    || route.isPresent() && Math.abs(route.get().distanceMetres()-expected)>1e-6)
                throw new IllegalStateException("Reference route mismatch: "+start+" -> "+end);
        }
        return pairs.size();
    }
    public static void main(String[] args) throws Exception {
        var rows=new ArrayList<String>();
        rows.add("region\tstatus\trelations\tnodeViaCandidates\tviaWayCandidates\tnodes\tedges\tturnPairs\tviaRules\tqueries\tdetail");
        var totals=new TreeMap<String,Integer>();
        try(var files=Files.list(Path.of(args[0]))) {
            for(var file:files.filter(p->p.toString().endsWith(".osm.pbf")).sorted().toList()) {
                String status="BUILD_FAILED", analysis="0\t0\t0", metrics="0\t0\t0\t0\t0", detail="";
                try {
                    var a=new CarDataAnalysis().read(file);
                    analysis=a.restrictions()+"\t"+a.candidateNodeVia()+"\t"+a.connectedViaWay();
                    var graph=new GraphBuilder().build(file);
                    metrics=graph.nodeCount()+"\t"+graph.edgeCount()+"\t"+graph.forbiddenTurnCount()+"\t"+graph.sequenceRestrictions().size()+"\t0";
                    status="CHECK_FAILED";
                    Path saved=Files.createTempFile("region-audit-", ".rgraph");
                    try {
                        Files.delete(saved);graph.write(saved);
                        var loaded=RoutingGraph.read(saved);
                        if (!graph.sequenceRestrictions().equals(loaded.sequenceRestrictions())) throw new IllegalStateException("Persistence mismatch");
                        int queries=verify(loaded);
                        metrics=graph.nodeCount()+"\t"+graph.edgeCount()+"\t"+graph.forbiddenTurnCount()+"\t"+graph.sequenceRestrictions().size()+"\t"+queries;
                        status="PASS";
                    } finally {Files.deleteIfExists(saved);}
                } catch(Exception e) {detail=e.getClass().getSimpleName()+": "+e.getMessage();}
                totals.merge(status,1,Integer::sum);
                rows.add(file.getFileName()+"\t"+status+"\t"+analysis+"\t"+metrics+"\t"+detail.replace('\t',' ').replace('\n',' '));
                System.out.println(file.getFileName()+": "+status);
            }
        }
        Files.write(Path.of(args[1]),rows);
        System.out.println("Region audit: "+totals);
        if(totals.keySet().stream().anyMatch(s->s.endsWith("FAILED")))throw new IllegalStateException("Region failures; inspect report");
    }
}
