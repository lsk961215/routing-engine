package com.lsk.routing.core.graph;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.CRC32;

/** Immutable CSR graph. Reads v1/v2/v3; v3 adds conditional direction bindings. */
public final class RoutingGraph {
    private static final int MAGIC = 0x52475246, VERSION = 3, MAX_BYTES = 64 * 1024 * 1024;
    private final long[] ids, ways, forbidden;
    private final double[] lat, lon, distance;
    private final int[] offsets, targets;
    public record SequenceRestriction(boolean only,List<List<Integer>> paths) {
        public SequenceRestriction {
            paths=paths.stream().map(List::copyOf).toList();
            if(paths.isEmpty() || paths.size()>256)throw new IllegalArgumentException("Invalid restriction alternatives");
        }
    }
    private final List<SequenceRestriction> sequences;
    private final TurnSequences automaton;
    private final ConditionalDirections conditionalDirections;
    public Optional<ConditionalDirections> conditionalDirections() { return Optional.ofNullable(conditionalDirections); }
    void requireStaticDirections() {
        if(conditionalDirections!=null)throw new IllegalArgumentException("Conditional graph requires snapshotAt");
    }
    public List<SequenceRestriction> sequenceRestrictions() { return sequences; }
    public int restrictionState(int state,int edge) { return automaton.advance(state,edge); }


