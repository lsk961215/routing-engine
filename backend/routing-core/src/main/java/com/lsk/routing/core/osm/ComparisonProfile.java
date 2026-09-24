package com.lsk.routing.core.osm;

import java.util.*;

/** Static comparison policy: keep uncertain roads, use known directions, ignore unsupported modifiers. */
public final class ComparisonProfile {
    private static final List<String> ACCESS=List.of("motorcar","motor_vehicle","vehicle","access");
    private static final List<String> ONEWAY=List.of("oneway:motorcar","oneway:motor_vehicle","oneway");
    public static Map<String,String> normalize(Map<String,String> tags) {
        var result=new HashMap<>(tags);
        result.keySet().removeIf(k->ACCESS.stream().anyMatch(a->k.equals(a)||k.startsWith(a+":")) || k.startsWith("oneway"));
        String access=access(tags);
        if(access!=null)result.put("access",access.equals("no")?"no":"yes");
        for(String key:ONEWAY)if(tags.containsKey(key)) {
            String value=tags.get(key);
            if(Set.of("yes","true","1","-1","no","false","0").contains(value)){result.put("oneway",value);break;}
        }
        // Unclassified road/track geometry is useful in comparisons; non-car highway types stay excluded.
        if(Set.of("road","track").contains(tags.getOrDefault("highway","")))result.put("highway","unclassified");
        return result;
    }
    public static String ignoredRules(Map<String,String> tags) {
        var ignored=new TreeMap<String,String>();
        tags.forEach((key,value)->{
            if(ACCESS.stream().anyMatch(a->key.startsWith(a+":"))
                    || ACCESS.contains(key) && !Set.of("no","yes","permissive","designated").contains(value)
                    || key.startsWith("oneway:") && !ONEWAY.contains(key)
                    || ONEWAY.contains(key) && !Set.of("yes","true","1","-1","no","false","0").contains(value))ignored.put(key,value);
        });
        return ignored.toString().replace('\t',' ').replace('\n',' ');
    }
    public static CarProfile.Decision evaluate(Map<String,String> tags,int count) {
        return new CarProfile().evaluate(normalize(tags),count);
    }
    public static boolean blockedNode(Map<String,String> tags) {
        String access=access(tags);
        if("no".equals(access))return true;
        if(access!=null && Set.of("yes","permissive","designated").contains(access))return false;
        return Set.of("bollard","block","wall","fence","jersey_barrier").contains(tags.getOrDefault("barrier",""));
    }
    private static String access(Map<String,String> tags) {
        for(String key:ACCESS)if(tags.containsKey(key))return tags.get(key);
        return null;
    }
}
