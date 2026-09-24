package com.lsk.routing.core.graph;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class ComparisonGraphTest {
    @Test void definiteBarrierBlocksPassageWithoutRemovingApproachRoadsAndGateKeepsOneway() throws Exception {
        var source=Path.of(getClass().getResource("/osm/comparison-access.osm.pbf").toURI());
        var graph=new GraphBuilder().build(source,true);
        assertEquals(6,graph.edgeCount());
        var router=new DijkstraRouter(graph);
        assertTrue(router.route(0,1).isPresent());
        assertTrue(router.route(1,2).isPresent());
        assertTrue(router.route(0,2).isEmpty());
        assertTrue(router.route(3,5).isPresent());
        assertTrue(router.route(5,3).isEmpty());
        assertTrue(router.route(2,3).isEmpty());
        assertTrue(graph.conditionalDirections().isEmpty());
        assertThrows(java.io.IOException.class,()->new GraphBuilder().build(source));
    }
}
