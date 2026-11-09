package com.lsk.routing.core.graph;

import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LandmarkIndexTest {
    @Test void directedBoundsAreFiniteAndAdmissibleAgainstIndependentAllPairsOracle() {
        for(int seed=0;seed<12;seed++) {
            var random=new Random(seed);int n=18;
            int[] offsets=new int[n+1];var targets=new ArrayList<Integer>();var costs=new ArrayList<Double>();
            long[] ids=new long[n];double[] lat=new double[n],lon=new double[n];
            for(int from=0;from<n;from++) {
                ids[from]=from+1;lon[from]=127+from*.001;lat[from]=37;
                // Two disconnected components, directed edges, zero and asymmetric costs.
                for(int to=0;to<n;to++)if(from!=to && from/9==to/9 && random.nextInt(4)==0) {
                    targets.add(to);costs.add(random.nextInt(5)==0?0:random.nextDouble()*1000);
                }
                offsets[from+1]=targets.size();
            }
            var g=new RoutingGraph(ids,lat,lon,offsets,targets.stream().mapToInt(Integer::intValue).toArray(),
                    new long[targets.size()],costs.stream().mapToDouble(Double::doubleValue).toArray(),new long[0]);
            double[][] exact=new double[n][n];
            for(int v=0;v<n;v++) {
                Arrays.fill(exact[v],Double.POSITIVE_INFINITY);exact[v][v]=0;
                for(int e=g.edgeStart(v);e<g.edgeEnd(v);e++)exact[v][g.target(e)]=g.distanceMetres(e);
            }
            for(int k=0;k<n;k++)for(int i=0;i<n;i++)for(int j=0;j<n;j++)exact[i][j]=Math.min(exact[i][j],exact[i][k]+exact[k][j]);
            var index=new LandmarkIndex(g,8);var repeated=new LandmarkIndex(g,8);
            for(int i=0;i<n;i++)for(int j=0;j<n;j++) {
                double bound=index.lowerBound(i,j);
                assertTrue(Double.isFinite(bound) && bound>=0);
                assertTrue(bound<=exact[i][j]+1e-8,"overestimate "+seed+" "+i+" -> "+j);
                assertEquals(bound,repeated.lowerBound(i,j));
                if(i==j)assertEquals(0,bound);
            }
        }
    }
    @Test void bothDirectedInequalitiesAndMissingDistancesAreHandled() {
        var g=new RoutingGraph(new long[]{1,2,3,4,5},new double[5],new double[5],
                new int[]{0,1,2,3,3,3},new int[]{1,2,3},new long[]{1,2,3},new double[]{10,20,30},new long[0]);
        // Node 1 is the first landmark (degree 2, stable smallest-node tie break).
        var index=new LandmarkIndex(g,1);
        assertEquals(10,index.lowerBound(0,1),1e-7); // d(v,L)-d(t,L)
        assertEquals(30,index.lowerBound(2,3),1e-7); // d(L,t)-d(L,v)
        assertEquals(0,index.lowerBound(4,3));
        assertEquals(0,index.lowerBound(3,0));
        assertEquals(2L*5*8,index.distanceTableBytes());
    }
    @Test void oneWayFringesDoNotConsumeLandmarksNeededByTheStronglyConnectedRoadNetwork() {
        var g=new RoutingGraph(new long[]{1,2,3,4,5,6,7},new double[7],new double[7],
                new int[]{0,1,2,3,4,6,7,7},new int[]{1,2,3,0,0,5,6},new long[7],
                new double[]{10,10,10,10,1000,1000,1000},new long[0]);
        var index=new LandmarkIndex(g,8);
        assertEquals(4,index.count());
        assertEquals(20,index.lowerBound(0,2),1e-7);
        assertEquals(20,index.lowerBound(2,0),1e-7);
        assertEquals(0,index.lowerBound(4,6));
    }
    @Test void emptyGraphsAndOversizedLandmarkBudgetsAreSafe() {
        var empty=new RoutingGraph(new long[0],new double[0],new double[0],new int[]{0},new int[0],new long[0],new double[0],new long[0]);
        assertEquals(0,new CoordinateRouter(empty).prepareLandmarks().count());
        assertEquals(0,new CoordinateRouter(empty).prepareLandmarks().distanceTableBytes());
        var g=RoutingTestGraphs.grid(2,false);
        assertEquals(4,new CoordinateRouter(g,32).prepareLandmarks().count());
        assertThrows(IllegalArgumentException.class,()->new CoordinateRouter(g,0));
        assertThrows(IllegalArgumentException.class,()->new CoordinateRouter(g,33));
        var router=new CoordinateRouter(g);
        assertThrows(IllegalArgumentException.class,()->router.compare(127,37,127.001,37.001,RoutingAlgorithm.ALT,RoutingAlgorithm.ALT));
    }
    @Test void altReducesExpansionAndPreservesCostOnWeightedGrid() {
        var g=RoutingTestGraphs.grid(20,false);var router=new CoordinateRouter(g);
        var d=router.search(127,37,127.019,37.019,RoutingAlgorithm.DIJKSTRA);
        var a=router.search(127,37,127.019,37.019,RoutingAlgorithm.ALT);
        assertEquals(d.route().orElseThrow().distanceMetres(),a.route().orElseThrow().distanceMetres(),1e-7);
        assertTrue(a.expandedStates()<d.expandedStates()/2,"ALT should prune most of this grid");
        var info=router.prepareLandmarks();
        assertEquals(8,info.count());assertEquals(2L*8*g.nodeCount()*8,info.distanceTableBytes());
        assertTrue(info.preprocessingMillis()>=0);
        var reversed=router.compare(127,37,127.019,37.019,RoutingAlgorithm.ALT);
        assertEquals(List.of(RoutingAlgorithm.ALT,RoutingAlgorithm.DIJKSTRA),reversed.results().stream().map(CoordinateRouter.SearchResult::algorithm).toList());
        assertEquals(a.route(),reversed.results().getFirst().route());
    }
    @Test void concurrentColdStartPublishesOneIndexAndKeepsQueryStateIndependent() throws Exception {
        var g=RoutingTestGraphs.grid(10,true);var router=new CoordinateRouter(g);
        try(var executor=Executors.newFixedThreadPool(6)) {
            var results=new ArrayList<Future<CoordinateRouter.LandmarkMetrics>>();
            for(int i=0;i<12;i++)results.add(executor.submit(()->{
                var pair=router.compare(127,37,127.009,37.009,RoutingAlgorithm.ALT,RoutingAlgorithm.DIJKSTRA);
                var a=pair.results().get(0).route();var d=pair.results().get(1).route();
                assertEquals(d.isPresent(),a.isPresent());
                if(d.isPresent())assertEquals(d.get().distanceMetres(),a.orElseThrow().distanceMetres(),1e-7);
                return router.prepareLandmarks();
            }));
            var info=results.getFirst().get();
            for(var result:results)assertEquals(info,result.get());
        }
    }
}
