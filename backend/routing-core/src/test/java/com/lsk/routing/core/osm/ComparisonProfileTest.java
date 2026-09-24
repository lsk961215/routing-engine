package com.lsk.routing.core.osm;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ComparisonProfileTest {
    @Test void onlyExplicitNoExcludesOtherwiseEligibleRoads() {
        for(String access:new String[]{"private","destination","delivery","permit","unknown"})
            assertTrue(ComparisonProfile.evaluate(Map.of("highway","residential","access",access),2).accepted());
        assertFalse(ComparisonProfile.evaluate(Map.of("highway","residential","access","no","access:conditional","yes @ (Mo-Fr 07:00-10:00)"),2).accepted());
        assertTrue(ComparisonProfile.evaluate(Map.of("highway","residential","access","no","motorcar","yes"),2).accepted());
        assertFalse(ComparisonProfile.evaluate(Map.of("highway","footway"),2).accepted());
    }
    @Test void preservesKnownDirectionsWhileIgnoringConditionalModifiers() {
        assertEquals(CarProfile.Direction.REVERSE,ComparisonProfile.evaluate(Map.of("highway","residential","oneway","-1","oneway:conditional","yes @ (Mo-Fr 07:00-10:00)"),3).direction());
        assertEquals(CarProfile.Direction.FORWARD,ComparisonProfile.evaluate(Map.of("highway","residential","oneway","yes","oneway:motorcar","unknown"),3).direction());
        assertEquals(CarProfile.Direction.BOTH,ComparisonProfile.evaluate(Map.of("highway","residential","oneway","unknown"),3).direction());
    }
    @Test void aGateIsNotAnAutomaticDenialButBollardsAndExplicitNoBlockPassage() {
        assertFalse(ComparisonProfile.blockedNode(Map.of("barrier","gate")));
        assertTrue(ComparisonProfile.blockedNode(Map.of("barrier","bollard")));
        assertTrue(ComparisonProfile.blockedNode(Map.of("barrier","bollard","access","private")));
        assertTrue(ComparisonProfile.blockedNode(Map.of("barrier","gate","motorcar","no")));
        assertFalse(ComparisonProfile.blockedNode(Map.of("barrier","bollard","motorcar","yes","access","no")));
    }
}
