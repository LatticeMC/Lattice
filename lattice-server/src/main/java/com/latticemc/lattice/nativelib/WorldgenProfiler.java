package com.latticemc.lattice.nativelib;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

public final class WorldgenProfiler {
    private static final boolean AVAILABLE = Boolean.getBoolean("lattice.worldgenProfilerAvailable")
            || Boolean.getBoolean("lattice.worldgenProfiler");
    private static volatile boolean ENABLED = AVAILABLE && Boolean.getBoolean("lattice.worldgenProfiler");
    private static volatile boolean HOT_LOOPS_ENABLED = AVAILABLE && Boolean.getBoolean("lattice.worldgenProfilerHotLoops");
    private static final ConcurrentHashMap<String, Probe> PROBES = new ConcurrentHashMap<>();

    public static final Probe NOISE_UPDATE_FOR_Y = probe("noise.updateForY");
    public static final Probe NOISE_UPDATE_FOR_X = probe("noise.updateForX");
    public static final Probe NOISE_UPDATE_FOR_Z = probe("noise.updateForZ");
    public static final Probe NOISE_INTERPOLATOR_SELECT_CELL_YZ_FLAT = probe("noise.interpolator.selectCellYZ.flat");
    public static final Probe NOISE_INTERPOLATOR_UPDATE_FOR_Y_FLAT = probe("noise.interpolator.updateForY.flat");
    public static final Probe NOISE_INTERPOLATOR_UPDATE_FOR_X_FLAT = probe("noise.interpolator.updateForX.flat");
    public static final Probe NOISE_INTERPOLATOR_UPDATE_FOR_Z_FLAT = probe("noise.interpolator.updateForZ.flat");

    private WorldgenProfiler() {}

    public static boolean available() {
        return AVAILABLE;
    }

    public static boolean enabled() {
        return ENABLED;
    }

    public static void setEnabled(boolean enabled) {
        ENABLED = AVAILABLE && enabled;
    }

    public static void setHotLoopsEnabled(boolean enabled) {
        HOT_LOOPS_ENABLED = AVAILABLE && enabled;
    }

    public static long start() {
        return AVAILABLE && ENABLED ? System.nanoTime() : 0L;
    }

    public static long hotLoopStart() {
        return AVAILABLE && ENABLED && HOT_LOOPS_ENABLED ? System.nanoTime() : 0L;
    }

    public static void end(String name, long startNanos) {
        if (!AVAILABLE || startNanos == 0L) return;
        long elapsed = System.nanoTime() - startNanos;
        probe(name).add(elapsed);
    }

    public static void end(Probe probe, long startNanos) {
        if (!AVAILABLE || startNanos == 0L) return;
        probe.add(System.nanoTime() - startNanos);
    }

    /** 在诊断批次之间、工作线程静止时清零；保留调用方缓存的 Probe 引用。 */
    public static void reset() {
        for (Probe probe : PROBES.values()) {
            probe.count.reset();
            probe.nanos.reset();
        }
    }

    public record Sample(String name, long count, long nanos) {}

    /** 探针时间包含内部嵌套调用，不能把嵌套样本当作独立工作相加。 */
    public static List<Sample> snapshot() {
        List<Sample> samples = new ArrayList<>();
        PROBES.forEach((name, probe) -> samples.add(new Sample(name, probe.count.sum(), probe.nanos.sum())));
        samples.sort(Comparator.comparing(Sample::name));
        return List.copyOf(samples);
    }

    public static String status() {
        if (PROBES.isEmpty()) return "worldgenProfiler=" + ENABLED + " available=" + AVAILABLE + " hotLoops=" + HOT_LOOPS_ENABLED + " {}";
        List<Map.Entry<String, Probe>> entries = new ArrayList<>(PROBES.entrySet());
        entries.sort(Comparator.comparingLong((Map.Entry<String, Probe> entry) -> entry.getValue().nanos.sum()).reversed());
        StringBuilder builder = new StringBuilder("worldgenProfiler=")
                .append(ENABLED)
                .append(" available=")
                .append(AVAILABLE)
                .append(" hotLoops=")
                .append(HOT_LOOPS_ENABLED)
                .append(" {");
        long total = 0L;
        for (Map.Entry<String, Probe> entry : entries) total += entry.getValue().nanos.sum();
        int written = 0;
        for (Map.Entry<String, Probe> entry : entries) {
            if (written++ > 0) builder.append(", ");
            if (written > 24) {
                builder.append("...");
                break;
            }
            Probe probe = entry.getValue();
            long nanos = probe.nanos.sum();
            long count = probe.count.sum();
            long avgMicros = count == 0L ? 0L : nanos / count / 1_000L;
            long millis = nanos / 1_000_000L;
            long pctTimes100 = total == 0L ? 0L : nanos * 10_000L / total;
            builder.append(entry.getKey())
                    .append("=")
                    .append(millis)
                    .append("ms/")
                    .append(count)
                    .append(" avg=")
                    .append(avgMicros)
                    .append("us pct=")
                    .append(pctTimes100 / 100L)
                    .append('.')
                    .append(pctTimes100 % 100L);
        }
        return builder.append('}').toString();
    }

    public static Probe probe(String name) {
        return PROBES.computeIfAbsent(name, ignored -> new Probe());
    }

    public static final class Probe {
        private final LongAdder count = new LongAdder();
        private final LongAdder nanos = new LongAdder();

        private void add(long elapsed) {
            count.increment();
            nanos.add(elapsed);
        }
    }
}
