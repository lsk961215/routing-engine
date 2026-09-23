package com.lsk.routing.core.osm;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.lsk.routing.core.osm.RestrictionValidator.*;

class RestrictionValidatorTest {
    private final RestrictionValidator validator = new RestrictionValidator();
    private final List<Member> members = List.of(new Member(Kind.WAY, 10, "from"),
            new Member(Kind.NODE, 2, "via"), new Member(Kind.WAY, 11, "to"));
    private Way way(long[] nodes, String oneway) {
        return new Way(nodes, new CarProfile().evaluate(Map.of("highway", "residential", "oneway", oneway), nodes.length));
    }
    private Map<Long, Way> ways() {
        return new HashMap<>(Map.of(10L, way(new long[]{1, 2}, "yes"), 11L, way(new long[]{2, 3}, "yes")));
    }
    private Restriction restriction(List<Member> members, String... pairs) {
        var tags = new HashMap<>(Map.of("type", "restriction", "restriction", "no_left_turn"));
        for (int i = 0; i < pairs.length; i += 2) tags.put(pairs[i], pairs[i + 1]);
        return new Restriction(100, tags, members);
    }
    private Set<Issue> check(Restriction r, Map<Long, Way> ways) {
        return validator.validate(r, ways, Set.of(2L), Set.of());
    }
    @Test void acceptsConnectedNodeViaCandidate() {
        assertTrue(check(restriction(members), ways()).isEmpty());
        assertTrue(check(restriction(members, "except", "bus; hgv"), ways()).isEmpty());
    }
    @Test void checksMissingReferencesAndMemberStructureIndependently() {
        var missing = ways(); missing.remove(11L);
        assertTrue(check(restriction(members), missing).contains(Issue.MISSING_REFERENCE));
        assertTrue(validator.validate(restriction(members), ways(), Set.of(), Set.of()).contains(Issue.MISSING_REFERENCE));
        var invalid = new ArrayList<>(members); invalid.add(new Member(Kind.RELATION, 999, ""));
        assertEquals(Set.of(Issue.INVALID_MEMBERS, Issue.MISSING_REFERENCE, Issue.UNKNOWN_ROLE), check(restriction(invalid), ways()));
    }
    @Test void distinguishesDisconnectedAndWrongDirection() {
        var ways = ways(); ways.put(11L, way(new long[]{4, 5}, "yes"));
        assertEquals(Set.of(Issue.DISCONNECTED_VIA), check(restriction(members), ways));
        ways.put(11L, way(new long[]{2, 3}, "-1"));
        assertEquals(Set.of(Issue.DIRECTION_CONFLICT), check(restriction(members), ways));
    }
    @Test void marksFilteredWays() {
        var ways = ways(); ways.put(11L, new Way(new long[]{2, 3},
                new CarProfile().evaluate(Map.of("highway", "footway"), 2)));
        assertEquals(Set.of(Issue.WAY_NOT_ACCEPTED), check(restriction(members), ways));
    }
    @Test void defersViaWaysInsteadOfClaimingSupported() {
        var viaWays = List.of(members.getFirst(), new Member(Kind.WAY, 12, "via"), members.getLast());
        var ways = ways(); ways.put(12L, way(new long[]{2, 3}, "no"));
        ways.put(11L, way(new long[]{3, 4}, "yes"));
        assertEquals(Set.of(Issue.VIA_WAY_DEFERRED), check(restriction(viaWays), ways));
    }
    @Test void respectsExceptionsConditionsAndModeSpecificValues() {
        assertEquals(Set.of(Issue.EXEMPT_CAR), check(restriction(members, "except", "motorcar"), ways()));
        assertEquals(Set.of(Issue.UNSUPPORTED_EXCEPTION), check(restriction(members, "except", "unknown"), ways()));
        assertEquals(Set.of(Issue.CONDITIONAL), check(restriction(members, "restriction:conditional", "no_left_turn @ wet"), ways()));
        assertTrue(check(restriction(members, "restriction", "unknown", "restriction:motorcar", "only_right_turn"), ways()).isEmpty());
        assertEquals(Set.of(Issue.UNSUPPORTED_VALUE), check(restriction(members, "restriction", "unknown"), ways()));
        assertEquals(Set.of(Issue.UNSUPPORTED_TYPE), check(restriction(members, "type", "restriction:hgv"), ways()));
    }
    @Test void rejectsDuplicateAndMissingRoles() {
        assertTrue(check(restriction(List.of(members.getFirst(), members.getLast())), ways()).contains(Issue.INVALID_MEMBERS));
        var duplicate = new ArrayList<>(members); duplicate.add(members.getFirst());
        assertTrue(check(restriction(duplicate), ways()).contains(Issue.INVALID_MEMBERS));
    }
    @Test void validatesViaWayChainsWithMultipleMembers() {
        var chain = List.of(members.getFirst(), new Member(Kind.WAY, 12, "via"),
                new Member(Kind.WAY, 13, "via"), members.getLast());
        var ways = ways();
        ways.put(12L, way(new long[]{2, 3}, "yes"));
        ways.put(13L, way(new long[]{4, 3}, "-1"));
        ways.put(11L, way(new long[]{4, 5}, "yes"));
        assertEquals(Set.of(Issue.VIA_WAY_DEFERRED), check(restriction(chain), ways));
        ways.put(13L, way(new long[]{4, 3}, "yes"));
        assertTrue(check(restriction(chain), ways).contains(Issue.DIRECTION_CONFLICT));
        ways.put(13L, way(new long[]{8, 9}, "no"));
        assertTrue(check(restriction(chain), ways).contains(Issue.DISCONNECTED_VIA));
    }
    @Test void viaWayCannotBeSkippedAtSameIntersection() {
        var chain = List.of(members.getFirst(), new Member(Kind.WAY, 12, "via"), members.getLast());
        var ways = ways();
        ways.put(12L, way(new long[]{2, 3}, "yes"));
        ways.put(11L, way(new long[]{2, 4}, "yes"));
        assertTrue(check(restriction(chain), ways).contains(Issue.DISCONNECTED_VIA));
        ways.put(12L, way(new long[]{2, 3, 2}, "yes"));
        assertEquals(Set.of(Issue.VIA_WAY_DEFERRED), check(restriction(chain), ways));
    }
    @Test void explainsMissingRoleAndDuplicateMember() {
        var absent = check(restriction(List.of(members.get(1), members.getLast())), ways());
        assertTrue(absent.contains(Issue.MISSING_FROM));
        var repeated = new ArrayList<>(members); repeated.add(members.get(1));
        assertTrue(check(restriction(repeated), ways()).contains(Issue.DUPLICATE_MEMBER));
    }
}
