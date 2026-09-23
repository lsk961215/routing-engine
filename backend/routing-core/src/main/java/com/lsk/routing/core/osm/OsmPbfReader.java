package com.lsk.routing.core.osm;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import de.topobyte.osm4j.core.model.iface.OsmEntity;
import de.topobyte.osm4j.core.model.iface.OsmNode;
import de.topobyte.osm4j.core.model.iface.OsmRelation;
import de.topobyte.osm4j.core.model.iface.OsmWay;
import de.topobyte.osm4j.pbf.seq.PbfIterator;

public class OsmPbfReader {
    private static final Set<String> ROAD_TAGS = Set.of(
            "highway", "access", "vehicle", "motor_vehicle", "motorcar",
            "oneway", "junction", "maxspeed", "area", "service");

    public OsmLoadResult read(Path pbfPath) throws IOException {
        long nodes = 0, ways = 0, relations = 0, highways = 0, segments = 0, shortWays = 0;
        long restrictions = 0;
        double minLon = Double.POSITIVE_INFINITY, minLat = Double.POSITIVE_INFINITY;
        double maxLon = Double.NEGATIVE_INFINITY, maxLat = Double.NEGATIVE_INFINITY;
        var roadTags = new TreeMap<String, Map<String, Long>>();
        var restrictionTags = new TreeMap<String, Map<String, Long>>();
        var memberTypes = new TreeMap<String, Long>();
        var references = new NodeReferences();

        try (var input = Files.newInputStream(pbfPath)) {
            var iterator = new PbfIterator(input, false);
            while (iterator.hasNext()) {
                var container = iterator.next();
                switch (container.getType()) {
                    case Node -> {
                        nodes++;
                        var node = (OsmNode) container.getEntity();
                        minLon = Math.min(minLon, node.getLongitude());
                        maxLon = Math.max(maxLon, node.getLongitude());
                        minLat = Math.min(minLat, node.getLatitude());
                        maxLat = Math.max(maxLat, node.getLatitude());
                    }
                    case Way -> {
                        ways++;
                        var way = (OsmWay) container.getEntity();
                        if (tag(way, "highway") == null) continue;
                        highways++;
                        collect(way, roadTags, false);
                        int count = way.getNumberOfNodes();
                        if (count < 2) shortWays++;
                        segments += Math.max(0, count - 1);
                        for (int i = 0; i < count; i++) references.add(way.getNodeId(i));
                    }
                    case Relation -> {
                        relations++;
                        var relation = (OsmRelation) container.getEntity();
                        String type = tag(relation, "type");
                        if (type == null || !(type.equals("restriction") || type.startsWith("restriction:"))) continue;
                        restrictions++;
                        collect(relation, restrictionTags, true);
                        for (int i = 0; i < relation.getNumberOfMembers(); i++) {
                            var member = relation.getMember(i);
                            memberTypes.merge(member.getRole() + ":" + member.getType(), 1L, Long::sum);
                        }
                    }
                }
            }
        }
        var bounds = nodes == 0 ? null : new OsmLoadResult.Bounds(minLon, minLat, maxLon, maxLat);
        return new OsmLoadResult(nodes, ways, relations, highways, bounds, references.size,
                references.uniqueCount(), segments, shortWays, restrictions,
                roadTags, restrictionTags, memberTypes);
    }

    private static String tag(OsmEntity entity, String key) {
        for (int i = 0; i < entity.getNumberOfTags(); i++) {
            var tag = entity.getTag(i);
            if (key.equals(tag.getKey())) return tag.getValue();
        }
        return null;
    }

    private static void collect(OsmEntity entity, Map<String, Map<String, Long>> output, boolean restriction) {
        for (int i = 0; i < entity.getNumberOfTags(); i++) {
            var tag = entity.getTag(i);
            String key = tag.getKey();
            boolean included = restriction
                    ? key.equals("type") || key.equals("except") || key.equals("restriction") || key.startsWith("restriction:")
                    : ROAD_TAGS.stream().anyMatch(base -> key.equals(base) || key.startsWith(base + ":"));
            if (included) output.computeIfAbsent(key, ignored -> new TreeMap<>())
                    .merge(tag.getValue(), 1L, Long::sum);
        }
    }

    // Analysis-only buffer: retain primitive IDs, not all parsed OSM objects.
    // Sorting counts distinct references without a boxed HashSet<Long>.
    private static final class NodeReferences {
        private long[] ids = new long[1024];
        private int size;

        void add(long id) {
            if (size == ids.length) ids = Arrays.copyOf(ids, Math.multiplyExact(ids.length, 2));
            ids[size++] = id;
        }

        long uniqueCount() {
            Arrays.sort(ids, 0, size);
            long count = 0;
            for (int i = 0; i < size; i++) {
                if (i == 0 || ids[i] != ids[i - 1]) count++;
            }
            return count;
        }
    }
}
