package com.lsk.routing.core.graph;

import org.junit.jupiter.api.Test;
import java.util.Random;
import static org.junit.jupiter.api.Assertions.*;

class CoordinateRouterComparisonTest {
    @Test void matchesFrozenSplitGraphOracleOnDirectedRestrictedGrid() {
        var graph=RoutingTestGraphs.grid(8,true);
        var optimized=new CoordinateRouter(graph);var legacy=new LegacyCoordinateRouter(graph);
        var random=new Random(4201);
        for(int i=0;i<250;i++) {
            int a=random.nextInt(graph.edgeCount()),b=i%5==0?a:random.nextInt(graph.edgeCount());
            double[] start=RoutingTestGraphs.point(graph,a,i%3==0?0:.2+random.nextDouble()*.6);
            double[] end=RoutingTestGraphs.point(graph,b,i%7==0?1:.2+random.nextDouble()*.6);
            var actual=optimized.route(start[0],start[1],end[0],end[1]);
            var expected=legacy.route(start[0],start[1],end[0],end[1]);
            assertEquals(expected.isPresent(),actual.isPresent(),"reachability case "+i);
            if(actual.isPresent()) {
                assertEquals(expected.get().distanceMetres(),actual.get().distanceMetres(),1e-7,"distance case "+i);
                assertEquals(expected.get().start().point().longitude(),actual.get().start().point().longitude(),1e-10);
                assertEquals(expected.get().end().point().latitude(),actual.get().end().point().latitude(),1e-10);
            }
        }
    }
    @Test void canReachEarlierPointOnOneWayOnlyByCompletingLegalLoop() {
        var graph=new RoutingGraph(new long[]{1,2,3},new double[]{37,37,37.01},
                new double[]{127,127.01,127},new int[]{0,1,2,3},new int[]{1,2,0},
                new long[]{10,20,30},new double[]{100,100,100},new long[0]);
        var route=new CoordinateRouter(graph).route(127.0075,37,127.0025,37).orElseThrow();
        assertEquals(250,route.distanceMetres(),1e-7);
        assertEquals(5,route.geometry().size());
        var blocked=new RoutingGraph(new long[]{1,2,3},new double[]{37,37,37.01},
                new double[]{127,127.01,127},new int[]{0,1,2,3},new int[]{1,2,0},
                new long[]{10,20,30},new double[]{100,100,100},new long[]{RoutingGraph.turnKey(2,0)});
        assertTrue(new CoordinateRouter(blocked).route(127.0075,37,127.0025,37).isEmpty());
    }
    @Test void handlesZeroCostCycleAndDestinationEndpoint() {
        var graph=new RoutingGraph(new long[]{1,2,3},new double[]{37,37,37.01},
                new double[]{127,127.01,127},new int[]{0,1,2,3},new int[]{1,2,0},
                new long[]{10,20,30},new double[]{0,0,0},new long[0]);
        assertEquals(0,new CoordinateRouter(graph).route(127.0075,37,127.0025,37).orElseThrow().distanceMetres());
        assertEquals(0,new CoordinateRouter(graph).route(127.0075,37,127,37.01).orElseThrow().distanceMetres());
    }
}
