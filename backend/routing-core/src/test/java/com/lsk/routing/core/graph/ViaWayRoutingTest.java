package com.lsk.routing.core.graph;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ViaWayRoutingTest {
    @TempDir Path dir;
    // 0->1(from), 0->4(detour), 1->2(via), 1->5(exit), 2->3(to), 2->5(exit), 4->1(other entry).
    private RoutingGraph graph(boolean only) {
        return new RoutingGraph(new long[]{1,2,3,4,5,6},new double[]{37,37,37,37,37.01,37.01},
                new double[]{127,127.001,127.002,127.003,127,127.002},new int[]{0,2,4,6,6,7,7},
                new int[]{1,4,2,5,3,5,1},new long[]{10,40,20,50,30,60,70},
                new double[]{10,20,10,1,10,1,20},new long[0],
                List.of(new RoutingGraph.SequenceRestriction(only,List.of(List.of(0,2,4)))));
    }
    @Test void forbiddenSequenceRequiresDifferentHistoryAtSameViaEdge() {
        var route=new DijkstraRouter(graph(false)).route(0,3).orElseThrow();
        assertEquals(List.of(1,6,2,4),route.edges());assertEquals(60,route.distanceMetres());
        assertEquals(20,new DijkstraRouter(graph(false)).route(1,3).orElseThrow().distanceMetres());
        assertEquals(11,new DijkstraRouter(graph(false)).route(0,5).orElseThrow().distanceMetres());
    }
    @Test void onlySequenceBlocksEarlyAndLateExitsButNotOtherEntry() {
        var router=new DijkstraRouter(graph(true));
        assertEquals(30,router.route(0,3).orElseThrow().distanceMetres());
        assertEquals(41,router.route(0,5).orElseThrow().distanceMetres());
        assertEquals(List.of(1,6,3),router.route(0,5).orElseThrow().edges());
        assertEquals(1,router.route(1,5).orElseThrow().distanceMetres());
    }
    @Test void coordinateRoutingCarriesHistoryIntoPartialDestinationEdge() {
        var router=new CoordinateRouter(graph(false));
        assertTrue(router.route(127.0005,37,127.0025,37).isEmpty());
        assertEquals(10,new CoordinateRouter(graph(true)).route(127.0005,37,127.0015,37).orElseThrow().distanceMetres(),1e-6);
    }
    @Test void coordinateStartingInsideViaDoesNotInventFromHistory() {
        var route=new CoordinateRouter(graph(false)).route(127.0015,37,127.0025,37).orElseThrow();
        assertEquals(10,route.distanceMetres(),1e-6);
        assertTrue(new CoordinateRouter(graph(false)).route(127.0005,37,127.0025,37).isEmpty());
    }
    @Test void versionTwoRoundTripAndCorruption() throws Exception {
        Path file=dir.resolve("via.rgraph");graph(false).write(file);var loaded=RoutingGraph.read(file);
        assertEquals(1,loaded.sequenceRestrictions().size());
        assertEquals(60,new DijkstraRouter(loaded).route(0,3).orElseThrow().distanceMetres());
        byte[] bytes=Files.readAllBytes(file);bytes[bytes.length-10]^=1;Files.write(file,bytes);
        assertThrows(java.io.IOException.class,()->RoutingGraph.read(file));
    }
    @Test void overlappingSequencesAreNotLostOnPrefixFallback() {
        var rules=List.of(new RoutingGraph.SequenceRestriction(false,List.of(List.of(0,1,2))),
                new RoutingGraph.SequenceRestriction(false,List.of(List.of(1,2,3))));
        var machine=new TurnSequences(rules);
        int state=machine.advance(0,1);state=machine.advance(state,2);
        assertEquals(-1,machine.advance(state,3));
        state=machine.advance(0,0);state=machine.advance(state,1);
        assertEquals(-1,machine.advance(state,2));
    }
    @Test void onlyAlternativesAndIndependentObligationsAreCombined() {
        var machine=new TurnSequences(List.of(
                new RoutingGraph.SequenceRestriction(true,List.of(List.of(0,1,2),List.of(0,3,4))),
                new RoutingGraph.SequenceRestriction(false,List.of(List.of(0,3,4)))));
        int state=machine.advance(0,0);
        assertEquals(-1,machine.advance(state,9));
        assertTrue(machine.advance(state,1)>=0);
        state=machine.advance(state,3);assertEquals(-1,machine.advance(state,4));
    }
    @Test void buildsMultipleViaWaysFromPbfAndPreservesThemAfterReload() throws Exception {
        var input=Path.of(getClass().getResource("/osm/via-no.osm.pbf").toURI());
        var graph=new GraphBuilder().build(input);
        assertEquals(1,graph.sequenceRestrictions().size());
        assertEquals(5,graph.sequenceRestrictions().getFirst().paths().getFirst().size());
        Path file=dir.resolve("built-via.rgraph");graph.write(file);graph=RoutingGraph.read(file);
        var router=new DijkstraRouter(graph);
        assertTrue(router.route(0,5).isEmpty());
        assertTrue(router.route(7,5).isPresent());
        assertTrue(router.route(0,6).isPresent());
        assertTrue(router.route(2,5).isPresent());
    }
    @Test void pbfOnlyRestrictionBlocksIntermediateExit() throws Exception {
        var input=Path.of(getClass().getResource("/osm/via-only.osm.pbf").toURI());
        var graph=new GraphBuilder().build(input);var router=new DijkstraRouter(graph);
        assertTrue(router.route(0,5).isPresent());
        assertTrue(router.route(0,6).isEmpty());
        assertTrue(router.route(7,6).isPresent());
        assertTrue(router.route(0,2).isPresent()); // It is legal to stop before completing the mandatory path.
    }
    @Test void invalidSequenceTopologyIsRejected() {
        assertThrows(IllegalArgumentException.class,()->new RoutingGraph(new long[]{1,2,3},new double[3],new double[3],
                new int[]{0,1,2,2},new int[]{1,2},new long[]{10,20},new double[]{1,1},new long[0],
                List.of(new RoutingGraph.SequenceRestriction(false,List.of(List.of(0,1,0))))));
    }
    @Test void prefixMachineMatchesIndependentHistoryOracle() {
        var rules=List.of(new RoutingGraph.SequenceRestriction(false,List.of(List.of(0,1,2),List.of(2,3,1))),
                new RoutingGraph.SequenceRestriction(true,List.of(List.of(1,3,4),List.of(1,2,4))));
        var machine=new TurnSequences(rules);var random=new Random(819);
        for(int trial=0;trial<100;trial++) {
            var history=new ArrayList<Integer>();int state=0;
            for(int step=0;step<15;step++) {
                int edge=random.nextInt(5);boolean allowed=true;
                for(var rule:rules) {
                    var required=new HashSet<Integer>();
                    for(var path:rule.paths()) {
                        if(rule.only()) {
                            for(int prefix=1;prefix<path.size();prefix++)
                                if(endsWith(history,path.subList(0,prefix)))required.add(path.get(prefix));
                        } else if(endsWith(history,path.subList(0,path.size()-1)) && edge==path.getLast())allowed=false;
                    }
                    if(rule.only() && !required.isEmpty() && !required.contains(edge))allowed=false;
                }
                int next=machine.advance(state,edge);
                assertEquals(allowed,next>=0);
                if(next<0)break;
                state=next;history.add(edge);
            }
        }
    }
    private static boolean endsWith(List<Integer> history,List<Integer> prefix) {
        return history.size()>=prefix.size() && history.subList(history.size()-prefix.size(),history.size()).equals(prefix);
    }
    @Test void sharedHistoryAutomatonSupportsConcurrentQueries() {
        var graph=graph(false);var futures=new ArrayList<java.util.concurrent.CompletableFuture<Double>>();
        for(int i=0;i<12;i++) {
            final int start=i%2;
            futures.add(java.util.concurrent.CompletableFuture.supplyAsync(()->new DijkstraRouter(graph).route(start,3).orElseThrow().distanceMetres()));
        }
        for(int i=0;i<futures.size();i++)assertEquals(i%2==0?60:20,futures.get(i).join());
    }
}
