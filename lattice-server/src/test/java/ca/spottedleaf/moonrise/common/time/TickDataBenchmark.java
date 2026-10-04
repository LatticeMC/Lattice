package ca.spottedleaf.moonrise.common.time;

import ca.spottedleaf.moonrise.common.time.TickDataTestSupport.OriginalTickData;
import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.Locale;

/**
 * 七窗口写入与五秒 TPS 查询的端到端微基准，不代表服务端 MSPT。
 * 运行本 main 时使用 test runtime classpath，建议 -Xms1g -Xmx1g，至少四个独立 JVM。
 * -Dlattice.tpsBenchReverse=true 反转每对测量的次序；每轮还会交替次序。
 */
public final class TickDataBenchmark {
    private static final long[] WINDOWS = {1, 5, 10, 15, 60, 300, 900};
    private static final ThreadMXBean ALLOCATIONS = (ThreadMXBean) ManagementFactory.getThreadMXBean();
    private static volatile double sink;

    private record Scenario(String name, int tps, boolean query, boolean primeAll, boolean changeRate) {}

    private abstract static class Windows {
        final long period;
        long start;

        Windows(long period) { this.period = period; }
        abstract void add(TickTime time);
        abstract double query(int window, long interval);

        void next() {
            long previous = start;
            start += period;
            add(TickDataTestSupport.tick(previous, start, period / 4));
        }
    }

    private static final class OriginalWindows extends Windows {
        final OriginalTickData[] windows = new OriginalTickData[WINDOWS.length];

        OriginalWindows(long period) {
            super(period);
            for (int i = 0; i < windows.length; i++) windows[i] = new OriginalTickData(WINDOWS[i] * 1_000_000_000L);
        }

        @Override void add(TickTime time) {
            for (OriginalTickData window : windows) window.addDataFrom(time);
        }

        @Override double query(int window, long interval) { return windows[window].getTPSAverage(null, interval); }
    }

    private static final class CachedWindows extends Windows {
        final TickData[] windows = new TickData[WINDOWS.length];

        CachedWindows(long period) {
            super(period);
            for (int i = 0; i < windows.length; i++) windows[i] = new TickData(WINDOWS[i] * 1_000_000_000L);
        }

        @Override void add(TickTime time) {
            for (TickData window : windows) window.addDataFrom(time);
        }

        @Override double query(int window, long interval) { return windows[window].getTPSAverage(null, interval); }
    }

    public static void main(String[] args) {
        Locale.setDefault(Locale.ROOT);
        if (!ALLOCATIONS.isThreadAllocatedMemorySupported()) throw new IllegalStateException("Allocation counter unavailable");
        ALLOCATIONS.setThreadAllocatedMemoryEnabled(true);
        boolean reverse = Boolean.getBoolean("lattice.tpsBenchReverse");
        int warmup = Integer.getInteger("lattice.tpsBenchWarmup", 10);
        int samples = Integer.getInteger("lattice.tpsBenchSamples", 15);
        System.out.printf("TPS_BENCH java=%s warmup=%d samples=%d reverse=%s%n",
            System.getProperty("java.version"), warmup, samples, reverse);
        Scenario[] scenarios = {
            new Scenario("steady-20", 20, true, false, false),
            new Scenario("steady-1000", 1000, true, false, false),
            new Scenario("all-cached-20", 20, true, true, false),
            new Scenario("all-cached-1000", 1000, true, true, false),
            new Scenario("no-query-1000", 1000, false, false, false),
            new Scenario("no-query-cached-1000", 1000, false, true, false),
            new Scenario("rate-change-every-tick-20", 20, true, true, true)
        };
        for (int s = 0; s < scenarios.length; s++) {
            Scenario scenario = scenarios[reverse ? scenarios.length - 1 - s : s];
            int iterations = scenario.tps == 1000 && scenario.query ? 10_000 : 100_000;
            Windows[] states = {new OriginalWindows(1_000_000_000L / scenario.tps), new CachedWindows(1_000_000_000L / scenario.tps)};
            for (Windows state : states) {
                // 所有窗口预填满，排除启动期扩容；模拟真实保留 15 分钟历史。
                for (int i = 0; i <= 900 * scenario.tps; i++) state.next();
                if (scenario.primeAll) for (int i = 0; i < WINDOWS.length; i++) state.query(i, state.period);
            }
            for (int i = 0; i < warmup; i++) for (int mode = 0; mode < 2; mode++) run(states[mode], scenario, iterations);
            double[][] nanos = new double[2][samples];
            double[][] bytes = new double[2][samples];
            for (int i = 0; i < samples; i++) {
                Measurement[] pair = new Measurement[2];
                for (int order = 0; order < 2; order++) {
                    int mode = (order + i + (reverse ? 1 : 0)) & 1;
                    Measurement result = pair[mode] = run(states[mode], scenario, iterations);
                    nanos[mode][i] = (double) result.nanos / iterations;
                    bytes[mode][i] = (double) result.bytes / iterations;
                    System.out.printf("TPS_SAMPLE scenario=%s mode=%s sample=%d iterations=%d ns=%.6f bytes=%.6f%n",
                        scenario.name, mode == 0 ? "original" : "cached", i, iterations, nanos[mode][i], bytes[mode][i]);
                }
                if (Double.doubleToRawLongBits(pair[0].checksum) != Double.doubleToRawLongBits(pair[1].checksum)) {
                    throw new AssertionError("Benchmark result differs: " + scenario.name);
                }
            }
            for (int mode = 0; mode < 2; mode++) {
                Arrays.sort(nanos[mode]);
                Arrays.sort(bytes[mode]);
                System.out.printf("TPS_RESULT scenario=%s mode=%s ns-per-tick=%.6f bytes-per-tick=%.6f%n",
                    scenario.name, mode == 0 ? "original" : "cached", nanos[mode][samples / 2], bytes[mode][samples / 2]);
            }
        }
        if (!Double.isFinite(sink)) throw new AssertionError("Invalid benchmark checksum");
    }

    private static Measurement run(Windows state, Scenario scenario, int iterations) {
        long thread = Thread.currentThread().threadId();
        long beforeBytes = ALLOCATIONS.getThreadAllocatedBytes(thread);
        long beforeTime = System.nanoTime();
        double sum = 0;
        for (int i = 0; i < iterations; i++) {
            synchronized (state) { state.next(); }
            if (scenario.query) {
                long interval = state.period + (scenario.changeRate ? (i & 1) : 0);
                synchronized (state) { sum += state.query(1, interval); }
            }
        }
        long nanos = System.nanoTime() - beforeTime;
        long bytes = ALLOCATIONS.getThreadAllocatedBytes(thread) - beforeBytes;
        sink = sum + state.start;
        return new Measurement(nanos, bytes, sum);
    }

    private record Measurement(long nanos, long bytes, double checksum) {}
}
