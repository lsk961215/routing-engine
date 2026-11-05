package com.lsk.routing.api.benchmark;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ExportDemoTracesTest {
    @Test void samplingKeepsTheFirstRealLocationAndOriginalOrdinalWithinEachCell() {
        var sampler=new ExportDemoTraces.Sampler();
        sampler.expanded(127,37,1);
        sampler.expanded(127,37,17);
        sampler.expanded(127.01,37.01,81);
        assertEquals(2,sampler.points.size());
        assertEquals(127.0,sampler.points.getFirst().get(0));
        assertEquals(37.0,sampler.points.getFirst().get(1));
        assertEquals(1L,sampler.points.getFirst().get(2));
        assertEquals(81L,sampler.points.getLast().get(2));
        assertEquals(java.util.List.of(127.0,37.0,127.01,37.01),sampler.bounds());
    }
}
