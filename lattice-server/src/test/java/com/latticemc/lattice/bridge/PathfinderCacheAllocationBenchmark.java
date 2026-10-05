package com.latticemc.lattice.bridge;

import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.Locale;
import net.minecraft.core.BlockPos;

/** 修复前/后类分别由独立 JVM 加载；计时入口、输入和校验完全相同。 */
public final class PathfinderCacheAllocationBenchmark {
    private static final ThreadMXBean ALLOCATION = (ThreadMXBean) ManagementFactory.getThreadMXBean();
    private static volatile long sink;
    private static final int WARMUP = 5;
    private static final int SAMPLES = 11;
    private static final int SNAPSHOT_POINTS = 65 * 8 * 17;

    public static void main(String[] args) {
        Locale.setDefault(Locale.ROOT);
        ALLOCATION.setThreadAllocatedMemoryEnabled(true);
        System.out.printf("PATH_CACHE_BENCH java=%s mode=%s origin=%s warmup=%d samples=%d%n",
            System.getProperty("java.version"), System.getProperty("lattice.pathCacheBenchMode", "fixed"),
            PathfinderTickStateCache.class.getProtectionDomain().getCodeSource().getLocation(), WARMUP, SAMPLES);
        String[] patterns = {"hot", "hot-saturated", "cold-repeat", "cold-alternating", "cold-spread", "saturated-refill", "snapshot-cold"};
        boolean reverse = Boolean.getBoolean("lattice.pathCacheBenchReverse");
        for (int order = 0; order < patterns.length; order++) {
            String pattern = patterns[reverse ? patterns.length - 1 - order : order];
            PathfinderCacheTestSupport fixture = new PathfinderCacheTestSupport();
            PathfinderTickStateCache cache = new PathfinderTickStateCache();
            cache.begin(fixture.level, 0);
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            if (!pattern.equals("hot")) fixture.saturate(cache);
            if (pattern.equals("hot") || pattern.equals("hot-saturated")) {
                for (int cell = 0; cell < 64; cell++) cache.descriptorAt(fixture.region, pos.set(cell & 15, 0, cell >>> 4));
            }
            if (!pattern.equals("hot")) cache.descriptorAt(fixture.region, pos.set(8192, 0, 0));
            PathfinderStateSnapshot snapshot = new PathfinderStateSnapshot();
            int iterations = pattern.equals("snapshot-cold") ? 8 : pattern.startsWith("cold-") && !pattern.equals("cold-repeat") ? 8192 : 500_000;
            long points = (long) iterations * (pattern.equals("snapshot-cold") ? SNAPSHOT_POINTS : 1);
            for (int i = 0; i < WARMUP; i++) check(run(pattern, fixture, cache, snapshot, pos, iterations), points);
            double[] ns = new double[SAMPLES], bytes = new double[SAMPLES];
            for (int sample = 0; sample < SAMPLES; sample++) {
                Measurement m = run(pattern, fixture, cache, snapshot, pos, iterations);
                check(m, points);
                ns[sample] = (double) m.nanos / points;
                bytes[sample] = (double) m.bytes / points;
                System.out.printf("PATH_CACHE_SAMPLE pattern=%s sample=%d points=%d ns=%.6f bytes=%.6f hits=%d misses=%d checksum=%d%n",
                    pattern, sample, points, ns[sample], bytes[sample], m.hits, m.misses, m.checksum);
            }
            Arrays.sort(ns); Arrays.sort(bytes);
            System.out.printf("PATH_CACHE_RESULT pattern=%s ns=%.6f bytes=%.6f%n", pattern, ns[SAMPLES / 2], bytes[SAMPLES / 2]);
            cache.begin(null, 0);
        }
    }

    private static void check(Measurement measurement, long points) {
        // 所有输入均为静态石头，其首个descriptor为0；+1使错误返回-1不能蒙混过关。
        if (measurement.checksum != points || measurement.hits + measurement.misses != points) {
            throw new AssertionError("Incorrect descriptors or accounting: " + measurement);
        }
    }

    private static Measurement run(String pattern, PathfinderCacheTestSupport fixture, PathfinderTickStateCache cache,
                                   PathfinderStateSnapshot snapshot, BlockPos.MutableBlockPos pos, int iterations) {
        int kind = switch (pattern) {
            case "cold-repeat" -> 1;
            case "cold-alternating" -> 2;
            case "cold-spread" -> 3;
            case "saturated-refill" -> 4;
            case "snapshot-cold" -> 5;
            default -> 0;
        };
        long hits = cache.hits(), misses = cache.misses();
        long thread = Thread.currentThread().threadId(), bytes = ALLOCATION.getThreadAllocatedBytes(thread);
        long start = System.nanoTime(), sum = 0;
        for (int i = 0; i < iterations; i++) {
            if (kind == 5) {
                if (!snapshot.fill(fixture.region, cache, 8192, 0, 0, 65, 8, 17)) throw new AssertionError("Snapshot rejected stone");
                for (int cell = 0; cell < snapshot.cellCount(); cell++) sum += snapshot.cells()[cell] + 1;
            } else {
                int section = kind == 1 ? 512 : kind == 2 ? 512 + (i & 1) : kind == 3 ? 512 + (i & 1023) : 0;
                // 热读轮换64个cell，避免JIT将恒定位置的重复读取折叠成一次。
                pos.set((section << 4) + (kind == 0 ? i & 15 : 0), 0, kind == 0 ? (i >>> 4) & 3 : 0);
                if (kind == 4 && (i & 63) == 0) PathfinderTickStateCache.invalidate(fixture.level, pos);
                sum += cache.descriptorAt(fixture.region, pos) + 1;
            }
        }
        long nanos = System.nanoTime() - start;
        bytes = ALLOCATION.getThreadAllocatedBytes(thread) - bytes;
        sink = sum;
        return new Measurement(nanos, bytes, cache.hits() - hits, cache.misses() - misses, sum);
    }

    private record Measurement(long nanos, long bytes, long hits, long misses, long checksum) {}
}
