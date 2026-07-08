package com.latticemc.lattice.nativelib;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

public final class WorldgenProfiler {
    private static volatile boolean ENABLED = Boolean.getBoolean("lattice.worldgenProfiler");
    private static final ConcurrentHashMap<String, Probe> PROBES = new ConcurrentHashMap<>();

    private WorldgenProfiler() {}

    public static boolean enabled() {
        return ENABLED;
    }

    public static void setEnabled(boolean enabled) {
        ENABLED = enabled;
    }

    public static long start() {
        return ENABLED ? System.nanoTime() : 0L;
    }

    public static void end(String name, long startNanos) {
        if (startNanos == 0L) return;
        long elapsed = System.nanoTime() - startNanos;
        Probe probe = PROBES.computeIfAbsent(name, ignored -> new Probe());
        probe.count.increment();
        probe.nanos.add(elapsed);
    }

    public static void reset() {
        PROBES.clear();
    }

    public static String status() {
        if (PROBES.isEmpty()) return "worldgenProfiler=" + ENABLED + " {}";
        List<Map.Entry<String, Probe>> entries = new ArrayList<>(PROBES.entrySet());
        entries.sort(Comparator.comparingLong((Map.Entry<String, Probe> entry) -> entry.getValue().nanos.sum()).reversed());
        StringBuilder builder = new StringBuilder("worldgenProfiler=").append(ENABLED).append(" {");
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

    private static final class Probe {
        private final LongAdder count = new LongAdder();
        private final LongAdder nanos = new LongAdder();
    }
}
