package com.latticemc.lattice.nativelib;

import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.Locale;

/**
 * 独立 JVM 的 AABB 相交扫描基准：对照 native（{@code scan}/{@code scanSoa}）与 Java 参考实现
 * （{@code javaScan}/{@code javaScanSoa}）在不同实体数/查询数下的耗时与分配；四种实现输出必须一致。
 * 不是服务端 MSPT 基准，也不代表任何 AI/查询频率变化。
 */
public final class AabbQueryBenchmark {
    private static final ThreadMXBean ALLOC = (ThreadMXBean) ManagementFactory.getThreadMXBean();
    private static volatile long sink;
    private static volatile double[] publishedQueries;
    private static volatile double[] publishedAos;
    private static volatile double[] publishedSoa;
    private static volatile long[] publishedVisibility;

    private enum Impl {
        NATIVE_AOS("native-aos"),
        NATIVE_SOA("native-soa"),
        JAVA_AOS("java-aos"),
        JAVA_SOA("java-soa");
        final String label;
        Impl(final String label) { this.label = label; }
    }

    public static void main(final String[] args) {
        Locale.setDefault(Locale.ROOT);
        if (!ALLOC.isThreadAllocatedMemorySupported()) {
            throw new IllegalStateException("allocation counter unavailable");
        }
        ALLOC.setThreadAllocatedMemoryEnabled(true);
        if (!NativeAabbQuery.isAvailable()) {
            throw new IllegalStateException("native AABB query unavailable: " + LatticeNative.failureReason());
        }

        final int warmup = Integer.getInteger("lattice.aabbWarmup", 8);
        final int samples = Integer.getInteger("lattice.aabbSamples", 11);
        final boolean reverse = Boolean.getBoolean("lattice.aabbReverse");
        final int[] entityCounts = parseInts(System.getProperty("lattice.aabbEntities", "256,2048,8192"));
        final int[] queryCounts = parseInts(System.getProperty("lattice.aabbQueries", "8,64"));
        final long targetWork = Long.getLong("lattice.aabbTargetWork", 20_000_000L);

        System.out.printf("AABB_BENCH java=%s warmup=%d samples=%d reverse=%s%n",
            System.getProperty("java.version"), warmup, samples, reverse);

        for (final int entityCount : entityCounts) {
            for (final int queryCount : queryCounts) {
                runConfig(entityCount, queryCount, warmup, samples, reverse, targetWork);
            }
        }
        if (sink == Long.MIN_VALUE) throw new AssertionError("no samples ran");
    }

    private static void runConfig(final int entityCount, final int queryCount, final int warmup,
                                  final int samples, final boolean reverse, final long targetWork) {
        final double[] queries = buildQueries(queryCount);
        final double[] aos = buildAos(entityCount);
        final double[] soa = buildSoa(aos, entityCount);
        final long[] visibility = new long[queryCount * NativeAabbQuery.rowLongs(entityCount)];
        final int iterations = (int) Math.max(1, targetWork / ((long) queryCount * entityCount));

        // 正确性：四种实现在同一缓冲上输出必须一致（native 与 Java 参考各自 memset 清零）。
        final long expected = checksum(Impl.JAVA_AOS, queries, aos, soa, queryCount, entityCount, visibility);
        for (final Impl impl : Impl.values()) {
            final long actual = checksum(impl, queries, aos, soa, queryCount, entityCount, visibility);
            if (actual != expected) {
                throw new AssertionError("AABB mismatch: " + impl.label + " bits=" + actual + " != " + expected);
            }
        }

        final Impl[] impls = Impl.values();
        final double[][] nanos = new double[impls.length][samples];
        final double[][] bytes = new double[impls.length][samples];
        for (int i = 0; i < warmup; i++) {
            for (final Impl impl : impls) {
                measure(impl, queries, aos, soa, queryCount, entityCount, visibility, iterations);
            }
        }
        for (int i = 0; i < samples; i++) {
            for (int order = 0; order < impls.length; order++) {
                final int idx = (order + i + (reverse ? 1 : 0)) % impls.length;
                final Measurement m = measure(impls[idx], queries, aos, soa, queryCount, entityCount, visibility, iterations);
                nanos[idx][i] = (double) m.nanos / iterations;
                bytes[idx][i] = (double) m.bytes / iterations;
            }
        }
        for (final Impl impl : impls) {
            Arrays.sort(nanos[impl.ordinal()]);
            Arrays.sort(bytes[impl.ordinal()]);
            System.out.printf("AABB_RESULT entities=%d queries=%d impl=%s iterations=%d ns-per-scan=%.4f bytes-per-scan=%.6f%n",
                entityCount, queryCount, impl.label, iterations,
                nanos[impl.ordinal()][samples / 2], bytes[impl.ordinal()][samples / 2]);
        }
    }

