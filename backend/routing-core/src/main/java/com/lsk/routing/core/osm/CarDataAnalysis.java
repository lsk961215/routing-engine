package com.lsk.routing.core.osm;

import de.topobyte.osm4j.core.model.iface.*;
import de.topobyte.osm4j.pbf.seq.PbfIterator;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Two-pass, offline analysis. Keeps road node IDs and only restriction-referenced way geometry. */
public final class CarDataAnalysis {
    public record Result(long highwayWays, long acceptedWays, long uniqueNodes, long directedSegments,
                         Map<String, Long> decisions, long restrictions, long candidateNodeVia, long connectedViaWay,
                         Map<String, Long> issues, Map<String, List<Long>> examples, List<Long> directionBlockedNoRestrictions) {
        public Result {
            directionBlockedNoRestrictions = List.copyOf(directionBlockedNoRestrictions);
            decisions = Collections.unmodifiableMap(new TreeMap<>(decisions));
            issues = Collections.unmodifiableMap(new TreeMap<>(issues));
            var copy = new TreeMap<String, List<Long>>();
            examples.forEach((key, ids) -> copy.put(key, List.copyOf(ids)));
            examples = Collections.unmodifiableMap(copy);
        }
    }

    public Result read(Path path) throws IOException { return read(path,false); }
    public Result readForGraph(Path path) throws IOException {
        try { return read(path,true); }
        catch(ConditionalOneway.UnsupportedRuleException e) { throw new IOException(e.getMessage(),e); }
    }
    public Result readForComparison(Path path) throws IOException { return read(path,false,true); }
    private Result read(Path path, boolean temporalUnion) throws IOException { return read(path,temporalUnion,false); }
    private Result read(Path path, boolean temporalUnion, boolean comparison) throws IOException {
        var profile = new CarProfile();
        var conditionalWays = new HashSet<Long>();
        var decisions = new TreeMap<String, Long>();
        var restrictions = new ArrayList<RestrictionValidator.Restriction>();
        var referencedNodes = new HashSet<Long>();
        var referencedWays = new HashSet<Long>();
        var referencedRelations = new HashSet<Long>();
        var ids = new LongIds();
        long highways = 0, accepted = 0, directed = 0;
        try (var input = Files.newInputStream(path)) {
            var iterator = new PbfIterator(input, false);
            while (iterator.hasNext()) {
                var entity = iterator.next();
                if (entity.getType() == EntityType.Way) {
                    var way = (OsmWay) entity.getEntity();
                    var tags = tags(way);
                    if(comparison)tags=ComparisonProfile.normalize(tags);
                    if (!tags.containsKey("highway")) continue;
                    highways++;
                    var decision = temporalUnion ? RoadDirections.forGraph(way.getId(),tags,way.getNumberOfNodes()).decision() : profile.evaluate(tags, way.getNumberOfNodes());
                    decisions.merge(decision.status() + ":" + decision.reason(), 1L, Long::sum);
                    if (!decision.accepted()) continue;
                    accepted++;
                    for (int i = 0; i < way.getNumberOfNodes(); i++) {
                        ids.add(way.getNodeId(i));
                        if (i > 0 && way.getNodeId(i - 1) != way.getNodeId(i)) {
                            directed += decision.direction() == CarProfile.Direction.BOTH ? 2 : 1;
                        }
                    }
                } else if (entity.getType() == EntityType.Relation) {
                    var relation = (OsmRelation) entity.getEntity();
                    var tags = tags(relation);
                    String type = tags.getOrDefault("type", "");
                    if (!type.equals("restriction") && !type.startsWith("restriction:")) continue;
                    var members = new ArrayList<RestrictionValidator.Member>();
                    for (int i = 0; i < relation.getNumberOfMembers(); i++) {
                        var member = relation.getMember(i);
                        var kind = switch (member.getType()) {
                            case Node -> RestrictionValidator.Kind.NODE;
                            case Way -> RestrictionValidator.Kind.WAY;
                            case Relation -> RestrictionValidator.Kind.RELATION;
                        };
                        switch (kind) {
                            case NODE -> referencedNodes.add(member.getId());
                            case WAY -> referencedWays.add(member.getId());
                            case RELATION -> referencedRelations.add(member.getId());
                        }
                        members.add(new RestrictionValidator.Member(kind, member.getId(), member.getRole()));
                    }
                    restrictions.add(new RestrictionValidator.Restriction(relation.getId(), tags, members));
                }
            }
        }
        var ways = new HashMap<Long, RestrictionValidator.Way>();
        var existingNodes = new HashSet<Long>();
        var existingRelations = new HashSet<Long>();
        try (var input = Files.newInputStream(path)) {
            var iterator = new PbfIterator(input, false);
            while (iterator.hasNext()) {
                var entity = iterator.next();
                long id = entity.getEntity().getId();
                switch (entity.getType()) {
                    case Node -> { if (referencedNodes.contains(id)) existingNodes.add(id); }
                    case Relation -> { if (referencedRelations.contains(id)) existingRelations.add(id); }
                    case Way -> {
                        if (!referencedWays.contains(id)) continue;
                        var way = (OsmWay) entity.getEntity();
                        long[] nodes = new long[way.getNumberOfNodes()];
                        for (int i = 0; i < nodes.length; i++) nodes[i] = way.getNodeId(i);
                        var roadTags=tags(way);
                        if(comparison)roadTags=ComparisonProfile.normalize(roadTags);
                        if(roadTags.containsKey(ConditionalOneway.KEY))conditionalWays.add(id);
                        var decision=temporalUnion?RoadDirections.forGraph(id,roadTags,nodes.length).decision():profile.evaluate(roadTags,nodes.length);
                        ways.put(id, new RestrictionValidator.Way(nodes, decision));
                    }
                }
            }
        }
        var validator = new RestrictionValidator();
        var counts = new TreeMap<String, Long>();
        var examples = new TreeMap<String, List<Long>>();
        long candidates = 0, connectedViaWays = 0;
        var directionBlockedNoRestrictions = new ArrayList<Long>();
        for (var restriction : restrictions) {
            var issues = validator.validate(restriction, ways, existingNodes, existingRelations);
            if (restriction.members().stream().noneMatch(m -> m.kind()==RestrictionValidator.Kind.WAY && conditionalWays.contains(m.id()))
                    && validator.isDirectionBlockedNo(restriction, issues)) directionBlockedNoRestrictions.add(restriction.id());
            if (issues.isEmpty()) candidates++;
            if (issues.equals(Set.of(RestrictionValidator.Issue.VIA_WAY_DEFERRED))) connectedViaWays++;
            for (var issue : issues) {
                counts.merge(issue.name(), 1L, Long::sum);
                var list = examples.computeIfAbsent(issue.name(), ignored -> new ArrayList<>());
                if (list.size() < 5) list.add(restriction.id());
            }
        }
        return new Result(highways, accepted, ids.uniqueCount(), directed, decisions,
                restrictions.size(), candidates, connectedViaWays, counts, examples, directionBlockedNoRestrictions);
    }

    private static Map<String, String> tags(OsmEntity entity) {
        var tags = new HashMap<String, String>();
        for (int i = 0; i < entity.getNumberOfTags(); i++) {
            var tag = entity.getTag(i);
            tags.put(tag.getKey(), tag.getValue());
        }
        return tags;
    }

    private static final class LongIds {
        private long[] ids = new long[1024];
        private int size;
        void add(long id) {
            if (size == ids.length) ids = Arrays.copyOf(ids, Math.multiplyExact(ids.length, 2));
            ids[size++] = id;
        }
        long uniqueCount() {
            Arrays.sort(ids, 0, size);
            long count = 0;
            for (int i = 0; i < size; i++) if (i == 0 || ids[i] != ids[i - 1]) count++;
            return count;
        }
    }
}
