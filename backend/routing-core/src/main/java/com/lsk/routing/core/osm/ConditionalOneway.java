package com.lsk.routing.core.osm;

import java.time.*;
import java.util.*;
import java.util.regex.Pattern;

/** Narrow, immutable weekly direction rule. Not yet connected to graph building or routing. */
public record ConditionalOneway(CarProfile.Direction baseline, CarProfile.Direction active,
                                DayOfWeek firstDay, DayOfWeek lastDay,
                                LocalTime start, LocalTime end) {
    public static final String KEY = "oneway:conditional";
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final List<String> DAYS = List.of("Mo", "Tu", "We", "Th", "Fr", "Sa", "Su");
    private static final Pattern VALUE = Pattern.compile(
            "(yes|no|-1)[ \\t]*@[ \\t]*(.+)");
    private static final Pattern SCHEDULE = Pattern.compile(
            "(Mo|Tu|We|Th|Fr|Sa|Su)(?:-(Mo|Tu|We|Th|Fr|Sa|Su))?[ \\t]+([0-9]{2}):([0-9]{2})-([0-9]{2}):([0-9]{2})");

    public ConditionalOneway {
        Objects.requireNonNull(baseline); Objects.requireNonNull(active);
        Objects.requireNonNull(firstDay); Objects.requireNonNull(lastDay);
        Objects.requireNonNull(start); Objects.requireNonNull(end);
        if (baseline == CarProfile.Direction.NONE || active == CarProfile.Direction.NONE)
            throw new IllegalArgumentException("Direction NONE is not a weekly oneway value");
        if (firstDay.getValue() > lastDay.getValue() || !start.isBefore(end)
                || start.getSecond() != 0 || end.getSecond() != 0 || start.getNano() != 0 || end.getNano() != 0)
            throw new IllegalArgumentException("Expected ascending days and same-day minute interval");
    }

    public static final class UnsupportedRuleException extends IllegalArgumentException {
        private final long wayId;
        private final String key, rawValue, reason;
        public UnsupportedRuleException(long wayId, String key, String rawValue, String reason) {
            super("Way " + wayId + " " + key + "=" + rawValue + ": " + reason);
            this.wayId=wayId;this.key=key;this.rawValue=rawValue;this.reason=reason;
        }
        public long wayId() { return wayId; }
        public String key() { return key; }
        public String rawValue() { return rawValue; }
        public String reason() { return reason; }
    }

    /** Validates the complete tag combination; does not silently discard other conditional keys. */
    public static ConditionalOneway parse(long wayId, Map<String,String> tags, int nodeCount) {
        Objects.requireNonNull(tags);
        for (String key : new TreeSet<>(tags.keySet())) {
            if (!key.equals(KEY) && (key.startsWith("oneway:") || key.contains(":conditional")))
                throw error(wayId,key,tags.get(key),"Unsupported modifier combination");
        }
        String raw=tags.get(KEY), base=tags.get("oneway");
        if (raw == null) throw error(wayId,KEY,null,"Explicit conditional rule required");
        if (!Set.of("yes","no","-1").contains(base == null ? "" : base))
            throw error(wayId,"oneway",base,"Explicit yes/no/-1 baseline required");
        var staticTags=new HashMap<>(tags);staticTags.remove(KEY);
        var decision=new CarProfile().evaluate(staticTags,nodeCount);
        if (!decision.accepted()) throw error(wayId,KEY,raw,"Unsupported static road profile: " + decision.reason());
        if (raw.length()>256) throw error(wayId,KEY,raw,"Rule exceeds 256 characters");
        var value=VALUE.matcher(raw);
        if (!value.matches()) throw error(wayId,KEY,raw,"Expected one value @ weekly condition");
        String condition=value.group(2);
        if (condition.startsWith("(") && condition.endsWith(")"))
            condition=condition.substring(1,condition.length()-1);
        var schedule=SCHEDULE.matcher(condition);
        if (!schedule.matches()) throw error(wayId,KEY,raw,"Unsupported weekly condition syntax");
        DayOfWeek first=DayOfWeek.of(DAYS.indexOf(schedule.group(1))+1);
        DayOfWeek last=schedule.group(2)==null ? first : DayOfWeek.of(DAYS.indexOf(schedule.group(2))+1);
        try {
            LocalTime start=LocalTime.of(Integer.parseInt(schedule.group(3)),Integer.parseInt(schedule.group(4)));
            LocalTime end=LocalTime.of(Integer.parseInt(schedule.group(5)),Integer.parseInt(schedule.group(6)));
            return new ConditionalOneway(direction(base),direction(value.group(1)),first,last,start,end);
        } catch (DateTimeException | IllegalArgumentException e) {
            throw error(wayId,KEY,raw,"Invalid day/time interval: " + e.getMessage());
        }
    }
    private static UnsupportedRuleException error(long id,String key,String raw,String reason) {
        return new UnsupportedRuleException(id,key,raw,reason);
    }
    private static CarProfile.Direction direction(String value) {
        return switch(value) {
            case "yes" -> CarProfile.Direction.FORWARD;
            case "no" -> CarProfile.Direction.BOTH;
            case "-1" -> CarProfile.Direction.REVERSE;
            default -> throw new IllegalArgumentException("Unsupported direction");
        };
    }
    /** Snapshot evaluation, not arrival-time evaluation. Caller supplies the graph's zone. */
    public CarProfile.Direction evaluate(Instant snapshotAt, ZoneId graphZone) {
        Objects.requireNonNull(snapshotAt); Objects.requireNonNull(graphZone);
        if (!SEOUL.equals(graphZone)) throw new IllegalArgumentException("Only Asia/Seoul is supported");
        var local=snapshotAt.atZone(graphZone);
        int day=local.getDayOfWeek().getValue();var time=local.toLocalTime();
        return day>=firstDay.getValue() && day<=lastDay.getValue()
                && !time.isBefore(start) && time.isBefore(end) ? active : baseline;
    }
}
