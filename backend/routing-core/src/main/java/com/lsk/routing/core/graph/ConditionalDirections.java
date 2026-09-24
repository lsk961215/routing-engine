package com.lsk.routing.core.graph;

import com.lsk.routing.core.osm.CarProfile.Direction;
import com.lsk.routing.core.osm.ConditionalOneway;
import java.io.*;
import java.time.*;
import java.util.*;

/** Immutable edge-to-weekly-rule bindings. Direction codes: 0 static, 1 forward, 2 reverse. */
public final class ConditionalDirections {
    private final List<ConditionalOneway> rules;
    private final int[] ruleIds;
    private final byte[] directions;
    private final ZoneId zone;
    public ConditionalDirections(ZoneId zone,List<ConditionalOneway> rules,int[] ruleIds,byte[] directions) {
        this.zone=Objects.requireNonNull(zone);this.rules=List.copyOf(rules);
        this.ruleIds=ruleIds.clone();this.directions=directions.clone();
        if(!zone.equals(ZoneId.of("Asia/Seoul")))throw new IllegalArgumentException("Unsupported graph time zone");
        if(rules.isEmpty() || rules.size()>10_000 || ruleIds.length!=directions.length)
            throw new IllegalArgumentException("Invalid conditional dimensions");
        var used=new boolean[rules.size()];
        for(int e=0;e<ruleIds.length;e++) {
            int id=ruleIds[e],direction=directions[e];
            if(id == -1) {
                if(direction!=0)throw new IllegalArgumentException("Static edge has conditional direction");
            } else {
                if(id<0 || id>=rules.size() || (direction!=1 && direction!=2))
                    throw new IllegalArgumentException("Invalid conditional edge binding");
                var rule=rules.get(id);
                if(!permits(rule.baseline(),direction) && !permits(rule.active(),direction))
                    throw new IllegalArgumentException("Edge never permitted by rule");
                used[id]=true;
            }
        }
        for(boolean referenced:used)if(!referenced)throw new IllegalArgumentException("Unreferenced conditional rule");
    }
    private static boolean permits(Direction direction,int edgeDirection) {
        return direction==Direction.BOTH || direction==Direction.FORWARD && edgeDirection==1
                || direction==Direction.REVERSE && edgeDirection==2;
    }
    public ZoneId timeZone() { return zone; }
    public List<ConditionalOneway> rules() { return rules; }
    public int edgeCount() { return ruleIds.length; }
    public int ruleId(int edge) { return ruleIds[edge]; }
    public int directionCode(int edge) { return directions[edge]; }
    public long serializedBytes() { return 2+10+4+24L*rules.size()+5L*edgeCount(); }
    void write(DataOutputStream out) throws IOException {
        out.writeUTF(zone.getId());out.writeInt(rules.size());
        for(var rule:rules) {
            out.writeInt(code(rule.baseline()));out.writeInt(code(rule.active()));
            out.writeInt(rule.firstDay().getValue());out.writeInt(rule.lastDay().getValue());
            out.writeInt(rule.start().toSecondOfDay()/60);out.writeInt(rule.end().toSecondOfDay()/60);
        }
        for(int e=0;e<edgeCount();e++){out.writeInt(ruleIds[e]);out.writeByte(directions[e]);}
    }
    private static int code(Direction d) {
        return switch(d){case BOTH->0;case FORWARD->1;case REVERSE->2;case NONE->throw new IllegalArgumentException("Invalid direction");};
    }
    private static Direction decode(int code) throws IOException {
        return switch(code){case 0->Direction.BOTH;case 1->Direction.FORWARD;case 2->Direction.REVERSE;default->throw new IOException("Invalid rule direction");};
    }
    static ConditionalDirections read(DataInputStream in,int edges) throws IOException {
        String zone=in.readUTF();
        if(!zone.equals("Asia/Seoul"))throw new IOException("Unsupported graph time zone");
        int count=in.readInt();
        if(count<1 || count>10_000 || 24L*count+5L*edges>in.available()-8)
            throw new IOException("Invalid conditional count or payload");
        var rules=new ArrayList<ConditionalOneway>();
        try {
            for(int i=0;i<count;i++) {
                var baseline=decode(in.readInt());var active=decode(in.readInt());
                var first=DayOfWeek.of(in.readInt());var last=DayOfWeek.of(in.readInt());
                int start=in.readInt(),end=in.readInt();
                if(start<0 || start>=1440 || end<0 || end>=1440)throw new IOException("Invalid rule minutes");
                rules.add(new ConditionalOneway(baseline,active,first,last,LocalTime.of(start/60,start%60),LocalTime.of(end/60,end%60)));
            }
            int[] ids=new int[edges];byte[] directions=new byte[edges];
            for(int e=0;e<edges;e++){ids[e]=in.readInt();directions[e]=in.readByte();}
            return new ConditionalDirections(ZoneId.of(zone),rules,ids,directions);
        } catch(DateTimeException | IllegalArgumentException e) {throw new IOException("Invalid conditional structure",e);}
    }
}
