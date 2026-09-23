package com.lsk.routing.core.graph;

import java.lang.management.ManagementFactory;
import java.util.*;

/** Local diagnostic benchmark, not a statistically controlled capacity test. */
public final class RoutingBenchmark {
    private static volatile double sink;
    private static int datasetIndex;
    private static final com.sun.management.ThreadMXBean ALLOCATION =
            (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    public static void main(String[] args) throws Exception {
        if(!ALLOCATION.isThreadAllocatedMemorySupported())throw new IllegalStateException("Thread allocation accounting unavailable");
        ALLOCATION.setThreadAllocatedMemoryEnabled(true);
        System.out.println("java="+System.getProperty("java.version")+" maxHeap="+Runtime.getRuntime().maxMemory());
        if(args.length==1)run("real-small-area",RoutingGraph.read(java.nio.file.Path.of(args[0])),200);
        run("grid-10",RoutingTestGraphs.grid(10,true),150);
        run("grid-50",RoutingTestGraphs.grid(50,true),80);
        run("grid-100",RoutingTestGraphs.grid(100,true),40);
    }
    private static void run(String name,RoutingGraph graph,int iterations) {
        var queries=new double[20][];
        for(int i=0;i<queries.length;i++) {
            int a=(i*37)%graph.edgeCount(),b=(i*113+graph.edgeCount()/2)%graph.edgeCount();
            var start=RoutingTestGraphs.point(graph,a,.25);var end=RoutingTestGraphs.point(graph,b,.75);
            queries[i]=new double[]{start[0],start[1],end[0],end[1]};
        }
        long start=System.nanoTime();
        var legacy=new LegacyCoordinateRouter(graph);
        double oldInit=(System.nanoTime()-start)/1e6;
        start=System.nanoTime();var optimized=new CoordinateRouter(graph);double newInit=(System.nanoTime()-start)/1e6;
        java.util.function.ToDoubleFunction<double[]> oldQuery=q->legacy.route(q[0],q[1],q[2],q[3]).map(LegacyCoordinateRouter.Route::distanceMetres).orElse(-1.0);
        java.util.function.ToDoubleFunction<double[]> newQuery=q->optimized.route(q[0],q[1],q[2],q[3]).map(CoordinateRouter.Route::distanceMetres).orElse(-1.0);
        for(var q:queries)if(Math.abs(oldQuery.applyAsDouble(q)-newQuery.applyAsDouble(q))>1e-7)
            throw new AssertionError("Benchmark result mismatch");
        for(int i=0;i<60;i++){sink=oldQuery.applyAsDouble(queries[i%20]);sink=newQuery.applyAsDouble(queries[i%20]);}
        System.out.printf(Locale.ROOT,"%s nodes=%d edges=%d turns=%d payloadBytes=%d legacyInitMs=%.3f optimizedInitMs=%.3f%n",
                name,graph.nodeCount(),graph.edgeCount(),graph.forbiddenTurnCount(),graph.arrayPayloadBytes(),oldInit,newInit);
        // Alternate the measured implementation between graph sizes to reduce fixed-order bias.
        if((datasetIndex++ & 1)==0){measure("optimized",newQuery,queries,iterations);measure("legacy",oldQuery,queries,iterations);}
        else{measure("legacy",oldQuery,queries,iterations);measure("optimized",newQuery,queries,iterations);}
    }
    private static void measure(String name,java.util.function.ToDoubleFunction<double[]> query,double[][] queries,int count) {
        long thread=Thread.currentThread().threadId();
        long allocated=0;double[] ms=new double[count];double checksum=0;
        for(int i=0;i<count;i++) {
            long before=ALLOCATION.getThreadAllocatedBytes(thread),start=System.nanoTime();
            checksum+=query.applyAsDouble(queries[i%queries.length]);
            ms[i]=(System.nanoTime()-start)/1e6;
            allocated+=ALLOCATION.getThreadAllocatedBytes(thread)-before;
        }
        sink=checksum;Arrays.sort(ms);
        System.out.printf(Locale.ROOT,"  %s queries=%d p50Ms=%.4f p95Ms=%.4f allocatedBytesPerQuery=%d checksum=%.6f%n",
                name,count,ms[count/2],ms[(int)Math.ceil(count*.95)-1],allocated/count,checksum);
    }
}
