package com.lsk.routing.core.graph;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CoordinateRouterTest {
    private RoutingGraph line(boolean bidirectional) {
        return new RoutingGraph(new long[]{1,2},new double[]{37,37},new double[]{127,127.02},
                new int[]{0,1,bidirectional?2:1},bidirectional?new int[]{1,0}:new int[]{1},
                bidirectional?new long[]{10,10}:new long[]{10},bidirectional?new double[]{1000,1000}:new double[]{1000},new long[0]);
    }
    @Test void projectsToLongSegmentAndUsesPartialDistance() {
        var result=new CoordinateRouter(line(false)).route(127.005,37.0001,127.015,37).orElseThrow();
        assertEquals(500,result.distanceMetres(),1e-6);
        assertEquals(127.005,result.geometry().getFirst().longitude(),1e-8);
        assertEquals(37,result.geometry().getFirst().latitude(),1e-8);
        assertTrue(result.start().distanceMetres()>10);assertTrue(result.start().distanceMetres()<12);
        assertEquals(127.015,result.geometry().getLast().longitude(),1e-8);
    }
    @Test void respectsOneWayAndHandlesReverseTwin() {
        assertTrue(new CoordinateRouter(line(false)).route(127.015,37,127.005,37).isEmpty());
        assertEquals(500,new CoordinateRouter(line(true)).route(127.015,37,127.005,37).orElseThrow().distanceMetres(),1e-6);
    }
    @Test void samePointAndEndpointsHaveNoPhantomTurnConstraint() {
        var router=new CoordinateRouter(line(false));
        assertEquals(0,router.route(127.01,37,127.01,37).orElseThrow().distanceMetres());
        assertEquals(2,router.route(127.01,37,127.01,37).orElseThrow().geometry().size());
        assertEquals(1000,router.route(127,37,127.02,37).orElseThrow().distanceMetres(),1e-6);
        assertEquals(250,router.route(127.015,37,127.02,37).orElseThrow().distanceMetres(),1e-6);
    }
    @Test void preservesTurnBanWhenBothIncomingAndOutgoingAreSplit() {
        var g=new RoutingGraph(new long[]{1,2,3,4},new double[]{37,37,37.01,37.01},
                new double[]{127,127.01,127.01,127},new int[]{0,2,4,4,5},
                new int[]{1,3,0,2,1},new long[]{10,20,10,30,40},new double[]{100,100,100,100,150},
                new long[]{RoutingGraph.turnKey(0,3)});
        var result=new CoordinateRouter(g).route(127.005,37,127.01,37.005).orElseThrow();
        assertEquals(350,result.distanceMetres(),1e-5);
        assertEquals(5,result.geometry().size());
        assertEquals(37.01,result.geometry().get(2).latitude(),1e-8);
    }
    @Test void refusesOutsideAreaAndInvalidCoordinates() {
        var router=new CoordinateRouter(line(false));
        assertThrows(CoordinateRouter.OutsideGraphException.class,()->router.route(127.01,37.001,127.015,37));
        assertThrows(IllegalArgumentException.class,()->router.route(Double.NaN,37,127,37));
        assertThrows(IllegalArgumentException.class,()->router.route(127,91,127,37));
    }
    @Test void keepsDistinctCrossingRoadsDisconnected() {
        var graph=new RoutingGraph(new long[]{1,2,3,4},new double[]{37,37,36.99,37.01},
                new double[]{126.99,127.01,127,127},new int[]{0,1,1,2,2},new int[]{1,3},
                new long[]{10,20},new double[]{100,100},new long[0]);
        assertTrue(new CoordinateRouter(graph).route(126.995,37,127,37.005).isEmpty());
    }
    @Test void spatialTreeFindsSegmentsAcrossLeafBoundaries() {
        int n=20;long[] ids=new long[n],ways=new long[n-1];double[] lat=new double[n],lon=new double[n],cost=new double[n-1];
        int[] offsets=new int[n+1],targets=new int[n-1];
        for(int i=0;i<n;i++){ids[i]=i+1;lat[i]=37;lon[i]=127+i*0.001;offsets[i]=Math.min(i,n-1);}
        offsets[n]=n-1;
        for(int i=0;i<n-1;i++){targets[i]=i+1;ways[i]=i;cost[i]=100;}
        var router=new CoordinateRouter(new RoutingGraph(ids,lat,lon,offsets,targets,ways,cost,new long[0]));
        assertEquals(1800,router.route(127.0005,37,127.0185,37).orElseThrow().distanceMetres(),1e-5);
    }
    @Test void sharedIndexSupportsIndependentConcurrentQueries() {
        var router=new CoordinateRouter(line(true));
        var requests=new java.util.ArrayList<java.util.concurrent.CompletableFuture<Double>>();
        for(int i=0;i<12;i++) {
            final boolean forward=i%2==0;
            requests.add(java.util.concurrent.CompletableFuture.supplyAsync(()->router.route(
                    forward?127.005:127.015,37,forward?127.015:127.005,37).orElseThrow().distanceMetres()));
        }
        for(var request:requests)assertEquals(500,request.join(),1e-5);
    }
}
