package com.lsk.routing.core.graph;

import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ThreadAllocationTest {
    private static volatile byte[] retained;

    @Test void countsCurrentThreadAllocationsButNotAnotherThreadsAllocations() throws Exception {
        assumeTrue(ThreadAllocation.currentBytes() >= 0);
        int size = 8 * 1024 * 1024;
        // Start the other thread before taking the baseline to exclude thread setup.
        var start = new CountDownLatch(1);
        var finished = new CountDownLatch(1);
        var workerBytes = new CompletableFuture<Long>();
        var worker = Thread.ofPlatform().start(() -> {
            try {
                start.await();
                long before = ThreadAllocation.currentBytes();
                retained = new byte[size];
                workerBytes.complete(ThreadAllocation.since(before));
            } catch (Throwable error) {
                workerBytes.completeExceptionally(error);
            } finally { finished.countDown(); }
        });
        long before = ThreadAllocation.currentBytes();
        start.countDown();
        assertTrue(finished.await(10, TimeUnit.SECONDS));
        Long ownBytes = ThreadAllocation.since(before);
        worker.join();
        assertNotNull(ownBytes);
        assertTrue(ownBytes < size, "Another thread's allocation must not enter this thread's metric");
        assertTrue(workerBytes.get() >= size);
        before = ThreadAllocation.currentBytes();
        retained = new byte[size];
        assertTrue(ThreadAllocation.since(before) >= size);
        retained = null;
    }

    @Test void virtualThreadSearchStillWorksAndMarksAllocationUnavailable() throws Exception {
        var graph = RoutingTestGraphs.grid(3, false);
        var router = new CoordinateRouter(graph);
        var start = RoutingTestGraphs.point(graph, 0, .25);
        var end = RoutingTestGraphs.point(graph, graph.edgeCount() - 1, .75);
        var reference = router.compare(start[0], start[1], end[0], end[1]);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var result = executor.submit(() -> router.compare(start[0], start[1], end[0], end[1])).get();
            for (int i = 0; i < 2; i++) {
                assertEquals(reference.results().get(i).route(), result.results().get(i).route());
                assertNull(result.results().get(i).allocatedBytes());
            }
        }
    }
}
