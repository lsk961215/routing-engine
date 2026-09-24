package com.lsk.routing.core.osm;

import java.util.Map;

/** Graph-build profile: union of all supported temporal directions, never a chosen clock time. */
public record RoadDirections(CarProfile.Decision decision, ConditionalOneway conditional) {
    public static RoadDirections forGraph(long wayId, Map<String,String> tags, int nodes) {
        if (!tags.containsKey(ConditionalOneway.KEY))
            return new RoadDirections(new CarProfile().evaluate(tags,nodes),null);
        var rule=ConditionalOneway.parse(wayId,tags,nodes);
        var union=rule.baseline()==rule.active()?rule.baseline():CarProfile.Direction.BOTH;
        return new RoadDirections(new CarProfile.Decision(CarProfile.Status.ACCEPTED,union,"accepted"),rule);
    }
}
