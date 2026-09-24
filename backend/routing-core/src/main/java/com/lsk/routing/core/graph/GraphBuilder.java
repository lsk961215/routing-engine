package com.lsk.routing.core.graph;

import com.lsk.routing.core.osm.*;
import de.topobyte.osm4j.core.model.iface.*;
import de.topobyte.osm4j.pbf.seq.PbfIterator;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Offline builder. Rejects unresolved restrictions instead of dropping them. */
public final class GraphBuilder {
    private record Road(long id,long[] nodes,CarProfile.Decision decision) {}
    private record Edge(int from,int to,long way,double metres,byte direction) {}
    private record Turn(long from,long via,long to,String value) {}
    private record ViaTurn(long relation,long from,List<Long> via,long to,String value) {}

    public RoutingGraph build(Path pbf) throws IOException { return build(pbf,false); }
    public RoutingGraph build(Path pbf,boolean comparison) throws IOException {
        if(Files.size(pbf)>128*1024*1024)throw new IOException("Builder accepts PBF files up to 128 MiB");
        var analysis=comparison?new CarDataAnalysis().readForComparison(pbf):new CarDataAnalysis().readForGraph(pbf);
        var redundant = new HashSet<>(analysis.directionBlockedNoRestrictions());
        boolean allDirectionConflictsRedundant = analysis.issues().getOrDefault("DIRECTION_CONFLICT", 0L) == redundant.size();
        if(analysis.issues().keySet().stream().anyMatch(k->!k.equals("VIA_WAY_DEFERRED")
                && !(k.equals("DIRECTION_CONFLICT") && allDirectionConflictsRedundant)) || analysis.decisions().keySet().stream().anyMatch(k->k.startsWith("DEFERRED:")))
            throw new IOException("Unresolved road/restriction rules: "+analysis.issues()+" "+analysis.decisions());
        var roads=new ArrayList<Road>();var turns=new ArrayList<Turn>();var viaTurns=new ArrayList<ViaTurn>();var needed=new TreeSet<Long>();
        var ruleByWay=new LinkedHashMap<Long,ConditionalOneway>();
        try(var input=Files.newInputStream(pbf)) {
            var it=new PbfIterator(input,false);
            while(it.hasNext()) {
                var c=it.next();var tags=tags(c.getEntity());
                if(c.getType()==EntityType.Way) {
                    var w=(OsmWay)c.getEntity();
                    var evaluated=RoadDirections.forGraph(w.getId(),comparison?ComparisonProfile.normalize(tags):tags,w.getNumberOfNodes());var d=evaluated.decision();
                    if(evaluated.conditional()!=null)ruleByWay.put(w.getId(),evaluated.conditional());
                    if(!d.accepted())continue;
                    long[] ns=new long[w.getNumberOfNodes()];
                    for(int i=0;i<ns.length;i++){ns[i]=w.getNodeId(i);needed.add(ns[i]);}
                    roads.add(new Road(w.getId(),ns,d));
                    if(needed.size()>2_000_000 || roads.size()>500_000)throw new IOException("Graph area too large");
                } else if(c.getType()==EntityType.Relation && tags.getOrDefault("type","").startsWith("restriction")) {
                    var r=(OsmRelation)c.getEntity();
                    if(redundant.contains(r.getId()))continue; // Its geometry has no directed realization; retain road directions.
                    long from=0,to=0,via=0;var viaWays=new ArrayList<Long>();
                    for(int i=0;i<r.getNumberOfMembers();i++) {
                        var m=r.getMember(i);
                        switch(m.getRole()){case "from"->from=m.getId();case "to"->to=m.getId();case "via"->{if(m.getType()==EntityType.Way)viaWays.add(m.getId());else via=m.getId();}}
                    }
                    String value=null;
                    for(String k:List.of("restriction:motorcar","restriction:motor_vehicle","restriction:vehicle","restriction"))
                        if(tags.containsKey(k)){value=tags.get(k);break;}
                    if(from==to && !"no_u_turn".equals(value))throw new IOException("Ambiguous same-way restriction: "+r.getId());
                    if(viaWays.isEmpty())turns.add(new Turn(from,via,to,value));
                    else viaTurns.add(new ViaTurn(r.getId(),from,List.copyOf(viaWays),to,value));
                }
            }
        }
        if(needed.isEmpty())throw new IOException("No accepted roads");
        long[] ids=needed.stream().mapToLong(Long::longValue).toArray();
        var index=new HashMap<Long,Integer>();for(int i=0;i<ids.length;i++)index.put(ids[i],i);
        double[] lat=new double[ids.length],lon=new double[ids.length];var found=new HashSet<Long>();var blockedNodes=new HashSet<Integer>();
        try(var input=Files.newInputStream(pbf)) {
            var it=new PbfIterator(input,false);
            while(it.hasNext()) {
                var c=it.next();if(c.getType()!=EntityType.Node)continue;
                var n=(OsmNode)c.getEntity();Integer i=index.get(n.getId());if(i==null)continue;
                var tags=tags(n);
                if(comparison && ComparisonProfile.blockedNode(tags))blockedNodes.add(i);
                if(!comparison && (tags.containsKey("barrier") || tags.keySet().stream().anyMatch(k->k.equals("access") || k.startsWith("access:")
                        || k.startsWith("motor") || k.startsWith("vehicle"))))throw new IOException("Unsupported node access/barrier: "+n.getId());
                if(!found.add(n.getId()))throw new IOException("Multiple node versions");
                lat[i]=n.getLatitude();lon[i]=n.getLongitude();
            }
        }
        if(found.size()!=ids.length)throw new IOException("Missing road nodes");
        var edges=new ArrayList<Edge>();
        for(var road:roads) for(int i=1;i<road.nodes.length;i++) {
            int a=index.get(road.nodes[i-1]),b=index.get(road.nodes[i]);if(a==b)continue;
            double d=metres(lat[a],lon[a],lat[b],lon[b]);
            if(road.decision.direction()!=CarProfile.Direction.REVERSE)edges.add(new Edge(a,b,road.id,d,(byte)1));
            if(road.decision.direction()!=CarProfile.Direction.FORWARD)edges.add(new Edge(b,a,road.id,d,(byte)2));
            if(edges.size()>4_000_000)throw new IOException("Graph edge limit exceeded");
        }
        edges.sort(Comparator.comparingInt(Edge::from).thenComparingLong(Edge::way).thenComparingInt(Edge::to));
        int[] offsets=new int[ids.length+1],targets=new int[edges.size()];long[] ways=new long[edges.size()];double[] distance=new double[edges.size()];
        for(int e=0;e<edges.size();e++){var x=edges.get(e);offsets[x.from+1]++;targets[e]=x.to;ways[e]=x.way;distance[e]=x.metres;}
        for(int i=1;i<offsets.length;i++)offsets[i]+=offsets[i-1];
        int[] incomingHead=new int[ids.length],incomingNext=new int[edges.size()];
        Arrays.fill(incomingHead,-1);
        for(int e=0;e<edges.size();e++){int target=edges.get(e).to;incomingNext[e]=incomingHead[target];incomingHead[target]=e;}
        var forbidden=new TreeSet<Long>();
        for(var turn:turns) {
            Integer via=index.get(turn.via);if(via==null)throw new IOException("Restriction via missing from graph");
            for(int a=incomingHead[via];a!=-1;a=incomingNext[a]) {
                var incoming=edges.get(a);if(incoming.way!=turn.from || incoming.to!=via)continue;
                for(int b=offsets[via];b<offsets[via+1];b++) {
                    var outgoing=edges.get(b);
                    boolean selected=outgoing.way==turn.to;
                    if(turn.from==turn.to)selected &= outgoing.to==incoming.from;
                    boolean blocked=turn.value.startsWith("only_")?!selected:selected;
                    if(blocked)forbidden.add(RoutingGraph.turnKey(a,b));
                }
            }
        }
        // Block passage at a definite barrier, retaining both approach roads and their geometry.
        for(int via:blockedNodes)for(int a=incomingHead[via];a!=-1;a=incomingNext[a])
            for(int b=offsets[via];b<offsets[via+1];b++)forbidden.add(RoutingGraph.turnKey(a,b));
        long[] blocked=forbidden.stream().mapToLong(Long::longValue).toArray();
        var base=new RoutingGraph(ids,lat,lon,offsets,targets,ways,distance,blocked);
        var sequences=new ArrayList<RoutingGraph.SequenceRestriction>();
        for(var turn:viaTurns) {
            try {
                sequences.add(ViaWayCompiler.compile(base,turn.from,turn.via,turn.to,turn.value.startsWith("only_")));
            } catch(IOException e) {
                throw new IOException("Restriction " + turn.relation + " (from=" + turn.from
                        + ", via=" + turn.via + ", to=" + turn.to + "): " + e.getMessage(), e);
            }
        }
        ConditionalDirections conditional=null;
        if(!ruleByWay.isEmpty()) {
            var ruleIds=new HashMap<Long,Integer>();var rules=new ArrayList<ConditionalOneway>();
            ruleByWay.forEach((way,rule)->{ruleIds.put(way,rules.size());rules.add(rule);});
            int[] bindings=new int[edges.size()];byte[] directions=new byte[edges.size()];Arrays.fill(bindings,-1);
            for(int e=0;e<edges.size();e++) {
                var edge=edges.get(e);Integer id=ruleIds.get(edge.way);
                if(id!=null){bindings[e]=id;directions[e]=edge.direction;}
            }
            conditional=new ConditionalDirections(java.time.ZoneId.of("Asia/Seoul"),rules,bindings,directions);
        }
        return sequences.isEmpty() && conditional==null?base:new RoutingGraph(ids,lat,lon,offsets,targets,ways,distance,blocked,sequences,conditional);
    }
    private static Map<String,String> tags(OsmEntity e) {
        var result=new HashMap<String,String>();for(int i=0;i<e.getNumberOfTags();i++){var t=e.getTag(i);result.put(t.getKey(),t.getValue());}return result;
    }
    private static double metres(double lat1,double lon1,double lat2,double lon2) {
        double a=Math.toRadians(lat1),b=Math.toRadians(lat2),dl=Math.toRadians(lon2-lon1);
        double h=Math.pow(Math.sin((b-a)/2),2)+Math.cos(a)*Math.cos(b)*Math.pow(Math.sin(dl/2),2);
        return 6371008.8*2*Math.asin(Math.sqrt(Math.min(1,h)));
    }
    public static void main(String[] args) throws Exception {
        if(args.length<2 || args.length>3 || args.length==3 && !args[2].equals("--comparison"))throw new IllegalArgumentException("Expected input PBF, output graph, optional --comparison");
        boolean comparison=args.length==3;
        var audit=comparison?new CarDataAnalysis().readForComparison(Path.of(args[0])):new CarDataAnalysis().readForGraph(Path.of(args[0]));
        System.out.println("Direction-blocked no restrictions: "+audit.directionBlockedNoRestrictions());
        var graph=new GraphBuilder().build(Path.of(args[0]),comparison);graph.write(Path.of(args[1]));
        long start=System.nanoTime();var loaded=RoutingGraph.read(Path.of(args[1]));
        System.out.printf("Nodes=%d edges=%d forbiddenTurns=%d viaRules=%d arrayBytes=%d loadMs=%.3f%n",loaded.nodeCount(),loaded.edgeCount(),loaded.forbiddenTurnCount(),loaded.sequenceRestrictions().size(),loaded.arrayPayloadBytes(),(System.nanoTime()-start)/1e6);
    }
}
