package com.lsk.routing.core.graph;

import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ViaWayAuditTest {
    @Test void auditChecksNoAndOnlyFixturesAgainstIndependentSearch() throws Exception {
        for (String name : List.of("via-no", "via-only")) {
            var graph = new GraphBuilder().build(Path.of(getClass().getResource("/osm/"+name+".osm.pbf").toURI()));
            var checks = ViaWayAudit.verify(graph);
            assertTrue(checks.transitions() > 10);
            assertTrue(checks.routes() >= 5);
            assertEquals(name.equals("via-no") ? Double.POSITIVE_INFINITY :
                    new DijkstraRouter(graph).route(0, 5).orElseThrow().distanceMetres(),
                    ViaWayAudit.referenceDistance(graph, 0, 5));
        }
    }
    @Test void historyOracleDistinguishesExitsAndFreshStarts() {
        var path = List.of(0, 1, 2, 3);
        var no = new RoutingGraph.SequenceRestriction(false, List.of(path));
        var only = new RoutingGraph.SequenceRestriction(true, List.of(path));
        assertFalse(ViaWayAudit.allowed(List.of(0, 1, 2), 3, no));
        assertTrue(ViaWayAudit.allowed(List.of(1, 2), 3, no));
        assertTrue(ViaWayAudit.allowed(List.of(0, 1), 9, no));
        assertFalse(ViaWayAudit.allowed(List.of(0, 1), 9, only));
        assertTrue(ViaWayAudit.allowed(List.of(0, 1), 2, only));
    }
    @Test void builderReportsRelationAndMembersForOverlappingRoles() throws Exception {
        for (String role : List.of("from", "to")) {
            var input = Path.of(getClass().getResource("/osm/via-overlap-"+role+".osm.pbf").toURI());
            var error = assertThrows(java.io.IOException.class, () -> new GraphBuilder().build(input));
            assertTrue(error.getMessage().contains("Restriction 100 (from="));
            assertTrue(error.getMessage().contains("via="));
            assertTrue(error.getMessage().contains("to="));
            assertTrue(error.getMessage().contains("Ambiguous/repeated via-way members"));
        }
    }
    @Test void compilerRejectsViaEqualToFromOrToExplicitly() throws Exception {
        var graph = new GraphBuilder().build(Path.of(getClass().getResource("/osm/via-no.osm.pbf").toURI()));
        for (var via : List.of(List.of(10L), List.of(30L), List.of(20L, 20L))) {
            var error = assertThrows(java.io.IOException.class,
                    () -> ViaWayCompiler.compile(graph, 10, via, 30, false));
            assertEquals("Ambiguous/repeated via-way members", error.getMessage());
        }
    }
}
