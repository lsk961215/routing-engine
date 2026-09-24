package com.lsk.routing.core.graph;

import java.io.IOException;
import java.util.*;

/** Bounded enumeration of continuous directed paths through via ways in member order. */
final class ViaWayCompiler {
    private final RoutingGraph graph;
    private final List<Long> via;
    private final long to;
    private final Set<List<Integer>> paths=new LinkedHashSet<>();
    private int visits;
    private ViaWayCompiler(RoutingGraph graph,List<Long> via,long to) {
        this.graph=graph;this.via=List.copyOf(via);this.to=to;
    }
    static RoutingGraph.SequenceRestriction compile(RoutingGraph graph,long from,List<Long> via,long to,boolean only) throws IOException {
        if(via.isEmpty() || via.size()>32 || new HashSet<>(via).size()!=via.size()
                || via.contains(from)||via.contains(to))throw new IOException("Ambiguous/repeated via-way members");
        var compiler=new ViaWayCompiler(graph,via,to);
        for(int edge=0;edge<graph.edgeCount();edge++)if(graph.osmWayId(edge)==from) {
            var path=new ArrayList<Integer>();path.add(edge);
            compiler.walk(path,0,false,new HashSet<>());
        }
        if(compiler.paths.isEmpty())throw new IOException("No traversable via-way sequence");
        return new RoutingGraph.SequenceRestriction(only,List.copyOf(compiler.paths));
    }
    private void walk(ArrayList<Integer> path,int stage,boolean traversed,Set<Integer> used) throws IOException {
        if(++visits>100_000 || path.size()>127)throw new IOException("Via-way expansion limit exceeded");
        int incoming=path.getLast(),node=graph.target(incoming);
        for(int outgoing=graph.edgeStart(node);outgoing<graph.edgeEnd(node);outgoing++) {
            // Compile geometry independently: an existing node turn ban may make this
            // restriction redundant (no) or impossible to complete (only). Keep both
            // rules; the router enforces their intersection when traversing edges.
            long way=graph.osmWayId(outgoing);
            if(traversed && stage==via.size()-1 && way==to) {
                var complete=new ArrayList<>(path);complete.add(outgoing);paths.add(List.copyOf(complete));
                if(paths.size()>256)throw new IOException("Too many via-way alternatives");
            }
            if(used.contains(outgoing))continue;
            int nextStage=way==via.get(stage)?stage:
                    traversed && stage+1<via.size() && way==via.get(stage+1)?stage+1:-1;
            if(nextStage<0)continue;
            // Do not enumerate reversing back on the same physical segment as a via geometry.
            if(graph.osmWayId(incoming)==way && reverses(incoming,outgoing))continue;
            path.add(outgoing);used.add(outgoing);
            walk(path,nextStage,true,used);
            used.remove(outgoing);path.removeLast();
        }
    }
    private boolean reverses(int incoming,int outgoing) {
        int target=graph.target(outgoing);
        return incoming>=graph.edgeStart(target) && incoming<graph.edgeEnd(target);
    }
}
