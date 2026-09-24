package com.lsk.routing.core.graph;

import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.util.*;

/** Coordinates checked against node-history oracle, then warm serial diagnostic measurements. */
public final class RegionCoordinateBenchmark {
    private static volatile double sink;
    public static void main(String[] args) throws Exception {
        var allocation=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
        if(!allocation.isThreadAllocatedMemorySupported())throw new IllegalStateException("Allocation accounting unavailable");
        allocation.setThreadAllocatedMemoryEnabled(true);
        long thread=Thread.currentThread().threadId(), before=allocation.getThreadAllocatedBytes(thread), start=System.nanoTime();
        var graph=RoutingGraph.read(Path.of(args[0]));
        var router=new CoordinateRouter(graph);
        double initMs=(System.nanoTime()-start)/1e6;
        long initBytes=allocation.getThreadAllocatedBytes(thread)-before;
        var queries=new ArrayList<double[]>();
        var expected=new ArrayList<Double>();
        var random=new Random(20260924);
        // Exact graph-node requests, with independent history-suffix shortest paths.
        for(int i=0;i<40;i++) {
            int a=random.nextInt(graph.nodeCount()),b=random.nextInt(graph.nodeCount());
            queries.add(new double[]{graph.longitude(a),graph.latitude(a),graph.longitude(b),graph.latitude(b)});
            expected.add(ViaWayAudit.referenceDistance(graph,a,b));
        }
        // Same directed segment's middle half has an explicit expected distance.
        for(int i=0;i<20;i++) {
            int e=i*37%graph.edgeCount();
            var a=RoutingTestGraphs.point(graph,e,.25);var b=RoutingTestGraphs.point(graph,e,.75);
            queries.add(new double[]{a[0],a[1],b[0],b[1]});expected.add(graph.distanceMetres(e)*.5);
        }
        var rows=new ArrayList<String>();rows.add("startLon\tstartLat\tendLon\tendLat\tstatus\tdistance");
        int found=0;
        for(int i=0;i<queries.size();i++) {
            var q=queries.get(i);var route=router.route(q[0],q[1],q[2],q[3]);double distance=expected.get(i);
            if(route.isPresent()!=Double.isFinite(distance) || route.isPresent() && Math.abs(route.get().distanceMetres()-distance)>1e-5)
                throw new IllegalStateException("Coordinate reference mismatch at query "+i);
            if(route.isPresent())found++;
            rows.add(q[0]+"\t"+q[1]+"\t"+q[2]+"\t"+q[3]+"\t"+(route.isPresent()?200:404)+"\t"+distance);
        }
        Files.write(Path.of(args[1]),rows);
        for(int i=0;i<300;i++)query(router,queries.get(i%queries.size()));
        double[] ms=new double[600];long bytes=0;
        for(int i=0;i<ms.length;i++) {
            var q=queries.get(i%queries.size());before=allocation.getThreadAllocatedBytes(thread);start=System.nanoTime();
            query(router,q);ms[i]=(System.nanoTime()-start)/1e6;bytes+=allocation.getThreadAllocatedBytes(thread)-before;
        }
        Arrays.sort(ms);
        System.out.printf(Locale.ROOT,"java=%s maxHeap=%d nodes=%d edges=%d nodeTurns=%d viaRules=%d cases=%d found=%d noRoute=%d initMs=%.3f initAllocatedBytes=%d measured=600 p50Ms=%.4f p95Ms=%.4f allocatedBytesPerQuery=%d%n",
                System.getProperty("java.version"),Runtime.getRuntime().maxMemory(),graph.nodeCount(),graph.edgeCount(),graph.forbiddenTurnCount(),graph.sequenceRestrictions().size(),queries.size(),found,queries.size()-found,initMs,initBytes,ms[300],ms[569],bytes/ms.length);
    }
    private static void query(CoordinateRouter router,double[] q) {
        sink=router.route(q[0],q[1],q[2],q[3]).map(CoordinateRouter.Route::distanceMetres).orElse(-1.0);
    }
}
