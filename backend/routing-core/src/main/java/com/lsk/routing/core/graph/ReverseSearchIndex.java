package com.lsk.routing.core.graph;

import java.util.Map;

/** Reverse adjacency and sparse history candidates; no shortest-distance preprocessing. */
final class ReverseSearchIndex {
    private static final int[] ZERO={0};
    private final int[] offsets,edges;
    private final Map<Integer,int[]> histories;
    private final double preprocessingMillis;
    ReverseSearchIndex(RoutingGraph graph) {
        long begin=System.nanoTime();
        offsets=new int[graph.nodeCount()+1];edges=new int[graph.edgeCount()];
        for(int e=0;e<graph.edgeCount();e++)offsets[graph.target(e)+1]++;
        for(int i=1;i<offsets.length;i++)offsets[i]+=offsets[i-1];
        int[] cursor=offsets.clone();
        for(int e=0;e<graph.edgeCount();e++)edges[cursor[graph.target(e)]++]=e;
        histories=graph.restrictionHistories();
        preprocessingMillis=(System.nanoTime()-begin)/1e6;
    }
    int start(int node) { return offsets[node]; }
    int end(int node) { return offsets[node+1]; }
    int edge(int position) { return edges[position]; }
    // State zero can be unreachable after some edges. Such extra states are harmless:
    // forward search cannot reach them, and reverse transitions still require exact inversion.
    int[] histories(int edge) { return histories.getOrDefault(edge,ZERO); }
    long arrayPayloadBytes() {
        return Integer.BYTES*((long)offsets.length+edges.length+histories.values().stream().mapToLong(a->a.length).sum());
    }
    double preprocessingMillis() { return preprocessingMillis; }
}
