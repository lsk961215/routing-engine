package com.lsk.routing.core.osm;

import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static com.lsk.routing.core.osm.CarProfile.*;

class CarProfileTest {
    private final CarProfile profile = new CarProfile();
    private Decision decide(String... pairs) {
        var tags = new HashMap<String, String>(Map.of("highway", "residential"));
        for (int i = 0; i < pairs.length; i += 2) tags.put(pairs[i], pairs[i + 1]);
        return profile.evaluate(tags, 3);
    }
    @Test void moreSpecificAccessOverridesGeneralAccess() {
        assertTrue(decide("access", "no", "motorcar", "yes").accepted());
        assertEquals(Status.EXCLUDED, decide("access", "yes", "motor_vehicle", "no").status());
        assertTrue(decide("vehicle", "no", "motor_vehicle", "yes").accepted());
        assertEquals(Status.EXCLUDED, decide("motor_vehicle", "yes", "motorcar", "private").status());
    }
    @Test void directionsAndExplicitOverrides() {
        assertEquals(Direction.BOTH, decide().direction());
        assertEquals(Direction.REVERSE, decide("oneway", "-1").direction());
        assertEquals(Direction.FORWARD, decide("oneway", "true").direction());
        assertEquals(Direction.BOTH, decide("oneway", "yes", "oneway:motorcar", "no").direction());
        assertEquals(Direction.FORWARD, decide("highway", "motorway").direction());
        assertEquals(Direction.FORWARD, decide("junction", "roundabout").direction());
        assertEquals(Direction.BOTH, decide("junction", "roundabout", "oneway", "no").direction());
    }
    @Test void doesNotSilentlyAllowUnsupportedAccess() {
        for (String value : new String[]{"destination", "customers", "unknown", "discouraged"}) {
            assertEquals(Status.DEFERRED, decide("access", value).status());
        }
        assertEquals(Status.DEFERRED, decide("motor_vehicle:conditional", "no @ wet").status());
        assertEquals(Status.DEFERRED, decide("vehicle:forward", "no").status());
        assertEquals(Status.DEFERRED, decide("oneway", "reversible").status());
        assertEquals(Status.DEFERRED, decide("oneway:forward:conditional", "yes @ wet").status());
        assertTrue(decide("oneway:bicycle", "no").accepted());
    }
    @Test void excludesUnsupportedGeometryAndRoadTypes() {
        assertEquals(Status.EXCLUDED, decide("highway", "footway", "motorcar", "yes").status());
        assertEquals(Status.EXCLUDED, decide("highway", "construction").status());
        assertEquals(Status.EXCLUDED, decide("area", "yes").status());
        assertEquals(Status.EXCLUDED, profile.evaluate(Map.of("highway", "service"), 1).status());
    }
}
