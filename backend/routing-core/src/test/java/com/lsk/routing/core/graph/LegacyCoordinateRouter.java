package com.lsk.routing.core.graph;

import java.util.*;

/** Frozen pre-optimization implementation for differential tests and benchmarks only. */
public final class LegacyCoordinateRouter {
    private static final double EPS = 1e-10;
    private final RoutingGraph graph;
    private final int[] sources;
    private final Segment[] edgeSegments;
    private final Box index;
    private record Key(long way, int a, int b) {}
    private record Segment(Key key, double ax, double ay, double bx, double by) {
        double minX() { return Math.min(ax,bx); }
        double maxX() { return Math.max(ax,bx); }
        double minY() { return Math.min(ay,by); }
        double maxY() { return Math.max(ay,by); }
    }
    private record Box(double minX,double minY,double maxX,double maxY,Box left,Box right,List<Segment> items) {}
    private record Snap(Segment segment,double fraction,Point point,double distance) {}
    public record Point(double longitude,double latitude) {}
    public record Waypoint(Point point,double distanceMetres) {}
    public record Route(double distanceMetres,List<Point> geometry,Waypoint start,Waypoint end) {
        public Route { geometry=List.copyOf(geometry); }
    }
    public static final class OutsideGraphException extends IllegalArgumentException {
        public OutsideGraphException() { super("No road segment within 50 metres"); }
    }

    public LegacyCoordinateRouter(RoutingGraph graph) {
        this.graph=Objects.requireNonNull(graph);
        sources=new int[graph.edgeCount()];edgeSegments=new Segment[graph.edgeCount()];
        var segments=new LinkedHashMap<Key,Segment>();
        for(int node=0;node<graph.nodeCount();node++) {
            for(int edge=graph.edgeStart(node);edge<graph.edgeEnd(node);edge++) {
                sources[edge]=node;
                int target=graph.target(edge);
                var key=new Key(graph.osmWayId(edge),Math.min(node,target),Math.max(node,target));
                var segment=segments.computeIfAbsent(key,k->new Segment(k,graph.longitude(k.a),graph.latitude(k.a),
                        graph.longitude(k.b),graph.latitude(k.b)));
                if(Math.abs(segment.ax-segment.bx)>180)throw new IllegalArgumentException("Antimeridian segments unsupported");
                edgeSegments[edge]=segment;
            }
        }
        index=buildIndex(new ArrayList<>(segments.values()),0);
    }

    public Optional<Route> route(double startLon,double startLat,double endLon,double endLat) {
        validate(startLon,startLat);validate(endLon,endLat);
        Snap start=snap(startLon,startLat),end=snap(endLon,endLat);
        var overlay=split(start,end);
        return new DijkstraRouter(overlay.graph).route(overlay.start,overlay.end).map(path->{
            var geometry=new ArrayList<Point>();
            for(int n:path.nodes())geometry.add(new Point(overlay.graph.longitude(n),overlay.graph.latitude(n)));
            if(geometry.size()==1)geometry.add(geometry.getFirst());
            return new Route(path.distanceMetres(),geometry,new Waypoint(start.point,start.distance),new Waypoint(end.point,end.distance));
        });
    }

    private static void validate(double lon,double lat) {
        if(!Double.isFinite(lon)||!Double.isFinite(lat)||Math.abs(lon)>180||Math.abs(lat)>90)
            throw new IllegalArgumentException("Invalid coordinate");
    }
    private static Box buildIndex(List<Segment> segments,int depth) {
        if(segments.isEmpty())return null;
        double minX=Double.POSITIVE_INFINITY,minY=minX,maxX=Double.NEGATIVE_INFINITY,maxY=maxX;
        for(var s:segments){minX=Math.min(minX,s.minX());minY=Math.min(minY,s.minY());maxX=Math.max(maxX,s.maxX());maxY=Math.max(maxY,s.maxY());}
        if(segments.size()<=8)return new Box(minX,minY,maxX,maxY,null,null,List.copyOf(segments));
        segments.sort(Comparator.comparingDouble(s->depth%2==0?s.ax+s.bx:s.ay+s.by));
        int mid=segments.size()/2;
        return new Box(minX,minY,maxX,maxY,buildIndex(new ArrayList<>(segments.subList(0,mid)),depth+1),
                buildIndex(new ArrayList<>(segments.subList(mid,segments.size())),depth+1),List.of());
    }
    private static void query(Box box,double minX,double minY,double maxX,double maxY,List<Segment> result) {
        if(box==null||box.maxX<minX||box.minX>maxX||box.maxY<minY||box.minY>maxY)return;
        result.addAll(box.items);
        query(box.left,minX,minY,maxX,maxY,result);query(box.right,minX,minY,maxX,maxY,result);
    }
    private Snap snap(double lon,double lat) {
        var candidates=new ArrayList<Segment>();
        // Conservative geographic search box, then exact spherical distance to the projected point.
        double dy=51.0/110_000,dx=dy/Math.max(1e-9,Math.cos(Math.toRadians(Math.min(90,Math.abs(lat)+dy))));
        query(index,lon-dx,lat-dy,lon+dx,lat+dy,candidates);
        Snap best=null;
        for(var s:candidates) {
            double scale=Math.cos(Math.toRadians(lat));
            double x=(s.bx-s.ax)*scale,y=s.by-s.ay;
            double norm=x*x+y*y;
            double fraction=norm==0?0:Math.max(0,Math.min(1,((lon-s.ax)*scale*x+(lat-s.ay)*y)/norm));
            if(fraction<EPS)fraction=0;else if(fraction>1-EPS)fraction=1;
            Point point=new Point(s.ax+(s.bx-s.ax)*fraction,s.ay+(s.by-s.ay)*fraction);
            double distance=metres(lon,lat,point.longitude,point.latitude);
            if(distance<=50 && (best==null||distance<best.distance))best=new Snap(s,fraction,point,distance);
        }
        if(best==null)throw new OutsideGraphException();
        return best;
    }
    private static double metres(double lon1,double lat1,double lon2,double lat2) {
        double a=Math.toRadians(lat1),b=Math.toRadians(lat2);
        double h=Math.pow(Math.sin((b-a)/2),2)+Math.cos(a)*Math.cos(b)*Math.pow(Math.sin(Math.toRadians(lon2-lon1)/2),2);
        return 6371008.8*2*Math.asin(Math.sqrt(Math.min(1,h)));
    }

