package com.lsk.routing.core.graph;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SeoulPreparationTest {
    @TempDir Path dir;
    private Set<String> select(String name) throws Exception {
        Path source=Path.of(getClass().getResource("/osm/"+name+".osm.pbf").toURI());
        Path ids=dir.resolve(name+".ids"),report=dir.resolve(name+".tsv");
        SeoulPreparation.main(new String[]{source.toString(),ids.toString(),report.toString()});
        assertTrue(Files.readString(report).startsWith("kind\tid\treason"));
        return new HashSet<>(Files.readAllLines(ids));
    }
    @Test void keepsRoadsDespiteMalformedRelationAndKeepsOtherValidRules() throws Exception {
        assertEquals(Set.of("w10","w20","w30","w40","w50","w60","w70","w80","r101","r102"),select("seoul-selection"));
        String audit=Files.readString(dir.resolve("seoul-selection.tsv"));
        assertTrue(audit.contains("MISSING_FROM"));
        assertFalse(audit.contains("quarantined_member"));
        assertTrue(audit.contains("ignored_node_rule\t10"));
    }
    @Test void retainsSupportedViaRulesAndNodeBansTogether() throws Exception {
        var ids=select("via-only-node-ban");
        assertTrue(ids.containsAll(Set.of("r100","r101","w10","w20","w21","w30")));
    }
    @Test void excludesAmbiguousViaOverlap() throws Exception {
        var ids=select("via-overlap-from");
        assertTrue(ids.stream().noneMatch(id->id.startsWith("r")));
    }
    @Test void comparisonSubsetRetainsConditionalRoadWithBaselineDirection() throws Exception {
        var ids=select("conditional-road");
        assertTrue(ids.contains("w10"));assertTrue(ids.contains("w20"));
    }
}