    private static long checksum(final Impl impl, final double[] queries, final double[] aos, final double[] soa,
                                 final int queryCount, final int entityCount, final long[] visibility) {
        dispatch(impl, queries, aos, soa, queryCount, entityCount, visibility);
        long bits = 0;
        for (final long word : visibility) bits += Long.bitCount(word);
        return bits;
    }

    private static Measurement measure(final Impl impl, final double[] queries, final double[] aos, final double[] soa,
                                       final int queryCount, final int entityCount, final long[] visibility,
                                       final int iterations) {
        // 发布输入，禁止把整个扫描当作不逃逸的临时工作进行标量替换。
        publishedQueries = queries;
        publishedAos = aos;
        publishedSoa = soa;
        publishedVisibility = visibility;
        final long thread = Thread.currentThread().threadId();
        final long beforeBytes = ALLOC.getThreadAllocatedBytes(thread);
        final long beforeTime = System.nanoTime();
        long acc = 0;
        int vi = 0;
        for (int i = 0; i < iterations; i++) {
            dispatch(impl, queries, aos, soa, queryCount, entityCount, visibility);
            // 读一个输出字，防止整段扫描被当作死代码消除。
            acc += visibility[vi];
            if (++vi == visibility.length) vi = 0;
        }
        final long nanos = System.nanoTime() - beforeTime;
        final long bytes = ALLOC.getThreadAllocatedBytes(thread) - beforeBytes;
        sink = acc;
        return new Measurement(nanos, bytes);
    }

    private static void dispatch(final Impl impl, final double[] queries, final double[] aos, final double[] soa,
                                 final int queryCount, final int entityCount, final long[] visibility) {
        switch (impl) {
            case NATIVE_AOS -> NativeAabbQuery.scan(queries, queryCount, aos, entityCount, visibility);
            case NATIVE_SOA -> NativeAabbQuery.scanSoa(queries, queryCount, soa, entityCount, visibility);
            case JAVA_AOS -> NativeAabbQuery.javaScan(queries, queryCount, aos, entityCount, visibility);
            case JAVA_SOA -> NativeAabbQuery.javaScanSoa(queries, queryCount, soa, entityCount, visibility);
        }
    }

    /** 实体铺在 XZ 网格、y∈[0,1]，spacing 2 使彼此不重叠；查询覆盖其中若干，保证命中与 miss 混合。 */
    private static double[] buildAos(final int entityCount) {
        final double[] aos = new double[entityCount * NativeAabbQuery.AABB_STRIDE];
        final int side = Math.max(1, (int) Math.ceil(Math.sqrt(entityCount)));
        for (int e = 0; e < entityCount; e++) {
            final double x = (e % side) * 2.0;
            final double z = (e / side) * 2.0;
            final int b = e * NativeAabbQuery.AABB_STRIDE;
            aos[b] = x; aos[b + 1] = 0.0; aos[b + 2] = z;
            aos[b + 3] = x + 1.0; aos[b + 4] = 1.0; aos[b + 5] = z + 1.0;
        }
        return aos;
    }

    private static double[] buildSoa(final double[] aos, final int entityCount) {
        final double[] soa = new double[entityCount * NativeAabbQuery.AABB_STRIDE];
        for (int e = 0; e < entityCount; e++) {
            for (int c = 0; c < NativeAabbQuery.AABB_STRIDE; c++) {
                soa[c * entityCount + e] = aos[e * NativeAabbQuery.AABB_STRIDE + c];
            }
        }
        return soa;
    }

    private static double[] buildQueries(final int queryCount) {
        final double[] queries = new double[queryCount * NativeAabbQuery.AABB_STRIDE];
        for (int i = 0; i < queryCount; i++) {
            final double x = i * 3.0;
            final int b = i * NativeAabbQuery.AABB_STRIDE;
            queries[b] = x; queries[b + 1] = -1.0; queries[b + 2] = -1.0;
            queries[b + 3] = x + 4.0; queries[b + 4] = 2.0; queries[b + 5] = 48.0;
        }
        return queries;
    }

    private static int[] parseInts(final String csv) {
        final String[] parts = csv.split(",");
        final int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) out[i] = Integer.parseInt(parts[i].trim());
        return out;
    }

    private record Measurement(long nanos, long bytes) { }
}
