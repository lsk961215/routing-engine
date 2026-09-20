package com.lsk.routing.core.osm;

public record OsmLoadResult(
        long nodeCount,
        long wayCount,
        long relationCount,
        long highwayWayCount
) {
}