    private record Cut(double fraction,int node) {}
    private record Piece(int from,int to,int original,double distance,boolean first,boolean last) {}
    private record Overlay(RoutingGraph graph,int start,int end) {}
    private Overlay split(Snap start,Snap end) {
        var virtual=new ArrayList<Snap>();
        int startNode=assign(start,virtual),endNode=assign(end,virtual);
        int n=graph.nodeCount()+virtual.size();
        long[] ids=new long[n];double[] lat=new double[n],lon=new double[n];var usedIds=new HashSet<Long>();
        for(int i=0;i<graph.nodeCount();i++){ids[i]=graph.osmNodeId(i);usedIds.add(ids[i]);lat[i]=graph.latitude(i);lon[i]=graph.longitude(i);}
        long nextId=Long.MIN_VALUE;
        for(int i=0;i<virtual.size();i++){
            while(usedIds.contains(nextId))nextId++;
            ids[graph.nodeCount()+i]=nextId++;lat[graph.nodeCount()+i]=virtual.get(i).point.latitude;lon[graph.nodeCount()+i]=virtual.get(i).point.longitude;
        }
        var pieces=new ArrayList<Piece>();
        for(int e=0;e<graph.edgeCount();e++) {
            var cuts=new ArrayList<Cut>();cuts.add(new Cut(0,sources[e]));cuts.add(new Cut(1,graph.target(e)));
            for(int i=0;i<virtual.size();i++) {
                var snap=virtual.get(i);
                if(!snap.segment.key.equals(edgeSegments[e].key))continue;
                double fraction=sources[e]==snap.segment.key.a?snap.fraction:1-snap.fraction;
                cuts.add(new Cut(fraction,graph.nodeCount()+i));
            }
            cuts.sort(Comparator.comparingDouble(Cut::fraction));
            for(int i=1;i<cuts.size();i++)pieces.add(new Piece(cuts.get(i-1).node,cuts.get(i).node,e,
                    graph.distanceMetres(e)*(cuts.get(i).fraction-cuts.get(i-1).fraction),i==1,i==cuts.size()-1));
        }
        pieces.sort(Comparator.comparingInt(Piece::from));
        int[] offsets=new int[n+1],targets=new int[pieces.size()],first=new int[graph.edgeCount()],last=new int[graph.edgeCount()];
        long[] ways=new long[pieces.size()];double[] distances=new double[pieces.size()];
        for(int e=0;e<pieces.size();e++) {
            var p=pieces.get(e);offsets[p.from+1]++;targets[e]=p.to;ways[e]=graph.osmWayId(p.original);distances[e]=p.distance;
            if(p.first)first[p.original]=e;if(p.last)last[p.original]=e;
        }
        for(int i=1;i<offsets.length;i++)offsets[i]+=offsets[i-1];
        var forbidden=new TreeSet<Long>();
        for(int in=0;in<graph.edgeCount();in++) {
            int via=graph.target(in);
            for(int out=graph.edgeStart(via);out<graph.edgeEnd(via);out++)
                if(!graph.turnAllowed(in,out))forbidden.add(RoutingGraph.turnKey(last[in],first[out]));
        }
        // A query split is not a new junction: no mid-segment U-turn to bypass a real turn ban.
        for(int in=0;in<pieces.size();in++) {
            int via=targets[in];if(via<graph.nodeCount())continue;
            for(int out=offsets[via];out<offsets[via+1];out++)
                if(pieces.get(in).original!=pieces.get(out).original)forbidden.add(RoutingGraph.turnKey(in,out));
        }
        return new Overlay(new RoutingGraph(ids,lat,lon,offsets,targets,ways,distances,
                forbidden.stream().mapToLong(Long::longValue).toArray()),startNode,endNode);
    }
    private int assign(Snap snap,List<Snap> virtual) {
        if(snap.fraction==0)return snap.segment.key.a;
        if(snap.fraction==1)return snap.segment.key.b;
        for(int i=0;i<virtual.size();i++)if(virtual.get(i).segment.key.equals(snap.segment.key)
                && Math.abs(virtual.get(i).fraction-snap.fraction)<EPS)return graph.nodeCount()+i;
        virtual.add(snap);return graph.nodeCount()+virtual.size()-1;
    }
}
