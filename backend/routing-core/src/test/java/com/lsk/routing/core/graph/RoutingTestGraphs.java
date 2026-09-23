package com.lsk.routing.core.graph;

import java.util.*;

final class RoutingTestGraphs {
    record Arc(int from,int to,long way,double cost) {}
    static RoutingGraph grid(int side,boolean restricted) {
        int n=side*side;
        long[] ids=new long[n];double[] lat=new double[n],lon=new double[n];
        var arcs=new ArrayList<Arc>();var random=new Random(107);
        for(int i=0;i<n;i++) {
            ids[i]=i+1;lat[i]=37+(i/side)*.001;lon[i]=127+(i%side)*.001;
            if(i%side+1<side)add(arcs,i,i+1,random,restricted);
            if(i/side+1<side)add(arcs,i,i+side,random,restricted);
        }
        arcs.sort(Comparator.comparingInt(Arc::from).thenComparingInt(Arc::to));
        int[] offsets=new int[n+1],targets=new int[arcs.size()];long[] ways=new long[arcs.size()];double[] cost=new double[arcs.size()];
        for(int i=0;i<arcs.size();i++){var a=arcs.get(i);offsets[a.from+1]++;targets[i]=a.to;ways[i]=a.way;cost[i]=a.cost;}
        for(int i=1;i<offsets.length;i++)offsets[i]+=offsets[i-1];
        var bans=new ArrayList<Long>();
        if(restricted)for(int e=0;e<arcs.size();e++)for(int next=offsets[targets[e]];next<offsets[targets[e]+1];next++)
            if(random.nextInt(4)==0)bans.add(RoutingGraph.turnKey(e,next));
        return new RoutingGraph(ids,lat,lon,offsets,targets,ways,cost,bans.stream().mapToLong(Long::longValue).toArray());
    }
    private static void add(List<Arc> arcs,int a,int b,Random random,boolean restricted) {
        double distance=80+random.nextDouble()*40;long way=((long)a<<32)|b;
        arcs.add(new Arc(a,b,way,distance));
        if(!restricted||random.nextBoolean())arcs.add(new Arc(b,a,way,distance));
    }
    static double[] point(RoutingGraph graph,int edge,double fraction) {
        int source=0;
        while(source+1<graph.nodeCount() && graph.edgeEnd(source)<=edge)source++;
        int target=graph.target(edge);
        return new double[]{graph.longitude(source)+(graph.longitude(target)-graph.longitude(source))*fraction,
                graph.latitude(source)+(graph.latitude(target)-graph.latitude(source))*fraction};
    }
}
