package com.lsk.routing.core.graph;

import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ExplorationTraceTest {
    private record Visit(double longitude,double latitude,long ordinal) {}
    private void verify(RoutingGraph graph,double[] start,double[] end) {
        var router=new CoordinateRouter(graph);
        for(var algorithm:RoutingAlgorithm.values()) {
            var visits=new ArrayList<Visit>();
            var trace=router.trace(start[0],start[1],end[0],end[1],algorithm,
                    (lon,lat,ordinal)->visits.add(new Visit(lon,lat,ordinal)));
            var normal=router.search(start[0],start[1],end[0],end[1],algorithm);
            assertEquals(normal.route(),trace.route());
            assertEquals(normal.expandedStates(),trace.expandedStates());
            assertEquals(visits.size(),trace.expandedStates());
            var nodes=new HashSet<List<Double>>();
            for(int i=0;i<graph.nodeCount();i++)nodes.add(List.of(graph.longitude(i),graph.latitude(i)));
            for(int i=0;i<visits.size();i++) {
                assertEquals(i+1,visits.get(i).ordinal());
                assertTrue(nodes.contains(List.of(visits.get(i).longitude(),visits.get(i).latitude())));
            }
            var repeated=new ArrayList<Visit>();
            router.trace(start[0],start[1],end[0],end[1],algorithm,
                    (lon,lat,ordinal)->repeated.add(new Visit(lon,lat,ordinal)));
            assertEquals(visits,repeated,"Expansion order must be deterministic");
        }
    }
    @Test void tracesPreservePartialEdgeRoutesAndTurnRestrictions() {
        var graph=RoutingTestGraphs.grid(7,true);var random=new Random(81);
        for(int i=0;i<20;i++)verify(graph,RoutingTestGraphs.point(graph,random.nextInt(graph.edgeCount()),.25),
                RoutingTestGraphs.point(graph,random.nextInt(graph.edgeCount()),.75));
    }
    @Test void tracesPreserveViaWayHistoryIncludingRepeatedNodeExpansions() throws Exception {
        var graph=new GraphBuilder().build(Path.of(getClass().getResource("/osm/via-no-node-ban.osm.pbf").toURI()));
        for(int a=0;a<graph.nodeCount();a++)for(int b=0;b<graph.nodeCount();b++)
            verify(graph,new double[]{graph.longitude(a),graph.latitude(a)},new double[]{graph.longitude(b),graph.latitude(b)});
    }
    @Test void samePointHasNoExpansionsAndUnreachableDestinationStillHasATrace() {
        var graph=new RoutingGraph(new long[]{1,2,3},new double[]{37,37,37},new double[]{127,127.01,127.02},
                new int[]{0,1,2,2},new int[]{1,2},new long[]{10,20},new double[]{100,100},new long[0]);
        var router=new CoordinateRouter(graph);
        var zero=router.trace(127.005,37,127.005,37,RoutingAlgorithm.DIJKSTRA,(lon,lat,n)->fail());
        assertEquals(0,zero.expandedStates());
        assertEquals(0,zero.route().orElseThrow().distanceMetres());
        var ordinals=new ArrayList<Long>();
        var unreachable=router.trace(127.015,37,127.005,37,RoutingAlgorithm.DIJKSTRA,(lon,lat,n)->ordinals.add(n));
        assertTrue(unreachable.route().isEmpty());
        assertEquals(List.of(1L),ordinals);
        verify(graph,new double[]{127.015,37},new double[]{127.005,37});
    }
}
