package com.lsk.routing.core.osm;

import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.lsk.routing.core.osm.CarProfile.Direction.*;

class ConditionalOnewayTest {
    private static final ZoneId ZONE=ZoneId.of("Asia/Seoul");
    private Map<String,String> tags(String base,String value) {
        return new HashMap<>(Map.of("highway","residential","oneway",base,ConditionalOneway.KEY,value));
    }
    private ConditionalOneway rule(String base,String value) { return ConditionalOneway.parse(42,tags(base,value),2); }
    private CarProfile.Direction at(ConditionalOneway rule,String timestamp) {
        return rule.evaluate(OffsetDateTime.parse(timestamp).toInstant(),ZONE);
    }
    @Test void weeklyBoundaryIsStartInclusiveEndExclusiveToNanosecond() {
        var r=rule("no","yes @ (Mo-Fr 07:00-10:00)");
        assertEquals(BOTH,at(r,"2026-09-24T06:59:59.999999999+09:00"));
        assertEquals(FORWARD,at(r,"2026-09-24T07:00:00+09:00"));
        assertEquals(FORWARD,at(r,"2026-09-24T09:59:59.999999999+09:00"));
        assertEquals(BOTH,at(r,"2026-09-24T10:00:00+09:00"));
        assertEquals(BOTH,at(r,"2026-09-26T08:00:00+09:00"));
        assertEquals(FORWARD,at(r,"2026-09-28T07:00:00+09:00"));
    }
    @Test void instantUsesGraphZoneIncludingDifferentUtcCalendarDate() {
        var r=rule("yes","no @ Mo 07:00-10:00");
        assertEquals(BOTH,at(r,"2026-09-27T22:00:00Z"));
        assertEquals(at(r,"2026-09-27T22:00:00Z"),at(r,"2026-09-28T07:00:00+09:00"));
        assertThrows(IllegalArgumentException.class,()->r.evaluate(Instant.EPOCH,ZoneId.of("UTC")));
    }
    @Test void supportsAllThreeValuesAndRestoresBaseline() {
        for (String base:List.of("yes","no","-1")) for (String active:List.of("yes","no","-1")) {
            var r=rule(base,active+" @ Su 00:00-01:00");
            var directions=Map.of("yes",FORWARD,"no",BOTH,"-1",REVERSE);
            assertEquals(directions.get(active),at(r,"2026-09-27T00:00:00+09:00"));
            assertEquals(directions.get(base),at(r,"2026-09-27T01:00:00+09:00"));
        }
    }
    @Test void rejectsPartialAndUnsupportedGrammarWithSourceContext() {
        for(String value:List.of("yes @ (Mo-Fr 07:00-10:00); no @ Sa 07:00-10:00",
                "yes @ (Mo-Fr 07:00-10:00", "yes @ Mo-Fr 07:00-10:00)",
                "yes @ PH 07:00-10:00", "yes @ Fr-Mo 07:00-10:00", "yes @ Mo,We 07:00-10:00",
                "yes @ Mo 22:00-06:00", "yes @ Mo 00:00-24:00", "yes @ Mo 07:00-07:00",
                "yes @ Mo 07:60-10:00", "yes @ Mo 7:00-10:00", "yes @ wet",
                "yes @ ((Mo 07:00-10:00))", "yes @ Mo 07:00-10:00 trailing", "true @ Mo 07:00-10:00",
                "yes @ Mo 07:00-10:00\n", "yes @ "+"x".repeat(257))) {
            var error=assertThrows(ConditionalOneway.UnsupportedRuleException.class,()->rule("no",value),value);
            assertEquals(42,error.wayId());assertEquals(value,error.rawValue());
            assertEquals(ConditionalOneway.KEY,error.key());assertFalse(error.reason().isBlank());
        }
    }
    @Test void rejectsModifierCombinationsAndMissingBaseline() {
        for(String key:List.of("oneway:forward:conditional","oneway:motorcar","oneway:bicycle",
                "access:conditional","maxspeed:conditional","restriction:conditional")) {
            var tags=tags("no","yes @ Mo 07:00-10:00");tags.put(key,"yes");
            var error=assertThrows(ConditionalOneway.UnsupportedRuleException.class,()->ConditionalOneway.parse(42,tags,2));
            assertEquals(key,error.key());
        }
        var tags=tags("no","yes @ Mo 07:00-10:00");tags.remove("oneway");
        assertThrows(ConditionalOneway.UnsupportedRuleException.class,()->ConditionalOneway.parse(42,tags,2));
        assertThrows(ConditionalOneway.UnsupportedRuleException.class,()->rule("true","yes @ Mo 07:00-10:00"));
    }
    @Test void keepsRoadProfileGuardsAndDoesNotEnableGraphSupport() {
        var tags=tags("no","yes @ Mo 07:00-10:00");
        assertEquals(CarProfile.Status.DEFERRED,new CarProfile().evaluate(tags,2).status());
        tags.put("access","private");
        assertThrows(ConditionalOneway.UnsupportedRuleException.class,()->ConditionalOneway.parse(42,tags,2));
        tags.remove("access");tags.put("highway","footway");
        assertThrows(ConditionalOneway.UnsupportedRuleException.class,()->ConditionalOneway.parse(42,tags,2));
    }
    @Test void ruleIsIndependentOfMutableInputAndConcurrentRequests() throws Exception {
        var tags=tags("no","-1 @ Mo-Fr 07:00-10:00");var r=ConditionalOneway.parse(42,tags,2);tags.clear();
        try(var pool=Executors.newFixedThreadPool(4)) {
            var futures=new ArrayList<Future<CarProfile.Direction>>();
            for(int i=0;i<100;i++) { final boolean active=i%2==0;
                futures.add(pool.submit(()->at(r,active?"2026-09-24T08:00:00+09:00":"2026-09-24T11:00:00+09:00")));
            }
            for(int i=0;i<100;i++)assertEquals(i%2==0?REVERSE:BOTH,futures.get(i).get());
        }
    }
    @Test void directConstructionCannotBypassIntervalValidation() {
        assertThrows(IllegalArgumentException.class,()->new ConditionalOneway(BOTH,NONE,DayOfWeek.MONDAY,DayOfWeek.FRIDAY,LocalTime.of(7,0),LocalTime.of(10,0)));
        assertThrows(IllegalArgumentException.class,()->new ConditionalOneway(BOTH,FORWARD,DayOfWeek.MONDAY,DayOfWeek.FRIDAY,LocalTime.of(7,0,1),LocalTime.of(10,0)));
    }
}
