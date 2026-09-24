package com.lsk.routing.core.graph;

import com.lsk.routing.core.osm.CarProfile.Direction;
import java.time.Instant;
import java.util.Objects;
import java.util.function.IntPredicate;

/** Per-request immutable evaluation; the graph and spatial index remain shared. */
final class EdgeAvailability {
    private static final IntPredicate ALL=edge->true;
    static IntPredicate at(RoutingGraph graph,Instant instant) {
        Objects.requireNonNull(instant,"snapshotAt");
        var conditional=graph.conditionalDirections();
        if(conditional.isEmpty())return ALL;
        var c=conditional.get();
        var values=c.rules().stream().map(rule->rule.evaluate(instant,c.timeZone())).toArray(Direction[]::new);
        return edge->{
            int id=c.ruleId(edge);if(id<0)return true;
            return values[id]==Direction.BOTH || values[id]==Direction.FORWARD && c.directionCode(edge)==1
                    || values[id]==Direction.REVERSE && c.directionCode(edge)==2;
        };
    }
    static IntPredicate staticOnly(RoutingGraph graph) { graph.requireStaticDirections();return ALL; }
}
