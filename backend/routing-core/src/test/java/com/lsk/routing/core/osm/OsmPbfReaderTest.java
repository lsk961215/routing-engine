package com.lsk.routing.core.osm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class OsmPbfReaderTest {
    @TempDir Path temporaryDirectory;

    private Path fixture(String name) throws Exception {
        return Path.of(getClass().getResource("/osm/" + name + ".osm.pbf").toURI());
    }

    @Test
    void countsEntitiesTagsReferencesAndRestrictions() throws Exception {
        var result = new OsmPbfReader().read(fixture("sample"));
        assertEquals(4, result.nodeCount());
        assertEquals(4, result.wayCount());
        assertEquals(3, result.relationCount());
        assertEquals(3, result.highwayWayCount());
        assertEquals(126.0, result.bounds().minLon(), 1e-7);
        assertEquals(37.0, result.bounds().minLat(), 1e-7);
        assertEquals(126.3, result.bounds().maxLon(), 1e-7);
        assertEquals(37.3, result.bounds().maxLat(), 1e-7);
        assertEquals(7, result.highwayNodeReferences());
        assertEquals(4, result.uniqueHighwayNodeIds());
        assertEquals(4, result.highwaySegments());
        assertEquals(1, result.shortHighwayWays());
        assertEquals(Map.of("residential", 1L, "footway", 1L, "service", 1L), result.highwayTags().get("highway"));
        assertEquals(Map.of("yes", 1L, "-1", 1L), result.highwayTags().get("oneway"));
        assertEquals(Map.of("private", 1L), result.highwayTags().get("access"));
        assertEquals(Map.of("yes", 1L), result.highwayTags().get("motorcar"));
        assertEquals(Map.of("30", 1L), result.highwayTags().get("maxspeed"));
        assertEquals(Map.of("no @ (08:00-09:00)", 1L), result.highwayTags().get("motor_vehicle:conditional"));
        assertFalse(result.highwayTags().containsKey("building"));
        assertEquals(2, result.restrictionRelations());
        assertEquals(Map.of("no_left_turn", 1L), result.restrictionTags().get("restriction"));
        assertEquals(Map.of("only_straight_on", 1L), result.restrictionTags().get("restriction:motorcar"));
        assertEquals(Map.of("no_left_turn @ (08:00-09:00)", 1L), result.restrictionTags().get("restriction:conditional"));
        assertEquals(Map.of("bicycle", 1L), result.restrictionTags().get("except"));
        assertEquals(Map.of("from:Way", 2L, "to:Way", 2L, "via:Node", 1L, "via:Way", 1L), result.restrictionMemberTypes());
    }

    @Test
    void handlesInputWithoutNodesOrReferences() throws Exception {
        var result = new OsmPbfReader().read(fixture("no-nodes"));
        assertNull(result.bounds());
        assertEquals(0, result.nodeCount());
        assertEquals(1, result.highwayWayCount());
        assertEquals(1, result.shortHighwayWays());
        assertEquals(0, result.highwaySegments());
        assertEquals(0, result.uniqueHighwayNodeIds());
    }

    @Test
    void rejectsMissingFile() {
        assertThrows(NoSuchFileException.class,
                () -> new OsmPbfReader().read(temporaryDirectory.resolve("missing.pbf")));
    }

    @Test
    void resultMapsCannotBeChanged() throws Exception {
        var result = new OsmPbfReader().read(fixture("sample"));
        assertThrows(UnsupportedOperationException.class, () -> result.highwayTags().clear());
        assertThrows(UnsupportedOperationException.class, () -> result.highwayTags().get("highway").clear());
        assertThrows(UnsupportedOperationException.class, () -> result.restrictionTags().get("restriction").clear());
        assertThrows(UnsupportedOperationException.class, () -> result.restrictionMemberTypes().clear());
    }
}
