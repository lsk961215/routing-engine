package com.lsk.routing.core.graph;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.io.IOException;
import static org.junit.jupiter.api.Assertions.*;

class RoutingGraphTest {
    @TempDir Path dir;
    @Test void roundTripPreservesDirectionsAndTurnBan() throws Exception {
        var g=new RoutingGraph(new long[]{10,20,30},new double[]{37,37.1,37.2},new double[]{127,127.1,127.2},
                new int[]{0,1,3,3},new int[]{1,0,2},new long[]{100,100,200},new double[]{10,10,20},
                new long[]{RoutingGraph.turnKey(0,2)});
        Path file=dir.resolve("graph.bin");g.write(file);var loaded=RoutingGraph.read(file);
        assertEquals(3,loaded.nodeCount());assertEquals(30,loaded.osmNodeId(2));
        assertTrue(loaded.turnAllowed(0,1));assertFalse(loaded.turnAllowed(0,2));
        assertEquals(20,loaded.distanceMetres(2));
        byte[] bytes=Files.readAllBytes(file);bytes[30]^=1;Files.write(file,bytes);
        assertThrows(IOException.class,()->RoutingGraph.read(file));
        Files.write(file,new byte[4]);assertThrows(IOException.class,()->RoutingGraph.read(file));
    }
    @Test void rejectsInvalidTopology() {
        assertThrows(IllegalArgumentException.class,()->new RoutingGraph(new long[]{1},new double[]{37},new double[]{127},
                new int[]{0,1},new int[]{9},new long[]{1},new double[]{1},new long[0]));
    }
    @Test void refusesUnresolvedRestrictions() throws Exception {
        Path input=Path.of(getClass().getResource("/osm/car.osm.pbf").toURI());
        assertThrows(IOException.class,()->new GraphBuilder().build(input));
    }
    @Test void buildsPbfAndPreservesRestrictionAcrossReload() throws Exception {
        Path input=Path.of(getClass().getResource("/osm/graph.osm.pbf").toURI());
        var graph=new GraphBuilder().build(input);
        assertEquals(4,graph.nodeCount());assertEquals(6,graph.edgeCount());
        assertEquals(1,graph.forbiddenTurnCount());
        int incoming=-1,outgoing=-1;
        for(int e=0;e<graph.edgeCount();e++) {
            if(graph.osmWayId(e)==10)incoming=e;
            if(graph.osmWayId(e)==11)outgoing=e;
            assertTrue(graph.distanceMetres(e)>0);
        }
        assertFalse(graph.turnAllowed(incoming,outgoing));
        Path file=dir.resolve("pbf.rgraph");graph.write(file);
        assertFalse(RoutingGraph.read(file).turnAllowed(incoming,outgoing));
        assertThrows(java.nio.file.FileAlreadyExistsException.class,()->graph.write(file));
    }
}