    public RoutingGraph(long[] ids, double[] lat, double[] lon, int[] offsets,
                        int[] targets, long[] ways, double[] distance, long[] forbidden) {
        this(ids,lat,lon,offsets,targets,ways,distance,forbidden,List.of());
    }
    public RoutingGraph(long[] ids,double[] lat,double[] lon,int[] offsets,int[] targets,long[] ways,
                        double[] distance,long[] forbidden,List<SequenceRestriction> sequences) {
        this(ids,lat,lon,offsets,targets,ways,distance,forbidden,sequences,null);
    }
    public RoutingGraph(long[] ids,double[] lat,double[] lon,int[] offsets,int[] targets,long[] ways,
                        double[] distance,long[] forbidden,List<SequenceRestriction> sequences,ConditionalDirections conditionalDirections) {
        this.conditionalDirections=conditionalDirections;
        this.sequences=List.copyOf(sequences);
        this.ids=ids.clone(); this.lat=lat.clone(); this.lon=lon.clone(); this.offsets=offsets.clone();
        this.targets=targets.clone(); this.ways=ways.clone(); this.distance=distance.clone();
        this.forbidden=forbidden.clone(); Arrays.sort(this.forbidden);
        validate();
        automaton=new TurnSequences(this.sequences);
    }
    public int nodeCount() { return ids.length; }
    public int edgeCount() { return targets.length; }
    public int forbiddenTurnCount() { return forbidden.length; }
    public long osmNodeId(int node) { return ids[node]; }
    public double latitude(int node) { return lat[node]; }
    public double longitude(int node) { return lon[node]; }
    public int edgeStart(int node) { return offsets[node]; }
    public int edgeEnd(int node) { return offsets[node+1]; }
    public int target(int edge) { return targets[edge]; }
    public long osmWayId(int edge) { return ways[edge]; }
    public double distanceMetres(int edge) { return distance[edge]; }
    public static long turnKey(int incoming, int outgoing) { return ((long)incoming << 32) | (outgoing & 0xffffffffL); }
    public boolean turnAllowed(int incoming, int outgoing) {
        int via = targets[incoming];
        return outgoing >= offsets[via] && outgoing < offsets[via+1]
                && Arrays.binarySearch(forbidden, turnKey(incoming,outgoing)) < 0;
    }
    public long arrayPayloadBytes() { return 24L*nodeCount()+4L*(nodeCount()+1)+20L*edgeCount()+8L*forbidden.length; }
    private void validate() {
        int n=ids.length,m=targets.length;
        if(lat.length!=n || lon.length!=n || offsets.length!=n+1 || ways.length!=m || distance.length!=m
                || offsets[0]!=0 || offsets[n]!=m) throw new IllegalArgumentException("Invalid array dimensions");
        var unique=new HashSet<Long>();
        for(int i=0;i<n;i++) {
            if(!unique.add(ids[i]) || !Double.isFinite(lat[i]) || !Double.isFinite(lon[i])
                    || Math.abs(lat[i])>90 || Math.abs(lon[i])>180 || offsets[i]<0 || offsets[i]>offsets[i+1])
                throw new IllegalArgumentException("Invalid node or offsets");
        }
        for(int e=0;e<m;e++) if(targets[e]<0 || targets[e]>=n || !Double.isFinite(distance[e]) || distance[e]<0)
            throw new IllegalArgumentException("Invalid edge");
        for(long key:forbidden) {
            int a=(int)(key>>>32),c=(int)key;
            if(a<0 || a>=m || c<0 || c>=m || c<offsets[targets[a]] || c>=offsets[targets[a]+1])
                throw new IllegalArgumentException("Invalid turn");
        }
        if(conditionalDirections!=null && conditionalDirections.edgeCount()!=m)
            throw new IllegalArgumentException("Conditional binding count differs from edges");
        if(sequences.size()>10_000)throw new IllegalArgumentException("Too many sequence rules");
        long total=0;
        for(var rule:sequences)for(var path:rule.paths()) {
            total+=path.size();
            if(path.size()<3 || path.size()>128 || total>100_000)throw new IllegalArgumentException("Sequence limit exceeded");
            for(int i=0;i<path.size();i++) {
                int edge=path.get(i);
                if(edge<0 || edge>=m)throw new IllegalArgumentException("Invalid sequence edge");
                if(i>0) {
                    int via=targets[path.get(i-1)];
                    if(edge<offsets[via] || edge>=offsets[via+1])throw new IllegalArgumentException("Disconnected sequence");
                }
            }
        }
    }
    public void write(Path path) throws IOException {
        int version=conditionalDirections!=null?3:sequences.isEmpty()?1:2;
        long sequenceBytes=version>=2?4:0;
        for(var rule:sequences){sequenceBytes+=8;for(var pathEdges:rule.paths())sequenceBytes+=4L+4L*pathEdges.size();}
        if(arrayPayloadBytes()+28+sequenceBytes+(conditionalDirections==null?0:conditionalDirections.serializedBytes())>MAX_BYTES) throw new IOException("Graph exceeds size limit");
        var buffer=new ByteArrayOutputStream();
        try(var out=new DataOutputStream(buffer)) {
            out.writeInt(MAGIC);out.writeInt(version);out.writeInt(nodeCount());out.writeInt(edgeCount());out.writeInt(forbidden.length);
            for(int i=0;i<nodeCount();i++){out.writeLong(ids[i]);out.writeDouble(lat[i]);out.writeDouble(lon[i]);}
            for(int x:offsets)out.writeInt(x);
            for(int e=0;e<edgeCount();e++){out.writeInt(targets[e]);out.writeLong(ways[e]);out.writeDouble(distance[e]);}
            for(long x:forbidden)out.writeLong(x);
            if(version>=2) {
                out.writeInt(sequences.size());
                for(var rule:sequences) {
                    out.writeInt(rule.only()?1:0);out.writeInt(rule.paths().size());
                    for(var edges:rule.paths()){out.writeInt(edges.size());for(int edge:edges)out.writeInt(edge);}
                }
            }
            if(conditionalDirections!=null)conditionalDirections.write(out);
            var crc=new CRC32();crc.update(buffer.toByteArray());out.writeLong(crc.getValue());
        }
        Files.write(path,buffer.toByteArray(),StandardOpenOption.CREATE_NEW);
    }
    public static RoutingGraph read(Path path) throws IOException {
        long size=Files.size(path);
        if(size<32 || size>MAX_BYTES)throw new IOException("Invalid graph size");
        byte[] bytes=Files.readAllBytes(path);
        var crc=new CRC32();crc.update(bytes,0,bytes.length-8);
        try(var in=new DataInputStream(new ByteArrayInputStream(bytes))) {
            if(in.readInt()!=MAGIC)throw new IOException("Unsupported graph format");
            int version=in.readInt();
            if(version<1 || version>VERSION)throw new IOException("Unsupported graph version");
            int n=in.readInt(),m=in.readInt(),t=in.readInt();
            long baseSize=32L+28L*n+20L*m+8L*t;
            if(n<0 || m<0 || t<0 || baseSize>bytes.length || (version==1 && baseSize!=bytes.length))throw new IOException("Invalid graph counts");
            long[] ids=new long[n],ways=new long[m],turns=new long[t];
            double[] lat=new double[n],lon=new double[n],dist=new double[m];int[] offsets=new int[n+1],targets=new int[m];
            for(int i=0;i<n;i++){ids[i]=in.readLong();lat[i]=in.readDouble();lon[i]=in.readDouble();}
            for(int i=0;i<=n;i++)offsets[i]=in.readInt();
            for(int e=0;e<m;e++){targets[e]=in.readInt();ways[e]=in.readLong();dist[e]=in.readDouble();}
            for(int i=0;i<t;i++)turns[i]=in.readLong();
            var sequences=new ArrayList<SequenceRestriction>();
            if(version>=2) {
                int count=in.readInt();
                if(count<(version==2?1:0) || count>10_000)throw new IOException("Invalid sequence count");
                long total=0;
                for(int i=0;i<count;i++) {
                    int only=in.readInt(),alternatives=in.readInt();
                    if((only!=0 && only!=1) || alternatives<1 || alternatives>256)throw new IOException("Invalid sequence rule");
                    var paths=new ArrayList<List<Integer>>();
                    for(int j=0;j<alternatives;j++) {
                        int length=in.readInt();total+=length;
                        if(length<3 || length>128 || total>100_000 || 4L*length>in.available()-8)throw new IOException("Invalid sequence length");
                        var edges=new ArrayList<Integer>();for(int k=0;k<length;k++)edges.add(in.readInt());paths.add(edges);
                    }
                    sequences.add(new SequenceRestriction(only==1,paths));
                }
            }
            var conditional=version==3?ConditionalDirections.read(in,m):null;
            if(in.available()!=8 || in.readLong()!=crc.getValue())throw new IOException("Graph checksum or trailing bytes mismatch");
            try{return new RoutingGraph(ids,lat,lon,offsets,targets,ways,dist,turns,sequences,conditional);}
            catch(IllegalArgumentException e){throw new IOException("Invalid graph structure",e);}
        }
    }
}
