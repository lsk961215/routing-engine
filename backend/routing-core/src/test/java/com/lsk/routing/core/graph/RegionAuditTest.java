package com.lsk.routing.core.graph;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class RegionAuditTest {
    @Test void redundantNoPreservesDirectionButOnlyStillFails() throws Exception {
        var no=java.nio.file.Path.of(getClass().getResource("/osm/via-no-wrong-direction.osm.pbf").toURI());
        var analysis=new com.lsk.routing.core.osm.CarDataAnalysis().read(no);
        assertEquals(List.of(100L),analysis.directionBlockedNoRestrictions());
        var graph=new GraphBuilder().build(no);
        assertTrue(graph.sequenceRestrictions().isEmpty());
        assertTrue(new DijkstraRouter(graph).route(1,3).isEmpty());
        assertTrue(new DijkstraRouter(graph).route(3,1).isPresent());
        var only=java.nio.file.Path.of(getClass().getResource("/osm/via-only-wrong-direction.osm.pbf").toURI());
        assertThrows(java.io.IOException.class,()->new GraphBuilder().build(only));
    }
    @Test void builderPreservesRedundantNoAndConflictingOnlyRules() throws Exception {
        for (String mode : List.of("no", "only")) {
            var file=java.nio.file.Path.of(getClass().getResource("/osm/via-"+mode+"-node-ban.osm.pbf").toURI());
            var graph=new GraphBuilder().build(file);
            assertEquals(1,graph.sequenceRestrictions().size());
            assertTrue(graph.forbiddenTurnCount()>0);
            assertTrue(new DijkstraRouter(graph).route(0,5).isEmpty());
            if (mode.equals("only")) {
                assertTrue(new DijkstraRouter(graph).route(0,2).isPresent());
                assertTrue(new DijkstraRouter(graph).route(0,6).isEmpty());
            }
            assertTrue(RegionAudit.verify(graph)>0);
        }
    }
    @Test void referenceSearchCombinesMultipleViaRulesAndNodeTurns() {
        // 0->1->2->3, plus 1->3; only requires the long path, no forbids its completion.
        var graph=new RoutingGraph(new long[]{1,2,3,4},new double[4],new double[4],
                new int[]{0,1,3,4,4},new int[]{1,2,3,3},new long[]{10,20,30,40},
                new double[]{1,1,1,1},new long[]{RoutingGraph.turnKey(0,2)},List.of(
                new RoutingGraph.SequenceRestriction(true,List.of(List.of(0,1,3))),
                new RoutingGraph.SequenceRestriction(false,List.of(List.of(0,1,3)))));
        assertEquals(Double.POSITIVE_INFINITY,ViaWayAudit.referenceDistance(graph,0,3));
        assertEquals(1,ViaWayAudit.referenceDistance(graph,1,3));
        assertTrue(RegionAudit.verify(graph)>0);
    }
}
