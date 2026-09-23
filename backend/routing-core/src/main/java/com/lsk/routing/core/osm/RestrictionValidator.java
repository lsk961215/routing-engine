package com.lsk.routing.core.osm;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Reference and structure checks; graph transitions are deliberately not generated here. */
public final class RestrictionValidator {
    public enum Kind { NODE, WAY, RELATION }
    public record Member(Kind kind, long id, String role) {}
    public record Restriction(long id, Map<String, String> tags, List<Member> members) {
        public Restriction { tags = Map.copyOf(tags); members = List.copyOf(members); }
    }
    public record Way(long[] nodes, CarProfile.Decision decision) {}
    public enum Issue {
        MISSING_REFERENCE, INVALID_MEMBERS, UNSUPPORTED_TYPE, UNSUPPORTED_VALUE,
        CONDITIONAL, UNSUPPORTED_EXCEPTION, EXEMPT_CAR, VIA_WAY_DEFERRED,
        WAY_NOT_ACCEPTED, DISCONNECTED_VIA, DIRECTION_CONFLICT,
        MISSING_FROM, MISSING_TO, MISSING_VIA, DUPLICATE_MEMBER, UNKNOWN_ROLE
    }
    private static final Set<String> VALUES = Set.of("no_left_turn", "no_right_turn", "no_straight_on",
            "no_u_turn", "only_left_turn", "only_right_turn", "only_straight_on");
    private static final List<String> MODES = List.of("motorcar", "motor_vehicle", "vehicle");
    private static final Set<String> OTHER_EXCEPTIONS = Set.of("bus", "psv", "taxi", "bicycle", "hgv",
            "motorcycle", "foot", "emergency", "agricultural", "delivery");

    public Set<Issue> validate(Restriction restriction, Map<Long, Way> ways,
                               Set<Long> existingNodes, Set<Long> existingRelations) {
        var issues = EnumSet.noneOf(Issue.class);
        var tags = restriction.tags();
        String type = tags.getOrDefault("type", "");
        if (!type.equals("restriction") && !MODES.stream().anyMatch(mode -> type.equals("restriction:" + mode))) {
            issues.add(Issue.UNSUPPORTED_TYPE);
        }
        String value = null;
        for (String mode : MODES) {
            if (tags.containsKey("restriction:" + mode)) { value = tags.get("restriction:" + mode); break; }
        }
        if (value == null) value = tags.get("restriction");
        if (value == null || !VALUES.contains(value)) issues.add(Issue.UNSUPPORTED_VALUE);
        if (tags.keySet().stream().anyMatch(key -> key.startsWith("restriction") && key.contains(":conditional"))) {
            issues.add(Issue.CONDITIONAL);
        }
        if (tags.containsKey("except")) {
            for (String exception : tags.get("except").split(";", -1)) {
                String mode = exception.trim();
                if (MODES.contains(mode)) issues.add(Issue.EXEMPT_CAR);
                else if (!OTHER_EXCEPTIONS.contains(mode)) issues.add(Issue.UNSUPPORTED_EXCEPTION);
            }
        }
        var from = restriction.members().stream().filter(m -> m.role().equals("from")).toList();
        var to = restriction.members().stream().filter(m -> m.role().equals("to")).toList();
        var via = restriction.members().stream().filter(m -> m.role().equals("via")).toList();
        if (from.isEmpty()) issues.add(Issue.MISSING_FROM);
        if (to.isEmpty()) issues.add(Issue.MISSING_TO);
        if (via.isEmpty()) issues.add(Issue.MISSING_VIA);
        if (new HashSet<>(restriction.members()).size() != restriction.members().size()) {
            issues.add(Issue.DUPLICATE_MEMBER);
        }
        if (restriction.members().stream().anyMatch(m -> !Set.of("from", "to", "via").contains(m.role()))) {
            issues.add(Issue.UNKNOWN_ROLE);
        }
        boolean nodeVia = via.size() == 1 && via.getFirst().kind() == Kind.NODE;
        boolean wayVia = !via.isEmpty() && via.stream().allMatch(m -> m.kind() == Kind.WAY);
        boolean valid = from.size() == 1 && to.size() == 1
                && from.getFirst().kind() == Kind.WAY && to.getFirst().kind() == Kind.WAY
                && (nodeVia || wayVia) && !issues.contains(Issue.DUPLICATE_MEMBER)
                && from.size() + to.size() + via.size() == restriction.members().size();
        if (!valid) issues.add(Issue.INVALID_MEMBERS);
        if (wayVia) issues.add(Issue.VIA_WAY_DEFERRED);
        for (Member member : restriction.members()) {
            boolean exists = switch (member.kind()) {
                case NODE -> existingNodes.contains(member.id());
                case WAY -> ways.containsKey(member.id());
                case RELATION -> existingRelations.contains(member.id());
            };
            if (!exists) issues.add(Issue.MISSING_REFERENCE);
            if (member.kind() == Kind.WAY && exists && !ways.get(member.id()).decision().accepted()) {
                issues.add(Issue.WAY_NOT_ACCEPTED);
            }
        }
        if (valid && nodeVia && !issues.contains(Issue.MISSING_REFERENCE)) {
            long node = via.getFirst().id();
            Way incoming = ways.get(from.getFirst().id()), outgoing = ways.get(to.getFirst().id());
            if (!contains(incoming.nodes(), node) || !contains(outgoing.nodes(), node)) {
                issues.add(Issue.DISCONNECTED_VIA);
            } else if (incoming.decision().accepted() && outgoing.decision().accepted()
                    && (!canTravel(incoming, node, true) || !canTravel(outgoing, node, false))) {
                issues.add(Issue.DIRECTION_CONFLICT);
            }
        }
        if (valid && wayVia && !issues.contains(Issue.MISSING_REFERENCE)) {
            var chain = new ArrayList<Way>();
            chain.add(ways.get(from.getFirst().id()));
            for (Member member : via) chain.add(ways.get(member.id()));
            chain.add(ways.get(to.getFirst().id()));
            if (!connectedChain(chain, false)) issues.add(Issue.DISCONNECTED_VIA);
            else if (!issues.contains(Issue.WAY_NOT_ACCEPTED) && !connectedChain(chain, true)) {
                issues.add(Issue.DIRECTION_CONFLICT);
            }
        }
        return Set.copyOf(issues);
    }

