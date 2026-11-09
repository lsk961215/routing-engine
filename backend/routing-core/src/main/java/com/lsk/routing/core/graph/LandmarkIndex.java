package com.lsk.routing.core.graph;

import java.util.*;

/**
 * Immutable directed ALT distances on the relaxed node graph (turn restrictions removed).
 * Removing restrictions can only lower costs, so the bound also applies to history states.
 * Index lifetime is tied to one immutable graph; it is never shared between graph revisions.
 */
final class LandmarkIndex {
    private record Label(int node,double distance) {}
    private final double[][] fromLandmark, toLandmark;
    private final int[] landmarks;
    private final double preprocessingMillis;

    LandmarkIndex(RoutingGraph graph,int count) {
        long begin=System.nanoTime();
        int n=graph.nodeCount();
        // Reverse CSR retains original directed edge IDs and costs.
        int[] reverseOffsets=new int[n+1],sources=new int[graph.edgeCount()];
        for(int v=0;v<n;v++)for(int e=graph.edgeStart(v);e<graph.edgeEnd(v);e++) {
            sources[e]=v;reverseOffsets[graph.target(e)+1]++;
        }
        for(int v=0;v<n;v++)reverseOffsets[v+1]+=reverseOffsets[v];
        int[] cursor=reverseOffsets.clone(),reverseEdges=new int[graph.edgeCount()];
        for(int e=0;e<graph.edgeCount();e++)reverseEdges[cursor[graph.target(e)]++]=e;
        boolean[] candidates=landmarkCandidates(graph,reverseOffsets,reverseEdges,sources);
        int available=0,first=-1,maxDegree=-1;
        for(int v=0;v<n;v++)if(candidates[v]) {
            available++;
            int degree=graph.edgeEnd(v)-graph.edgeStart(v)+reverseOffsets[v+1]-reverseOffsets[v];
            if(degree>maxDegree){first=v;maxDegree=degree;}
        }
        int size=Math.min(count,available);
        landmarks=new int[size];fromLandmark=new double[size][];toLandmark=new double[size][];
        double[] nearest=new double[n];Arrays.fill(nearest,Double.POSITIVE_INFINITY);
        int next=first;
        for(int i=0;i<size;i++) {
            landmarks[i]=next;candidates[next]=false;
            fromLandmark[i]=distances(graph,next,null,null,null);
            toLandmark[i]=distances(graph,next,reverseOffsets,reverseEdges,sources);
            int farthest=-1;
            for(int v=0;v<n;v++)if(candidates[v]) {
                nearest[v]=Math.min(nearest[v],Math.min(fromLandmark[i][v],toLandmark[i][v]));
                if(farthest<0 || nearest[v]>nearest[farthest])farthest=v;
            }
            next=farthest;
        }
        preprocessingMillis=(System.nanoTime()-begin)/1e6;
    }

    double lowerBound(int from,int to) {
        double bound=0;
        for(int i=0;i<landmarks.length;i++) {
            // Directed triangle inequalities: d(L,t)-d(L,v), d(v,L)-d(t,L).
            bound=Math.max(bound,difference(fromLandmark[i][to],fromLandmark[i][from]));
            bound=Math.max(bound,difference(toLandmark[i][from],toLandmark[i][to]));
        }
        return bound;
    }
    private static double difference(double a,double b) {
        // Unreachable nodes must never produce Infinity-Infinity or an infinite heuristic.
        // A small downward margin protects subtraction of accumulated floating point costs.
        return Double.isFinite(a) && Double.isFinite(b)?Math.max(0,a-b-Math.max(a,b)*1e-10):0;
    }
    int count() { return landmarks.length; }
    long distanceTableBytes() { return landmarks.length==0?0:2L*landmarks.length*fromLandmark[0].length*Double.BYTES; }
    double preprocessingMillis() { return preprocessingMillis; }

