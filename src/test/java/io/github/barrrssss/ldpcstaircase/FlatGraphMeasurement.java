package io.github.barrrssss.ldpcstaircase;

import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;

/**
 * Test-only measurement of flat primitive adjacency versus the
 * previous {@code int[][]} forest, at the calibrated release shapes.
 *
 * <p>Two numbers per shape:
 *
 * <ul>
 *   <li><b>allocated</b> — exact bytes allocated on this thread during one build, from
 *       {@code ThreadMXBean.getThreadAllocatedBytes}. This is construction churn: what the
 *       allocator and GC actually move, including the growable builders.
 *   <li><b>retained</b> — the live size of the finished graph, computed from the JVM object
 *       layout (8-byte alignment, 16-byte array header, 4-byte compressed refs) rather than
 *       estimated, so the object-header cost of one array per row and per column is explicit.
 * </ul>
 *
 * <p>Run with {@code ./gradlew runFlatGraphMeasurement}. It is not a unit test: it asserts
 * nothing and exists to produce reproducible storage and timing figures.
 */
public final class FlatGraphMeasurement {

    private static final int N1 = ParityCheckMatrix.DEFAULT_LEFT_DEGREE;

    public static void main(String[] args) {
        ThreadMXBean threads = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        long self = Thread.currentThread().getId();

        System.out.println("shape                     | old alloc | new alloc | old retain | new retain "
                + "| old build | new build");
        System.out.println("--------------------------|-----------|-----------|------------|------------"
                + "|-----------|----------");

        int[][] shapes = {
            {2939, 2939, 20250812},
            {10000, 5000, 777},
            {16000, 5600, 31337},
            {16000, 8192, 8191},
        };

        for (int[] shape : shapes) {
            int k = shape[0];
            int r = shape[1];
            int seed = shape[2];

            // Warm up both paths so JIT state is comparable.
            for (int i = 0; i < 3; i++) {
                ReferenceParityCheckMatrix.build(k, r, N1, seed);
                ParityCheckMatrix.build(k, r, N1, seed);
            }

            long before = threads.getThreadAllocatedBytes(self);
            ReferenceParityCheckMatrix oldGraph = ReferenceParityCheckMatrix.build(k, r, N1, seed);
            long oldAllocated = threads.getThreadAllocatedBytes(self) - before;

            before = threads.getThreadAllocatedBytes(self);
            ParityCheckMatrix newGraph = ParityCheckMatrix.build(k, r, N1, seed);
            long newAllocated = threads.getThreadAllocatedBytes(self) - before;

            long oldBuild = timeOld(k, r, seed);
            long newBuild = timeNew(k, r, seed);

            System.out.printf("K=%-6d R=%-6d seed=%-7d| %9s | %9s | %10s | %10s | %6d ms | %6d ms%n",
                    k, r, seed,
                    mib(oldAllocated), mib(newAllocated),
                    mib(retainedOld(oldGraph, k, r)), mib(retainedNew(newGraph, k, r)),
                    oldBuild, newBuild);
        }
    }

    private static long timeOld(int k, int r, int seed) {
        long start = System.nanoTime();
        for (int i = 0; i < 5; i++) {
            ReferenceParityCheckMatrix.build(k, r, N1, seed);
        }
        return (System.nanoTime() - start) / 5 / 1_000_000;
    }

    private static long timeNew(int k, int r, int seed) {
        long start = System.nanoTime();
        for (int i = 0; i < 5; i++) {
            ParityCheckMatrix.build(k, r, N1, seed);
        }
        return (System.nanoTime() - start) / 5 / 1_000_000;
    }

    /** Live bytes of the object-forest graph: two outer ref arrays plus one int[] per row and column. */
    private static long retainedOld(ReferenceParityCheckMatrix graph, int k, int r) {
        int n = k + r;
        long total = refArray(r) + refArray(n);
        for (int i = 0; i < r; i++) {
            total += intArray(graph.rowColumns[i].length);
        }
        for (int c = 0; c < n; c++) {
            total += intArray(graph.columnRows[c].length);
        }
        return total;
    }

    /** Live bytes of the flat graph: four primitive arrays, no per-row or per-column object. */
    private static long retainedNew(ParityCheckMatrix graph, int k, int r) {
        int n = k + r;
        int entries = graph.entryCount();
        return intArray(r + 1) + intArray(entries) + intArray(n + 1) + intArray(entries);
    }

    /** int[len]: 16-byte header + 4 bytes per element, aligned to 8. */
    private static long intArray(int length) {
        return align(16L + 4L * length);
    }

    /** Object[len] with compressed oops: 16-byte header + 4 bytes per reference, aligned to 8. */
    private static long refArray(int length) {
        return align(16L + 4L * length);
    }

    private static long align(long bytes) {
        return (bytes + 7) & ~7L;
    }

    private static String mib(long bytes) {
        return String.format("%.2f MiB", bytes / (1024.0 * 1024.0));
    }

    private FlatGraphMeasurement() {
    }
}
