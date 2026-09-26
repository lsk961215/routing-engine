package com.lsk.routing.api.service;

import com.lsk.routing.core.graph.RoutingGraph;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

class RoutingServiceTest {
    private RoutingService service(boolean configured) {
        var factory=new StaticListableBeanFactory();
        if(configured)factory.addBean("graph",new RoutingGraph(new long[]{1,2},
                new double[]{37,37.001},new double[]{127,127},new int[]{0,1,1},
                new int[]{1},new long[]{1},new double[]{111},new long[0]));
        return new RoutingService(factory.getBeanProvider(RoutingGraph.class));
    }
    @Test void returnsGeometryDistanceAndActualSnapCoordinates() {
        var result=service(true).route(127,37,127,37.001);
        assertEquals(111,result.routes().getFirst().distance());
        assertNull(result.routes().getFirst().duration());
        assertEquals(java.util.List.of(127.0,37.0),result.routes().getFirst().geometry().coordinates().getFirst());
        assertEquals(0,result.waypoints().getFirst().distance());
    }
    @Test void selectsAlgorithmAndRejectsUnknownNames() {
        var service=service(true);
        var d=service.route(127,37,127,37.001,"dijkstra");
        var a=service.route(127,37,127,37.001,"astar");
        assertEquals(d.routes(),a.routes());
        assertEquals("astar",a.metrics().algorithm());
        assertEquals(0,a.metrics().expandedStates()); // Direct same-edge candidate needs no expansion.
        assertEquals("dijkstra",service.route(127,37,127,37.001).metrics().algorithm());
        assertEquals(400,assertThrows(ResponseStatusException.class,()->service.route(127,37,127,37.001,"unknown")).getStatusCode().value());
    }
    @Test void returnsExplicitStatuses() {
        assertEquals(503,assertThrows(ResponseStatusException.class,()->service(false).route(127,37,127,37)).getStatusCode().value());
        assertEquals(400,assertThrows(ResponseStatusException.class,()->service(true).route(Double.NaN,37,127,37)).getStatusCode().value());
        assertEquals(422,assertThrows(ResponseStatusException.class,()->service(true).route(128,38,127,37)).getStatusCode().value());
        assertEquals(404,assertThrows(ResponseStatusException.class,()->service(true).route(127,37.001,127,37)).getStatusCode().value());
    }
    @Test void sameNodeReturnsValidZeroLengthGeometry() {
        var result=service(true).route(127,37,127,37);
        assertEquals(0,result.routes().getFirst().distance());
        assertEquals(2,result.routes().getFirst().geometry().coordinates().size());
    }
    @Test void projectsMiddleOfSegmentAndRejectsReverseTravel() {
        var service=service(true);
        var route=service.route(127.0001,37.00025,127,37.00075);
        assertEquals(55.5,route.routes().getFirst().distance(),1e-5);
        assertEquals(127,route.waypoints().getFirst().location().getFirst(),1e-8);
        assertTrue(route.waypoints().getFirst().distance()>8);
        assertEquals(404,assertThrows(ResponseStatusException.class,
                ()->service.route(127,37.00075,127,37.00025)).getStatusCode().value());
    }
    @Test void comparisonSharesProjectedWaypointsAndMatchesIndividualRoutes() {
        var service=service(true);
        var comparison=service.compare(127.0001,37.00025,127,37.00075);
        assertEquals("Ok",comparison.code());
        assertEquals(2,comparison.results().size());
        assertTrue(comparison.snapMillis()>=0);
        assertEquals(127,comparison.waypoints().getFirst().location().getFirst(),1e-8);
        for(var result:comparison.results()) {
            var individual=service.route(127.0001,37.00025,127,37.00075,result.algorithm());
            assertEquals("Ok",result.code());
            assertEquals(individual.routes(),result.routes());
            assertEquals(individual.waypoints(),comparison.waypoints());
            assertTrue(result.metrics().searchMillis()>=0);
            assertEquals(individual.metrics().expandedStates(),result.metrics().expandedStates());
        }
    }
    @Test void comparisonRetainsBothNoRouteResultsAndMetrics() {
        var comparison=service(true).compare(127,37.00075,127,37.00025);
        assertEquals(2,comparison.results().size());
        assertEquals(2,comparison.waypoints().size());
        for(var result:comparison.results()) {
            assertEquals("NoRoute",result.code());
            assertTrue(result.routes().isEmpty());
            assertTrue(result.metrics().searchMillis()>=0);
        }
    }
    @Test void comparisonHandlesSamePointAndInputErrors() {
        for(var result:service(true).compare(127,37.0005,127,37.0005).results()) {
            assertEquals(0,result.routes().getFirst().distance());
            assertEquals(2,result.routes().getFirst().geometry().coordinates().size());
        }
        assertEquals(503,assertThrows(ResponseStatusException.class,()->service(false).compare(127,37,127,37)).getStatusCode().value());
        assertEquals(400,assertThrows(ResponseStatusException.class,()->service(true).compare(Double.NaN,37,127,37)).getStatusCode().value());
        assertEquals(422,assertThrows(ResponseStatusException.class,()->service(true).compare(128,38,127,37)).getStatusCode().value());
    }
}