    private static double[] distances(RoutingGraph graph,int start,int[] offsets,int[] edges,int[] sources) {
        double[] best=new double[graph.nodeCount()];Arrays.fill(best,Double.POSITIVE_INFINITY);best[start]=0;
        var queue=new PriorityQueue<Label>(Comparator.comparingDouble(Label::distance).thenComparingInt(Label::node));
        queue.add(new Label(start,0));
        while(!queue.isEmpty()) {
            var label=queue.remove();int node=label.node;
            if(label.distance!=best[node])continue;
            int begin=offsets==null?graph.edgeStart(node):offsets[node];
            int end=offsets==null?graph.edgeEnd(node):offsets[node+1];
            for(int position=begin;position<end;position++) {
                int edge=edges==null?position:edges[position];
                int target=sources==null?graph.target(edge):sources[edge];
                double cost=label.distance+graph.distanceMetres(edge);
                // Overflowed/unreachable paths provide no useful finite lower bound.
                if(cost<best[target]){best[target]=cost;queue.add(new Label(target,cost));}
            }
        }
        return best;
    }

    /**
     * Prefer the largest strongly connected component: a one-way fringe can be far away
     * yet have distances to almost no other nodes, wasting most of the landmark budget.
     * Without a nontrivial strong component, fall back to the largest weak component.
     */
    private static boolean[] landmarkCandidates(RoutingGraph graph,int[] reverseOffsets,int[] reverseEdges,int[] sources) {
        int n=graph.nodeCount(),finished=0;
        boolean[] visited=new boolean[n];int[] order=new int[n],stack=new int[n],nextEdge=new int[n];
        // Iterative DFS avoids stack overflow on long road chains.
        for(int start=0;start<n;start++)if(!visited[start]) {
            int top=0;stack[top]=start;visited[start]=true;nextEdge[start]=graph.edgeStart(start);
            while(top>=0) {
                int node=stack[top];
                if(nextEdge[node]==graph.edgeEnd(node)){order[finished++]=node;top--;continue;}
                int target=graph.target(nextEdge[node]++);
                if(!visited[target]){visited[target]=true;nextEdge[target]=graph.edgeStart(target);stack[++top]=target;}
            }
        }
        int[] component=new int[n];Arrays.fill(component,-1);
        int largest=-1,largestSize=0;
        for(int i=n-1;i>=0;i--)if(component[order[i]]<0) {
            int start=order[i],top=0,size=0;stack[top]=start;component[start]=start;
            while(top>=0) {
                int node=stack[top--];size++;
                for(int p=reverseOffsets[node];p<reverseOffsets[node+1];p++) {
                    int target=sources[reverseEdges[p]];
                    if(component[target]<0){component[target]=start;stack[++top]=target;}
                }
            }
            if(size>largestSize){largest=start;largestSize=size;}
        }
        if(largestSize<=1)return largestWeakComponent(graph,sources);
        boolean[] selected=new boolean[n];
        for(int v=0;v<n;v++)selected[v]=component[v]==largest;
        return selected;
    }

    private static boolean[] largestWeakComponent(RoutingGraph graph,int[] sources) {
        int n=graph.nodeCount();int[] parent=new int[n],size=new int[n];
        for(int v=0;v<n;v++){parent[v]=v;size[v]=1;}
        for(int e=0;e<graph.edgeCount();e++) {
            int a=root(parent,sources[e]),b=root(parent,graph.target(e));
            if(a==b)continue;
            if(size[a]<size[b]){int swap=a;a=b;b=swap;}
            parent[b]=a;size[a]+=size[b];
        }
        int largest=-1;
        for(int v=0;v<n;v++)if(parent[v]==v && size[v]>1 && (largest<0 || size[v]>size[largest]))largest=v;
        boolean[] selected=new boolean[n];
        for(int v=0;v<n;v++)selected[v]=root(parent,v)==largest;
        return selected;
    }
    private static int root(int[] parent,int node) {
        while(parent[node]!=node){parent[node]=parent[parent[node]];node=parent[node];}
        return node;
    }
}
