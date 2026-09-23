package com.lsk.routing.core.osm;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/** Raw input statistics, not a routable graph or a vehicle access decision. */
public record OsmLoadResult(
        long nodeCount, long wayCount, long relationCount, long highwayWayCount,
        Bounds bounds, long highwayNodeReferences, long uniqueHighwayNodeIds,
        long highwaySegments, long shortHighwayWays,
        long restrictionRelations,
        Map<String, Map<String, Long>> highwayTags,
        Map<String, Map<String, Long>> restrictionTags,
        Map<String, Long> restrictionMemberTypes
) {
    public OsmLoadResult {
        highwayTags = freeze(highwayTags);
        restrictionTags = freeze(restrictionTags);
        restrictionMemberTypes = Collections.unmodifiableMap(new TreeMap<>(restrictionMemberTypes));
    }

    private static Map<String, Map<String, Long>> freeze(Map<String, Map<String, Long>> source) {
        var copy = new TreeMap<String, Map<String, Long>>();
        source.forEach((key, values) -> copy.put(key,
                Collections.unmodifiableMap(new TreeMap<>(values))));
        return Collections.unmodifiableMap(copy);
    }

    /** Bounds of actual node coordinates; null when the input has no nodes. */
    public record Bounds(double minLon, double minLat, double maxLon, double maxLat) {}
}
