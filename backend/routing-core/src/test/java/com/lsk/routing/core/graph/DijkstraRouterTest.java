package com.lsk.routing.core.graph;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class DijkstraRouterTest {
    @TempDir Path dir;
    // 0->1 (1), 0->2 (2), 1->3 (1), 2->1 (2).
    private RoutingGraph graph(boolean restricted) {
        return new RoutingGraph(new long[]{100,200,300,400}, new double[4], new double[4],
                new int[]{0,2,3,4,4},new int[]{1,2,3,1},new long[]{10,20,30,40},
                new double[]{1,2,1,2},restricted?new long[]{RoutingGraph.turnKey(0,2)}:new long[0]);
    }
    @Test void findsShortestDistanceAndRestoresPath() {
        var r=new DijkstraRouter(graph(false)).route(0,3).orElseThrow();
        assertEquals(2,r.distanceMetres());assertEquals(List.of(0,1,3),r.nodes());assertEquals(List.of(0,2),r.edges());
    }
    @Test void revisitsSameNodeFromAnotherEdgeToAvoidForbiddenTurn() throws Exception {
        var g=graph(true);Path file=dir.resolve("route.rgraph");g.write(file);
        var r=new DijkstraRouter(RoutingGraph.read(file)).route(0,3).orElseThrow();
        assertEquals(5,r.distanceMetres());assertEquals(List.of(0,2,1,3),r.nodes());assertEquals(List.of(1,3,2),r.edges());
        for(int i=1;i<r.edges().size();i++)assertTrue(g.turnAllowed(r.edges().get(i-1),r.edges().get(i)));
    }
    @Test void handlesNoRouteOneWayAndSameNode() {
        var router=new DijkstraRouter(graph(true));
        assertTrue(router.route(3,0).isEmpty());
        assertEquals(List.of(2),router.route(2,2).orElseThrow().nodes());
        assertEquals(0,router.route(2,2).orElseThrow().distanceMetres());
        assertThrows(IndexOutOfBoundsException.class,()->router.route(-1,0));
        assertThrows(IndexOutOfBoundsException.class,()->router.route(0,4));
        // No previous edge at origin 1, so the 1->3 edge is usable.
        assertEquals(1,router.route(1,3).orElseThrow().distanceMetres());
    }
    @Test void pbfRestrictionMakesOtherwiseReachableDestinationUnreachable() throws Exception {
        var pbf=Path.of(getClass().getResource("/osm/graph.osm.pbf").toURI());
        var router=new DijkstraRouter(new GraphBuilder().build(pbf));
        assertTrue(router.route(0,2).isEmpty());
        assertTrue(router.route(1,2).isPresent());
    }
    @Test void zeroCostCycleTerminatesAndRepeatedQueriesAreIndependent() {
        var g=new RoutingGraph(new long[]{1,2,3},new double[3],new double[3],new int[]{0,1,3,3},
                new int[]{1,0,2},new long[]{1,2,3},new double[]{0,0,1},new long[0]);
        var router=new DijkstraRouter(g);
        assertEquals(1,router.route(0,2).orElseThrow().distanceMetres());
        assertTrue(router.route(2,0).isEmpty());
        assertEquals(List.of(0,1,2),router.route(0,2).orElseThrow().nodes());
    }
}
