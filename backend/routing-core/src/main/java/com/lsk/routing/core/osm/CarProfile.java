package com.lsk.routing.core.osm;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Conservative static passenger-car profile for analysis, not a complete legal-access model. */
public final class CarProfile {
    private static final Set<String> HIGHWAYS = Set.of(
            "motorway", "motorway_link", "trunk", "trunk_link", "primary", "primary_link",
            "secondary", "secondary_link", "tertiary", "tertiary_link", "unclassified",
            "residential", "living_street", "service");
    private static final List<String> ACCESS = List.of("motorcar", "motor_vehicle", "vehicle", "access");
    private static final List<String> ONEWAY = List.of("oneway:motorcar", "oneway:motor_vehicle", "oneway");
    private static final Set<String> ALLOW = Set.of("yes", "permissive", "designated");
    private static final Set<String> DENY = Set.of("no", "private", "agricultural", "forestry", "military",
            "official", "permit", "residents", "delivery", "bus");

    public enum Status { ACCEPTED, EXCLUDED, DEFERRED }
    public enum Direction { BOTH, FORWARD, REVERSE, NONE }
    public record Decision(Status status, Direction direction, String reason) {
        public boolean accepted() { return status == Status.ACCEPTED; }
    }

    public Decision evaluate(Map<String, String> tags, int nodeCount) {
        if (!HIGHWAYS.contains(tags.getOrDefault("highway", ""))) return exclude("highway_out_of_scope");
        if (nodeCount < 2) return exclude("too_few_nodes");
        if ("yes".equals(tags.get("area"))) return exclude("area");
        for (String key : tags.keySet()) {
            for (String base : ACCESS) {
                if (key.startsWith(base + ":") && !key.equals(base + ":note")) {
                    return defer("access_modifier");
                }
            }
            if (key.startsWith("oneway:") && !ONEWAY.contains(key)
                    && !key.equals("oneway:bicycle") && !key.equals("oneway:bus")) {
                return defer("oneway_modifier");
            }
        }
        for (String key : ACCESS) {
            String value = tags.get(key);
            if (value == null) continue;
            if (DENY.contains(value)) return exclude("access_denied");
            if (!ALLOW.contains(value)) return defer("access_" + value);
            break; // More specific vehicle permission overrides the general access tag.
        }
        String oneway = null;
        for (String key : ONEWAY) {
            if (tags.containsKey(key)) { oneway = tags.get(key); break; }
        }
        if (oneway == null) {
            boolean implicit = "motorway".equals(tags.get("highway"))
                    || "roundabout".equals(tags.get("junction"));
            return accept(implicit ? Direction.FORWARD : Direction.BOTH);
        }
        return switch (oneway) {
            case "yes", "true", "1" -> accept(Direction.FORWARD);
            case "-1" -> accept(Direction.REVERSE);
            case "no", "false", "0" -> accept(Direction.BOTH);
            default -> defer("oneway_" + oneway);
        };
    }

    private static Decision accept(Direction direction) { return new Decision(Status.ACCEPTED, direction, "accepted"); }
    private static Decision exclude(String reason) { return new Decision(Status.EXCLUDED, Direction.NONE, reason); }
    private static Decision defer(String reason) { return new Decision(Status.DEFERRED, Direction.NONE, reason); }
}
