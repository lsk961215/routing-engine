package com.lsk.routing.core.graph;

import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;

/** Current-thread heap allocations, not retained heap or process memory. */
final class ThreadAllocation {
    private static final ThreadMXBean BEAN = initialize();

    private static ThreadMXBean initialize() {
        try {
            if (ManagementFactory.getThreadMXBean() instanceof ThreadMXBean bean
                    && bean.isThreadAllocatedMemorySupported()) {
                if (!bean.isThreadAllocatedMemoryEnabled()) bean.setThreadAllocatedMemoryEnabled(true);
                return bean;
            }
        } catch (UnsupportedOperationException | SecurityException ignored) {
            // Routing still works on JVMs that cannot expose allocation counters.
        }
        return null;
    }

    static long currentBytes() {
        if (BEAN == null || Thread.currentThread().isVirtual()) return -1;
        try {
            return BEAN.getCurrentThreadAllocatedBytes();
        } catch (UnsupportedOperationException | SecurityException ignored) {
            return -1;
        }
    }

    static Long since(long start) {
        if (start < 0) return null;
        long end = currentBytes();
        return end >= start ? end - start : null;
    }
}
