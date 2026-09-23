package com.lsk.routing.core.graph;

import java.util.*;

/** Small-area routing via projected points. Graph and spatial index are shared; only Dijkstra labels are query-local. */
public final class CoordinateRouter {
    private static final double EPS = 1e-10;
    private final RoutingGraph graph;
    private final int[] sources;
    private final Segment[] edgeSegments;
    private final Box index;
    private final Map<Key, int[]> segmentEdges;
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

    public CoordinateRouter(RoutingGraph graph) {
        this.graph=Objects.requireNonNull(graph);
        sources=new int[graph.edgeCount()];edgeSegments=new Segment[graph.edgeCount()];
        var segments=new LinkedHashMap<Key,Segment>();
        var grouped=new HashMap<Key, List<Integer>>();
        for(int node=0;node<graph.nodeCount();node++) {
            for(int edge=graph.edgeStart(node);edge<graph.edgeEnd(node);edge++) {
                sources[edge]=node;
                int target=graph.target(edge);
                var key=new Key(graph.osmWayId(edge),Math.min(node,target),Math.max(node,target));
                var segment=segments.computeIfAbsent(key,k->new Segment(k,graph.longitude(k.a),graph.latitude(k.a),
                        graph.longitude(k.b),graph.latitude(k.b)));
                if(Math.abs(segment.ax-segment.bx)>180)throw new IllegalArgumentException("Antimeridian segments unsupported");
                edgeSegments[edge]=segment;
                grouped.computeIfAbsent(key,ignored->new ArrayList<>()).add(edge);
            }
        }
        index=buildIndex(new ArrayList<>(segments.values()),0);
        var frozen=new HashMap<Key,int[]>();
        grouped.forEach((key,edges)->frozen.put(key,edges.stream().mapToInt(Integer::intValue).toArray()));
        segmentEdges=Map.copyOf(frozen);
    }

    public Optional<Route> route(double startLon,double startLat,double endLon,double endLat) {
        validate(startLon,startLat);validate(endLon,endLat);
        Snap start=snap(startLon,startLat),end=snap(endLon,endLat);
        return search(start,end);
    }

    private record Label(int edge,double distance) {}
    private Optional<Route> search(Snap start,Snap end) {
        int startNode=endpoint(start),endNode=endpoint(end);
        if ((startNode>=0 && startNode==endNode) || (startNode<0 && endNode<0
                && start.segment.key.equals(end.segment.key) && Math.abs(start.fraction-end.fraction)<EPS)) {
            return Optional.of(result(start,end,0,List.of()));
        }
        if(!graph.sequenceRestrictions().isEmpty())return searchWithHistory(start,end,startNode,endNode);
        double[] best=new double[graph.edgeCount()];
        int[] previous=new int[graph.edgeCount()];
        Arrays.fill(best,Double.POSITIVE_INFINITY);
        Arrays.fill(previous,-1);
        var queue=new PriorityQueue<Label>(Comparator.comparingDouble(Label::distance).thenComparingInt(Label::edge));
        double winner=Double.POSITIVE_INFINITY;
        int winnerLast=-1;
        // At an exact junction any outgoing edge is a legal starting edge. In a segment's
        // interior only its existing directed edges can be used, with the remaining cost.
        int[] seeds=startNode>=0
                ? java.util.stream.IntStream.range(graph.edgeStart(startNode),graph.edgeEnd(startNode)).toArray()
                : segmentEdges.get(start.segment.key);
        for(int edge:seeds) {
            double from=startNode>=0?0:fractionAlong(start,edge);
            double remaining=graph.distanceMetres(edge)*(1-from);
            best[edge]=remaining;
            queue.add(new Label(edge,remaining));
            // Same directed segment: reach the destination before its endpoint.
            if(edgeSegments[edge].key.equals(end.segment.key)) {
                double to=fractionAlong(end,edge);
                if(to>=from)winner=Math.min(winner,graph.distanceMetres(edge)*(to-from));
            }
        }
        while(!queue.isEmpty()) {
            var label=queue.remove();
            int incoming=label.edge;
            if(label.distance!=best[incoming])continue;
            if(label.distance>=winner)break;
            int node=graph.target(incoming);
            if(node==endNode) {
                winner=label.distance;winnerLast=incoming;
                break;
            }
            for(int outgoing=graph.edgeStart(node);outgoing<graph.edgeEnd(node);outgoing++) {
                if(!graph.turnAllowed(incoming,outgoing))continue;
                if(endNode<0 && edgeSegments[outgoing].key.equals(end.segment.key)) {
                    double candidate=add(label.distance,graph.distanceMetres(outgoing)*fractionAlong(end,outgoing));
                    if(candidate<winner){winner=candidate;winnerLast=incoming;}
                }
                double distance=add(label.distance,graph.distanceMetres(outgoing));
                if(distance>=best[outgoing] || distance>=winner)continue;
                best[outgoing]=distance;previous[outgoing]=incoming;
                queue.add(new Label(outgoing,distance));
            }
        }
        if(!Double.isFinite(winner))return Optional.empty();
        var path=new ArrayList<Integer>();
        for(int edge=winnerLast;edge!=-1;edge=previous[edge])path.add(edge);
        Collections.reverse(path);
        return Optional.of(result(start,end,winner,path));
    }
    private Optional<Route> searchWithHistory(Snap start,Snap end,int startNode,int endNode) {
        int[] initial=startNode>=0
                ?java.util.stream.IntStream.range(graph.edgeStart(startNode),graph.edgeEnd(startNode)).toArray()
                :segmentEdges.get(start.segment.key);
        var seeds=new ArrayList<HistorySearch.Seed>();
        double direct=Double.POSITIVE_INFINITY;
        for(int edge:initial) {
            double from=startNode>=0?0:fractionAlong(start,edge);
            seeds.add(new HistorySearch.Seed(edge,graph.distanceMetres(edge)*(1-from)));
            if(edgeSegments[edge].key.equals(end.segment.key)) {
                double to=fractionAlong(end,edge);
                if(to>=from)direct=Math.min(direct,graph.distanceMetres(edge)*(to-from));
            }
        }
        var partial=new HashMap<Integer,Double>();
        if(endNode<0)for(int edge:segmentEdges.get(end.segment.key))partial.put(edge,graph.distanceMetres(edge)*fractionAlong(end,edge));
        return HistorySearch.route(graph,seeds,endNode,partial,direct).map(path->result(start,end,path.distance(),path.edges()));
    }
    private Route result(Snap start,Snap end,double distance,List<Integer> fullEdges) {
        var geometry=new ArrayList<Point>();geometry.add(start.point);
        for(int edge:fullEdges) {
            int node=graph.target(edge);
            geometry.add(new Point(graph.longitude(node),graph.latitude(node)));
        }
        Point last=geometry.getLast();
        if(geometry.size()==1 || Math.abs(last.longitude-end.point.longitude)>EPS
                || Math.abs(last.latitude-end.point.latitude)>EPS)geometry.add(end.point);
        return new Route(distance,geometry,new Waypoint(start.point,start.distance),new Waypoint(end.point,end.distance));
    }
    private static int endpoint(Snap snap) {
        return snap.fraction==0?snap.segment.key.a:snap.fraction==1?snap.segment.key.b:-1;
    }
    private double fractionAlong(Snap snap,int edge) {
        return sources[edge]==snap.segment.key.a?snap.fraction:1-snap.fraction;
    }
    private static double add(double a,double b) {
        double result=a+b;
        if(!Double.isFinite(result))throw new ArithmeticException("Route distance overflow");
        return result;
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

}
