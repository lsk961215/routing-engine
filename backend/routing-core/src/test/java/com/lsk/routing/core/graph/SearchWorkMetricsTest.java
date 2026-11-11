package com.lsk.routing.core.graph;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SearchWorkMetricsTest {
    // 0 → 1 → {2, 3}. The duplicate seed improves a queued label, leaving an obsolete entry.
    private final RoutingGraph graph=new RoutingGraph(new long[]{1,2,3,4},
            new double[]{37,37.001,37.002,37.001},new double[]{127,127,127,127.001},
            new int[]{0,1,3,3,3},new int[]{1,2,3},new long[]{1,2,3},new double[]{1,1,1},new long[0]);
    private final List<HistorySearch.Seed> seeds=List.of(new HistorySearch.Seed(0,10),new HistorySearch.Seed(0,1));

    @Test void countsCandidatesAndPhysicalQueueIncludingObsoleteLabelsForUnreachableTarget() {
        var metrics=new HistorySearch.Metrics();
        assertTrue(HistorySearch.route(graph,seeds,-1,Map.of(),Double.POSITIVE_INFINITY,e->true,n->0,metrics).isEmpty());
        assertEquals(3,metrics.expandedStates);
        assertEquals(2,metrics.inspectedEdges);
        assertEquals(3,metrics.maxQueueSize);
    }
    @Test void rejectedCandidatesCountButNeverEnterQueue() {
        var metrics=new HistorySearch.Metrics();
        HistorySearch.route(graph,seeds,-1,Map.of(),Double.POSITIVE_INFINITY,e->e!=2,n->0,metrics);
        assertEquals(2,metrics.expandedStates);
        assertEquals(2,metrics.inspectedEdges);
        assertEquals(2,metrics.maxQueueSize);
    }
    @Test void directCandidateStillCountsSeedQueueButNoOutgoingInspections() {
        var metrics=new HistorySearch.Metrics();
        assertEquals(.5,HistorySearch.route(graph,seeds,-1,Map.of(),.5,e->true,n->0,metrics).orElseThrow().distance());
        assertEquals(0,metrics.expandedStates);
        assertEquals(0,metrics.inspectedEdges);
        assertEquals(2,metrics.maxQueueSize);
    }
    @Test void identicalCoordinatesBypassQueueForEveryAlgorithm() {
        var router=new CoordinateRouter(graph);
        for(var algorithm:RoutingAlgorithm.values()) {
            var result=router.search(127,37,127,37,algorithm);
            assertEquals(0,result.route().orElseThrow().distanceMetres());
            assertEquals(0,result.inspectedEdges());assertEquals(0,result.maxQueueSize());
        }
    }
}
