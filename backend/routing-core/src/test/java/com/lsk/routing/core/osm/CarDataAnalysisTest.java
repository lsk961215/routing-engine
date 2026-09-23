package com.lsk.routing.core.osm;

import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class CarDataAnalysisTest {
    @Test void analyzesActualPbfThroughBothPasses() throws Exception {
        var path = Path.of(getClass().getResource("/osm/sample.osm.pbf").toURI());
        var result = new CarDataAnalysis().read(path);
        assertEquals(3, result.highwayWays());
        // Residential way is conditional, footway is out of scope, service has only one node.
        assertEquals(0, result.acceptedWays());
        assertEquals(0, result.uniqueNodes());
        assertEquals(0, result.directedSegments());
        assertEquals(2, result.restrictions());
        assertEquals(0, result.candidateNodeVia());
        assertEquals(2L, result.issues().get("WAY_NOT_ACCEPTED"));
        assertEquals(1L, result.issues().get("VIA_WAY_DEFERRED"));
        assertEquals(1L, result.issues().get("CONDITIONAL"));
        assertFalse(result.issues().containsKey("MISSING_REFERENCE"));
        assertEquals(3L, result.decisions().values().stream().mapToLong(Long::longValue).sum());
    }
    @Test void countsAcceptedDirectionsDeduplicatesNodesAndFindsMissingWay() throws Exception {
        var path = Path.of(getClass().getResource("/osm/car.osm.pbf").toURI());
        var result = new CarDataAnalysis().read(path);
        assertEquals(3, result.acceptedWays());
        assertEquals(4, result.uniqueNodes());
        assertEquals(6, result.directedSegments());
        assertEquals(2, result.restrictions());
        assertEquals(1, result.candidateNodeVia());
        assertEquals(java.util.Map.of("MISSING_REFERENCE", 1L), result.issues());
        assertEquals(java.util.List.of(21L), result.examples().get("MISSING_REFERENCE"));
    }
}
