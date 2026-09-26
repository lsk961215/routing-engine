package com.lsk.routing.core.graph;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AlgorithmComparisonTest {
    private void compare(CoordinateRouter router,double[] a,double[] b) {
        var reference=router.route(a[0],a[1],b[0],b[1]);
        var comparison=router.compare(a[0],a[1],b[0],b[1]);
        assertEquals(List.of(RoutingAlgorithm.DIJKSTRA,RoutingAlgorithm.ASTAR),
                comparison.results().stream().map(CoordinateRouter.SearchResult::algorithm).toList());
        for(var algorithm:RoutingAlgorithm.values()) {
            var measured=router.search(a[0],a[1],b[0],b[1],algorithm);
            var together=comparison.results().stream().filter(r->r.algorithm()==algorithm).findFirst().orElseThrow();
            assertEquals(measured.route(),together.route());
            assertEquals(measured.expandedStates(),together.expandedStates());
            assertEquals(comparison.snapMillis(),together.snapMillis());
            assertTrue(together.searchMillis()>=0);
            together.route().ifPresent(route->{
                assertEquals(comparison.start(),route.start());
                assertEquals(comparison.end(),route.end());
            });
            assertEquals(reference.isPresent(),measured.route().isPresent());
            if(reference.isPresent()) {
                var actual=measured.route().orElseThrow();
                assertEquals(reference.get().distanceMetres(),actual.distanceMetres(),1e-6);
                assertEquals(reference.get().start(),actual.start());assertEquals(reference.get().end(),actual.end());
            }
            assertTrue(measured.snapMillis()>=0 && measured.searchMillis()>=0 && measured.expandedStates()>=0);
        }
    }
    @Test void agreesWithExistingSearchOnRandomPartialEdgesWithDirectionAndTurnBans() {
        var g=RoutingTestGraphs.grid(9,true);var router=new CoordinateRouter(g);var random=new Random(473);
        for(int i=0;i<100;i++)compare(router,RoutingTestGraphs.point(g,random.nextInt(g.edgeCount()),random.nextDouble()),RoutingTestGraphs.point(g,random.nextInt(g.edgeCount()),random.nextDouble()));
    }
    @Test void preservesViaNoOnlyAndCombinedNodeRestrictions() throws Exception {
        for(String name:List.of("via-no-node-ban","via-only-node-ban","via-no","via-only")) {
            var g=new GraphBuilder().build(Path.of(getClass().getResource("/osm/"+name+".osm.pbf").toURI()));
            var router=new CoordinateRouter(g);
            for(int a=0;a<g.nodeCount();a++)for(int b=0;b<g.nodeCount();b++)
                compare(router,new double[]{g.longitude(a),g.latitude(a)},new double[]{g.longitude(b),g.latitude(b)});
            for(int a=0;a<g.edgeCount();a++)for(int b=0;b<g.edgeCount();b++)
                compare(router,RoutingTestGraphs.point(g,a,.25),RoutingTestGraphs.point(g,b,.75));
        }
    }
    @Test void scaledHeuristicHandlesCostsSmallerThanGeographicDistanceAndZeroCostEdges() {
        for(double cost:new double[]{0,1,100}) {
            var g=new RoutingGraph(new long[]{1,2,3},new double[]{37,37,37},new double[]{127,127.01,127.02},
                    new int[]{0,1,2,2},new int[]{1,2},new long[]{10,20},new double[]{cost,cost},new long[0]);
            var r=new CoordinateRouter(g);
            compare(r,new double[]{127.002,37},new double[]{127.018,37});
            compare(r,new double[]{127.018,37},new double[]{127.002,37});
            compare(r,new double[]{127.002,37},new double[]{127.002,37});
        }
    }
    @Test void sharedRouterSupportsConcurrentStrategies() throws Exception {
        var g=RoutingTestGraphs.grid(8,true);var r=new CoordinateRouter(g);
        try(var pool=Executors.newFixedThreadPool(4)) {
            var tasks=new ArrayList<Future<?>>();
            for(int i=0;i<20;i++){int a=i;tasks.add(pool.submit(()->compare(r,RoutingTestGraphs.point(g,a,.2),RoutingTestGraphs.point(g,g.edgeCount()-a-1,.8))));}
            for(var task:tasks)task.get();
        }
    }
    @Test void comparisonCanReverseExecutionOrderWithoutChangingResults() {
        var g=RoutingTestGraphs.grid(8,true);var router=new CoordinateRouter(g);
        var start=RoutingTestGraphs.point(g,1,.25);var end=RoutingTestGraphs.point(g,g.edgeCount()-2,.75);
        var normal=router.compare(start[0],start[1],end[0],end[1]);
        var reversed=router.compare(start[0],start[1],end[0],end[1],RoutingAlgorithm.ASTAR);
        assertEquals(RoutingAlgorithm.ASTAR,reversed.results().getFirst().algorithm());
        assertEquals(normal.start(),reversed.start());assertEquals(normal.end(),reversed.end());
        for(int i=0;i<2;i++) {
            assertEquals(normal.results().get(i).route(),reversed.results().get(1-i).route());
            assertEquals(normal.results().get(i).expandedStates(),reversed.results().get(1-i).expandedStates());
        }
    }
}
