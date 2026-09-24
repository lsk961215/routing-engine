package com.lsk.routing.core.graph;

import com.lsk.routing.core.osm.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.zip.CRC32;
import static org.junit.jupiter.api.Assertions.*;

class ConditionalGraphTest {
    @TempDir Path dir;
    private ConditionalOneway rule() {
        return ConditionalOneway.parse(10,Map.of("highway","residential","oneway","no",
                "oneway:conditional","yes @ (Mo-Fr 07:00-10:00)"),2);
    }
    private ConditionalDirections bindings() {
        return new ConditionalDirections(ZoneId.of("Asia/Seoul"),List.of(rule()),new int[]{0,0,-1,-1},new byte[]{1,2,0,0});
    }
    private RoutingGraph graph(boolean conditional,boolean sequences) {
        return new RoutingGraph(new long[]{1,2,3,4},new double[]{37,37,37,37},new double[]{127,127.001,127.002,127.003},
                new int[]{0,1,3,4,4},new int[]{1,0,2,3},new long[]{10,10,20,30},new double[]{10,10,10,10},new long[0],
                sequences?List.of(new RoutingGraph.SequenceRestriction(false,List.of(List.of(0,2,3)))):List.of(),conditional?bindings():null);
    }
    @Test void writesLegacyVersionsAndReadsThemWithoutConditionalRules() throws Exception {
        for(boolean sequences:List.of(false,true)) {
            var file=dir.resolve("legacy-"+sequences);var g=graph(false,sequences);g.write(file);
            assertEquals(sequences?2:1,ByteBuffer.wrap(Files.readAllBytes(file)).getInt(4));
            var loaded=RoutingGraph.read(file);assertTrue(loaded.conditionalDirections().isEmpty());
            assertEquals(g.sequenceRestrictions(),loaded.sequenceRestrictions());
            assertEquals(new DijkstraRouter(g).route(0,3),new DijkstraRouter(loaded).route(0,3));
        }
    }
    @Test void versionThreeRoundTripsWithAndWithoutViaRules() throws Exception {
        for(boolean sequences:List.of(false,true)) {
            var file=dir.resolve("v3-"+sequences);var g=graph(true,sequences);g.write(file);
            assertEquals(3,ByteBuffer.wrap(Files.readAllBytes(file)).getInt(4));
            var loaded=RoutingGraph.read(file);var c=loaded.conditionalDirections().orElseThrow();
            assertEquals(ZoneId.of("Asia/Seoul"),c.timeZone());assertEquals(List.of(rule()),c.rules());
            assertEquals(g.sequenceRestrictions(),loaded.sequenceRestrictions());
            for(int e=0;e<4;e++){assertEquals(bindings().ruleId(e),c.ruleId(e));assertEquals(bindings().directionCode(e),c.directionCode(e));}
            assertEquals(CarProfile.Direction.FORWARD,c.rules().getFirst().evaluate(Instant.parse("2026-09-23T22:00:00Z"),c.timeZone()));
            assertThrows(FileAlreadyExistsException.class,()->g.write(file));
        }
    }
    @Test void routingCannotSilentlyIgnoreConditionalEdges() {
        assertThrows(IllegalArgumentException.class,()->new DijkstraRouter(graph(true,false)).route(0,3));
        assertThrows(IllegalArgumentException.class,()->new CoordinateRouter(graph(true,true)).route(127,37,127.003,37));
    }
    @Test void bindingsAreImmutableAndValidateIndexesDirectionsAndReferences() {
        int[] ids={0};byte[] directions={1};var rules=new ArrayList<>(List.of(rule()));
        var c=new ConditionalDirections(ZoneId.of("Asia/Seoul"),rules,ids,directions);
        ids[0]=-1;directions[0]=0;rules.clear();assertEquals(0,c.ruleId(0));assertEquals(1,c.directionCode(0));
        assertThrows(UnsupportedOperationException.class,()->c.rules().clear());
        for(int id:new int[]{-2,1})assertThrows(IllegalArgumentException.class,()->new ConditionalDirections(ZoneId.of("Asia/Seoul"),List.of(rule()),new int[]{id},new byte[]{1}));
        for(byte d:new byte[]{0,3})assertThrows(IllegalArgumentException.class,()->new ConditionalDirections(ZoneId.of("Asia/Seoul"),List.of(rule()),new int[]{0},new byte[]{d}));
        assertThrows(IllegalArgumentException.class,()->new ConditionalDirections(ZoneId.of("UTC"),List.of(rule()),new int[]{0},new byte[]{1}));
        assertThrows(IllegalArgumentException.class,()->new ConditionalDirections(ZoneId.of("Asia/Seoul"),List.of(rule()),new int[]{-1},new byte[]{0}));
        assertThrows(IllegalArgumentException.class,()->new ConditionalDirections(ZoneId.of("Asia/Seoul"),List.of(rule()),new int[]{0,-1},new byte[]{1,2}));
    }
    private void rejects(byte[] bytes,String name,boolean repairCrc) throws Exception {
        if(repairCrc) {var crc=new CRC32();crc.update(bytes,0,bytes.length-8);ByteBuffer.wrap(bytes).putLong(bytes.length-8,crc.getValue());}
        Path p=dir.resolve(name);Files.write(p,bytes);assertThrows(java.io.IOException.class,()->RoutingGraph.read(p),name);
    }
    @Test void rejectsMalformedV3EvenWithValidChecksums() throws Exception {
        var g=graph(true,false);Path p=dir.resolve("source");g.write(p);byte[] original=Files.readAllBytes(p);
        int conditional=20+(int)g.arrayPayloadBytes()+4; // sequence count is zero in v3
        int rule=conditional+16,bindings=rule+24;
        // zone bytes, count, rule directions, weekdays, minutes, binding ID/direction
        for(int[] change:new int[][]{{conditional+12,0},{conditional+12,10001},{rule,3},{rule+8,0},{rule+12,8},{rule+16,1440},{rule+20,420},{bindings,2}}) {
            byte[] bytes=original.clone();ByteBuffer.wrap(bytes).putInt(change[0],change[1]);
            rejects(bytes,"field-"+change[0]+"-"+change[1],true);
        }
        byte[] zone=original.clone();zone[conditional+2]='X';rejects(zone,"zone",true);
        byte[] direction=original.clone();direction[bindings+4]=0;rejects(direction,"direction",true);
        byte[] crc=original.clone();crc[rule+3]^=1;rejects(crc,"checksum",false);
        rejects(Arrays.copyOf(original,original.length-1),"truncated",false);
        rejects(Arrays.copyOf(original,original.length+1),"trailing",true);
    }
    @Test void rejectsImpossibleDirectionAndMismatchedBindingCount() {
        var forward=new ConditionalOneway(CarProfile.Direction.FORWARD,CarProfile.Direction.FORWARD,
                DayOfWeek.MONDAY,DayOfWeek.FRIDAY,LocalTime.of(7,0),LocalTime.of(10,0));
        assertThrows(IllegalArgumentException.class,()->new ConditionalDirections(ZoneId.of("Asia/Seoul"),List.of(forward),new int[]{0},new byte[]{2}));
        assertThrows(IllegalArgumentException.class,()->new RoutingGraph(new long[]{1},new double[]{37},new double[]{127},
                new int[]{0,0},new int[0],new long[0],new double[0],new long[0],List.of(),bindings()));
    }
}
