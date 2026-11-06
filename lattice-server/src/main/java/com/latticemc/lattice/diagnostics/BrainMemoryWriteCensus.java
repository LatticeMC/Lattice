package com.latticemc.lattice.diagnostics;

import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/** Opt-in exact census of memory-write calls made during Brain ticks. */
public final class BrainMemoryWriteCensus {
    private static final String OUTPUT_PROPERTY = "lattice.brainMemoryWriteCensus";
    private static final String OUTPUT_PATH = System.getProperty(OUTPUT_PROPERTY, "");
    private static final boolean ENABLED = !OUTPUT_PATH.isBlank();
    private static final Collector COLLECTOR = new Collector();

    static {
        if (ENABLED) {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    COLLECTOR.writeCsv(Path.of(OUTPUT_PATH));
                } catch (IOException exception) {
                    throw new IllegalStateException("Could not write Brain memory census to " + OUTPUT_PATH, exception);
                }
            }, "brain-memory-write-census"));
        }
    }

    private BrainMemoryWriteCensus() {
    }

    public static boolean isEnabled() {
        return ENABLED;
    }

    public static void beginTick(Object brain) {
        if (isEnabled()) COLLECTOR.beginTick(brain);
    }

    public static void recordWrite(Object brain) {
        if (isEnabled()) COLLECTOR.recordWrite(brain);
    }

    public static void endTick(Object brain, boolean failed) {
        if (isEnabled()) COLLECTOR.endTick(brain, failed);
    }

    /** Identity-keyed collector kept independent of Minecraft for deterministic replay tests. */
    public static final class Collector {
        private final ReferenceQueue<Object> collected = new ReferenceQueue<>();
        private final Map<IdentityKey, BrainStats> brains = new HashMap<>();
        private final Map<Long, BrainStats> completed = new TreeMap<>();
        private long nextBrainId = 1L;

        public synchronized void beginTick(Object brain) {
            drainCollected();
            IdentityKey lookup = new IdentityKey(brain, null);
            BrainStats stats = brains.get(lookup);
            if (stats == null) {
                stats = new BrainStats(nextBrainId);
                nextBrainId = Math.incrementExact(nextBrainId);
                brains.put(new IdentityKey(brain, collected), stats);
            }
            if (stats.activeWrites != null) {
                throw new IllegalStateException("Brain tick census already active");
            }
            stats.activeWrites = 0;
        }

        public synchronized void recordWrite(Object brain) {
            BrainStats stats = statsFor(brain);
            if (stats != null && stats.activeWrites != null) {
                stats.activeWrites = Math.incrementExact(stats.activeWrites);
            }
        }

        public synchronized void endTick(Object brain, boolean failed) {
            BrainStats stats = statsFor(brain);
            if (stats == null || stats.activeWrites == null) {
                throw new IllegalStateException("Brain tick census was not active");
            }
            int writes = stats.activeWrites;
            stats.activeWrites = null;
            stats.record(writes, failed);
            completed.putIfAbsent(stats.identity, stats);
        }

        public synchronized void clear() {
            brains.clear();
            completed.clear();
            drainCollected();
            nextBrainId = 1L;
        }

        public synchronized Map<Integer, Long> writeCountDistribution(Object brain) {
            BrainStats stats = statsFor(brain);
            return stats == null ? Map.of() : Map.copyOf(stats.writeCounts);
        }

        public synchronized Map<Integer, Long> streakDistribution(Object brain) {
            BrainStats stats = statsFor(brain);
            if (stats == null) return Map.of();
            TreeMap<Integer, Long> result = new TreeMap<>(stats.streaks);
            if (stats.currentStreak > 0) result.merge(stats.currentStreak, 1L, Long::sum);
            return Map.copyOf(result);
        }

        public synchronized long failedTicks(Object brain) {
            BrainStats stats = statsFor(brain);
            return stats == null ? 0L : stats.failedTicks;
        }

        public synchronized void writeCsv(Path output) throws IOException {
            drainCollected();
            Path absolute = output.toAbsolutePath();
            Path parent = absolute.getParent();
            if (parent != null) Files.createDirectories(parent);
            try (BufferedWriter writer = Files.newBufferedWriter(absolute, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                writer.write("brainIdentity,recordType,value,count,failedTicks");
                writer.newLine();
                for (Map.Entry<Long, BrainStats> brain : completed.entrySet()) {
                    String identity = Long.toString(brain.getKey());
                    BrainStats stats = brain.getValue();
                    for (Map.Entry<Integer, Long> entry : stats.writeCounts.entrySet()) {
                        writer.write(identity + ",writesPerTick," + entry.getKey() + "," + entry.getValue() + "," + stats.failedTicks);
                        writer.newLine();
                    }
                    TreeMap<Integer, Long> streaks = new TreeMap<>(stats.streaks);
                    if (stats.currentStreak > 0) streaks.merge(stats.currentStreak, 1L, Long::sum);
                    for (Map.Entry<Integer, Long> entry : streaks.entrySet()) {
                        writer.write(identity + ",consecutiveWriteTicks," + entry.getKey() + "," + entry.getValue() + "," + stats.failedTicks);
                        writer.newLine();
                    }
                }
            }
        }

        private BrainStats statsFor(Object brain) {
            drainCollected();
            return brains.get(new IdentityKey(brain, null));
        }

        private void drainCollected() {
            IdentityKey key;
            while ((key = (IdentityKey) collected.poll()) != null) {
                brains.remove(key);
            }
        }

        synchronized WeakReference<Object> referenceFor(Object brain) {
            drainCollected();
            for (IdentityKey key : brains.keySet()) {
                if (key.get() == brain) return key;
            }
            return null;
        }
    }

    private static final class IdentityKey extends WeakReference<Object> {
        private final int hash;

        private IdentityKey(Object brain, ReferenceQueue<Object> queue) {
            super(java.util.Objects.requireNonNull(brain), queue);
            hash = System.identityHashCode(brain);
        }

        @Override public int hashCode() { return hash; }

        @Override public boolean equals(Object other) {
            if (this == other) return true;
            Object brain = get();
            return brain != null && other instanceof IdentityKey key && brain == key.get();
        }
    }

    private static final class BrainStats {
        private final long identity;
        private Integer activeWrites;
        private final TreeMap<Integer, Long> writeCounts = new TreeMap<>();
        private final TreeMap<Integer, Long> streaks = new TreeMap<>();
        private int currentStreak;
        private long failedTicks;

        private BrainStats(long identity) {
            this.identity = identity;
        }

        private void record(int writes, boolean failed) {
            writeCounts.merge(writes, 1L, Long::sum);
            if (failed) failedTicks++;
            if (writes > 0) {
                currentStreak++;
            } else if (currentStreak > 0) {
                streaks.merge(currentStreak, 1L, Long::sum);
                currentStreak = 0;
            }
        }
    }
}
