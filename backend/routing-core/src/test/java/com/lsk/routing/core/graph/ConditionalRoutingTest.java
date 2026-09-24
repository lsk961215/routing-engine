package com.lsk.routing.core.graph;

import com.lsk.routing.core.osm.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class ConditionalRoutingTest {
    @TempDir Path dir;
    private static final Instant ACTIVE=Instant.parse("2026-09-23T23:00:00Z"); // Thu 08:00 Seoul
    private static final Instant INACTIVE=Instant.parse("2026-09-24T02:00:00Z");
    private Path input(String name) throws Exception {return Path.of(getClass().getResource("/osm/"+name+".osm.pbf").toURI());}
    private RoutingGraph build(String name) throws Exception {return new GraphBuilder().build(input(name));}
    @Test void buildsUnionDirectionsAndReloadsVersionThree() throws Exception {
        var graph=build("conditional-road");assertEquals(4,graph.edgeCount());
        assertEquals(1,graph.conditionalDirections().orElseThrow().rules().size());
        Path file=dir.resolve("graph.rgraph");graph.write(file);graph=RoutingGraph.read(file);
        var router=new DijkstraRouter(graph);
        assertTrue(router.route(0,2,ACTIVE).isPresent());assertTrue(router.route(2,0,ACTIVE).isEmpty());
        assertTrue(router.route(0,2,INACTIVE).isEmpty());assertTrue(router.route(2,0,INACTIVE).isPresent());
        assertThrows(IllegalArgumentException.class,()->router.route(0,0));
    }
    @Test void directPartialSegmentsAndPartialDestinationRespectTime() throws Exception {
        var router=new CoordinateRouter(build("conditional-road"));
        assertTrue(router.route(127.00025,37,127.00075,37,ACTIVE).isPresent());
        assertTrue(router.route(127.00075,37,127.00025,37,ACTIVE).isEmpty());
        assertTrue(router.route(127.00025,37,127.00075,37,INACTIVE).isEmpty());
        assertTrue(router.route(127.00075,37,127.00025,37,INACTIVE).isPresent());
        // Arrive from a static edge and try to enter the conditional destination edge.
        assertTrue(router.route(127.0015,37,127.0005,37,ACTIVE).isEmpty());
        assertTrue(router.route(127.0015,37,127.0005,37,INACTIVE).isPresent());
        assertEquals(0,router.route(127.0005,37,127.0005,37,ACTIVE).orElseThrow().distanceMetres());
        assertThrows(IllegalArgumentException.class,()->router.route(127.0005,37,127.0005,37));
    }
    @Test void baselineDirectionDoesNotEraseTemporarilyRelevantNoRestriction() throws Exception {
        var analysis=new CarDataAnalysis().readForGraph(input("conditional-via-no"));
        assertTrue(analysis.directionBlockedNoRestrictions().isEmpty());
        var graph=build("conditional-via-no");assertEquals(1,graph.sequenceRestrictions().size());
        var router=new DijkstraRouter(graph);
        assertTrue(router.route(0,5,ACTIVE).isEmpty()); // no restriction is active along the traversable path
        assertTrue(router.route(7,5,ACTIVE).isPresent()); // different incoming way
        assertTrue(router.route(7,5,INACTIVE).isEmpty()); // conditional via direction now blocks progress
        var coordinates=new CoordinateRouter(graph);
        assertTrue(coordinates.route(127.0015,37,127.0045,37,ACTIVE).isPresent());
        assertTrue(coordinates.route(127.0015,37,127.0045,37,INACTIVE).isEmpty());
    }
    @Test void onlyAndNodeTurnsRemainEnforcedAlongConditionalVia() throws Exception {
        var graph=build("conditional-via-only");var router=new DijkstraRouter(graph);
        assertTrue(router.route(0,5,ACTIVE).isPresent());assertTrue(router.route(0,6,ACTIVE).isEmpty());
        assertTrue(router.route(0,5,INACTIVE).isEmpty());
        assertTrue(new DijkstraRouter(build("conditional-via-only-node-ban")).route(0,5,ACTIVE).isEmpty());
        var coordinates=new CoordinateRouter(graph);
        assertTrue(coordinates.route(127.0005,37,127.0025,37,ACTIVE).isPresent());
        assertTrue(coordinates.route(127.0005,37,127.0025,37,INACTIVE).isEmpty());
        assertTrue(coordinates.route(127.0005,37,127.001,37.005,ACTIVE).isEmpty());
    }
    @Test void concurrentSnapshotsShareGraphAndIndexWithoutSharingEvaluatedTime() throws Exception {
        var graph=build("conditional-road");var coordinates=new CoordinateRouter(graph);var nodes=new DijkstraRouter(graph);
        try(var pool=Executors.newFixedThreadPool(8)) {
            var futures=new ArrayList<Future<Boolean>>();
            for(int i=0;i<100;i++) {final boolean active=i%2==0;
                futures.add(pool.submit(()->{
                    var time=active?ACTIVE:INACTIVE;
                    return coordinates.route(127.00025,37,127.0015,37,time).isPresent()==active
                            && nodes.route(2,0,time).isPresent()!=active;
                }));
            }
            for(var f:futures)assertTrue(f.get());
        }
    }
    @Test void staticRoutingResultsAreUnchangedWhenSnapshotIsSupplied() throws Exception {
        var graph=build("via-no");var nodes=new DijkstraRouter(graph);var coordinates=new CoordinateRouter(graph);
        assertEquals(nodes.route(7,5),nodes.route(7,5,ACTIVE));
        assertEquals(coordinates.route(127.0015,37,127.0045,37),coordinates.route(127.0015,37,127.0045,37,ACTIVE));
    }
    @Test void actualDirectionalKeyAndUnsupportedConditionsStillRejected() {
        for(var tags:List.of(Map.of("highway","residential","oneway:forward:conditional","yes @ (Mo-Fr 07:00-10:00)"),
                Map.of("highway","residential","oneway","no","oneway:conditional","yes @ PH"))) {
            if(tags.containsKey(ConditionalOneway.KEY))assertThrows(ConditionalOneway.UnsupportedRuleException.class,()->RoadDirections.forGraph(465545550,tags,2));
            else assertEquals(CarProfile.Status.DEFERRED,RoadDirections.forGraph(465545550,tags,2).decision().status());
        }
    }
}
