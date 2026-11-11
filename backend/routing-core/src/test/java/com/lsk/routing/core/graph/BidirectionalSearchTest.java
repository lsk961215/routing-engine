package com.lsk.routing.core.graph;

import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BidirectionalSearchTest {
    private static int[] sources(RoutingGraph g) {
        int[] result=new int[g.edgeCount()];
        for(int n=0;n<g.nodeCount();n++)for(int e=g.edgeStart(n);e<g.edgeEnd(n);e++)result[e]=n;
        return result;
    }
    private HistorySearch.Result compare(RoutingGraph g,List<HistorySearch.Seed> seeds,int end,Map<Integer,Double> partial,double direct) {
        var expected=HistorySearch.route(g,seeds,end,partial,direct);
        var actual=BidirectionalSearch.route(g,new ReverseSearchIndex(g),sources(g),seeds,end,partial,direct,e->true,new HistorySearch.Metrics());
        assertEquals(expected.isPresent(),actual.isPresent());
        if(actual.isEmpty())return null;
        var route=actual.orElseThrow();assertEquals(expected.orElseThrow().distance(),route.distance(),1e-8);
        if(route.edges().isEmpty()){assertEquals(direct,route.distance());return route;}
        int first=route.edges().getFirst();
        double cost=seeds.stream().filter(s->s.edge()==first).mapToDouble(HistorySearch.Seed::cost).min().orElseThrow();
        int history=g.restrictionState(0,first),last=first;assertTrue(history>=0);
        for(int i=1;i<route.edges().size();i++) {
            int edge=route.edges().get(i);assertTrue(g.turnAllowed(last,edge));
            history=g.restrictionState(history,edge);assertTrue(history>=0,"Invalid reconstructed restriction history");
            cost+=g.distanceMetres(edge);last=edge;
        }
        if(end>=0){assertEquals(end,g.target(last));assertEquals(cost,route.distance(),1e-8);}
        else {
            boolean valid=false;
            for(var terminal:partial.entrySet())if(g.turnAllowed(last,terminal.getKey()) && g.restrictionState(history,terminal.getKey())>=0
                    && Math.abs(cost+terminal.getValue()-route.distance())<1e-8)valid=true;
            assertTrue(valid,"Invalid partial destination transition");
        }
        return route;
    }
    @Test void firstMeetingIsNotNecessarilyShortest() {
        var g=new RoutingGraph(new long[]{1,2,3,4},new double[4],new double[4],new int[]{0,2,3,4,4},
                new int[]{1,2,3,3},new long[]{1,2,3,4},new double[]{1,4,10,4},new long[0]);
        var result=compare(g,List.of(new HistorySearch.Seed(0,1),new HistorySearch.Seed(1,4)),3,Map.of(),Double.POSITIVE_INFINITY);
        assertEquals(8,result.distance());assertEquals(List.of(1,3),result.edges());
    }
    @Test void randomizedOverlappingOnlyAndNoRulesAgreeIncludingPartialEndpointsAndZeroCosts() {
        var random=new Random(67241);
        for(int run=0;run<60;run++) {
            int n=8;var targets=new ArrayList<Integer>();int[] offsets=new int[n+1];
            for(int v=0;v<n;v++) {
                for(int w=0;w<n;w++)if(w==(v+1)%n || random.nextInt(5)==0)targets.add(w);
                offsets[v+1]=targets.size();
            }
            int m=targets.size();long[] ids=new long[n],ways=new long[m];double[] costs=new double[m];
            for(int i=0;i<n;i++)ids[i]=i;for(int i=0;i<m;i++){ways[i]=i;costs[i]=random.nextInt(10);}
            var rules=new ArrayList<RoutingGraph.SequenceRestriction>();
            for(int r=0;r<8;r++) {
                var path=new ArrayList<Integer>();int edge=random.nextInt(m);path.add(edge);
                for(int k=0;k<2+random.nextInt(3);k++) {
                    int node=targets.get(edge);edge=offsets[node]+random.nextInt(offsets[node+1]-offsets[node]);path.add(edge);
                }
                var paths=new ArrayList<List<Integer>>();paths.add(path);
                // Two permitted continuations for one only-rule; overlaps with other rules remain possible.
                if(r%2==0) {
                    var alternate=new ArrayList<>(path);int node=targets.get(path.get(path.size()-2));
                    alternate.set(alternate.size()-1,offsets[node]+random.nextInt(offsets[node+1]-offsets[node]));paths.add(alternate);
                }
                rules.add(new RoutingGraph.SequenceRestriction(r%2==0,paths));
            }
            var bans=new ArrayList<Long>();
            for(int e=0;e<m;e++)for(int out=offsets[targets.get(e)];out<offsets[targets.get(e)+1];out++)
                if(random.nextInt(12)==0)bans.add(RoutingGraph.turnKey(e,out));
            var g=new RoutingGraph(ids,new double[n],new double[n],offsets,targets.stream().mapToInt(Integer::intValue).toArray(),
                    ways,costs,bans.stream().mapToLong(Long::longValue).toArray(),rules);
            for(int q=0;q<20;q++) {
                int start=random.nextInt(m),terminal=random.nextInt(m);
                var seeds=List.of(new HistorySearch.Seed(start,costs[start]*.3));
                compare(g,seeds,random.nextInt(n),Map.of(),Double.POSITIVE_INFINITY);
                compare(g,seeds,-1,Map.of(terminal,costs[terminal]*.7),Double.POSITIVE_INFINITY);
            }
        }
    }
    @Test void reverseAdjacencyRetainsEveryOriginalDirectedEdge() {
        var g=RoutingTestGraphs.grid(4,true);var reverse=new ReverseSearchIndex(g);
        for(int e=0;e<g.edgeCount();e++) {
            int count=0;for(int p=reverse.start(g.target(e));p<reverse.end(g.target(e));p++)if(reverse.edge(p)==e)count++;
            assertEquals(1,count);
        }
    }
    @Test void concurrentFirstUseSharesReverseIndexAndReturnsIdenticalPaths() throws Exception {
        var g=RoutingTestGraphs.grid(10,true);var router=new CoordinateRouter(g);
        var start=RoutingTestGraphs.point(g,1,.2);var end=RoutingTestGraphs.point(g,g.edgeCount()-2,.7);
        try(var pool=Executors.newFixedThreadPool(4)) {
            var tasks=new ArrayList<Future<CoordinateRouter.SearchResult>>();
            for(int i=0;i<12;i++)tasks.add(pool.submit(()->router.search(start[0],start[1],end[0],end[1],RoutingAlgorithm.BIDIRECTIONAL)));
            var first=tasks.getFirst().get();
            for(var task:tasks){var next=task.get();assertEquals(first.route(),next.route());assertEquals(first.expandedStates(),next.expandedStates());}
        }
        assertEquals(router.prepareBidirectional(),router.prepareBidirectional());
        assertTrue(router.prepareBidirectional().arrayPayloadBytes()>0);
    }
}