    // Propagate reachable boundary nodes through via ways in relation-member order.
    // Each via way must contribute at least one non-self segment; do not jump across
    // disconnected intersections or silently reorder malformed member lists.
    private static boolean connectedChain(List<Way> chain, boolean directed) {
        Way first = chain.getFirst();
        var reachable = new HashSet<Long>();
        for (long node : first.nodes()) {
            if (canTravel(first, node, true, directed)) reachable.add(node);
        }
        for (int w = 1; w < chain.size() - 1; w++) {
            Way way = chain.get(w);
            var next = new HashSet<Long>();
            boolean forward = !directed || way.decision().direction() != CarProfile.Direction.REVERSE;
            boolean reverse = !directed || way.decision().direction() != CarProfile.Direction.FORWARD;
            if (forward) collectReachable(way.nodes(), reachable, next, false);
            if (reverse) collectReachable(way.nodes(), reachable, next, true);
            reachable = next;
            if (reachable.isEmpty()) return false;
        }
        Way last = chain.getLast();
        for (long node : last.nodes()) {
            if (reachable.contains(node) && canTravel(last, node, false, directed)) return true;
        }
        return false;
    }

    private static void collectReachable(long[] nodes, Set<Long> entry, Set<Long> output, boolean reverse) {
        boolean active = false, moved = false;
        for (int k = 0; k < nodes.length; k++) {
            int i = reverse ? nodes.length - 1 - k : k;
            if (k > 0 && active && nodes[i] != nodes[reverse ? i + 1 : i - 1]) moved = true;
            if (moved) output.add(nodes[i]);
            if (entry.contains(nodes[i])) active = true;
        }
    }

    private static boolean contains(long[] nodes, long target) {
        for (long node : nodes) if (node == target) return true;
        return false;
    }

    private static boolean canTravel(Way way, long via, boolean arriving) {
        return canTravel(way, via, arriving, true);
    }

    private static boolean canTravel(Way way, long via, boolean arriving, boolean directed) {
        var direction = directed ? way.decision().direction() : CarProfile.Direction.BOTH;
        for (int i = 0; i + 1 < way.nodes().length; i++) {
            long a = way.nodes()[i], b = way.nodes()[i + 1];
            if (a == b) continue;
            if ((direction == CarProfile.Direction.BOTH || direction == CarProfile.Direction.FORWARD)
                    && (arriving ? b == via : a == via)) return true;
            if ((direction == CarProfile.Direction.BOTH || direction == CarProfile.Direction.REVERSE)
                    && (arriving ? a == via : b == via)) return true;
        }
        return false;
    }
}
