package com.latticemc.lattice.util;

import ca.spottedleaf.concurrentutil.map.ConcurrentLong2ReferenceChainedHashTable;
import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;

/** 每个 JVM 只测一种实现，避免原表/子类混合类型画像；保留结果并对照独立 oracle。 */
public final class ChunkCacheLookupBenchmark {
    private static final ThreadMXBean ALLOC = (ThreadMXBean) ManagementFactory.getThreadMXBean();
    private static final int SIZE = 16384;
    private static volatile long sink;
    private static volatile Object published;
    private record Value(int token) {}

    private static final class State {
        final ConcurrentLong2ReferenceChainedHashTable<Value> map;
        final ChunkCache<Value> holders;
        final boolean optimized;
        final boolean full;

        State(boolean full, boolean optimized, Thread owner, long[] keys, Value[] values) {
            this.full = full;
            this.optimized = optimized;
            this.map = full ? optimized ? new CachedChunkMap<>(owner) : new ConcurrentLong2ReferenceChainedHashTable<>()
                : ConcurrentLong2ReferenceChainedHashTable.createWithCapacity(SIZE, 0.25f);
            this.holders = new ChunkCache<>(owner, map::get);
            reset(keys, values);
        }

        Value get(long key) { return !full && optimized ? holders.get(key) : map.get(key); }

        void reset(long[] keys, Value[] values) {
            holders.invalidateAll();
            for (int i = 0; i < keys.length; i++) map.put(keys[i], values[i]);
        }

        void replace(long key, Value value) {
            if (!full && optimized) holders.invalidate(key);
            map.remove(key);
            map.put(key, value);
        }
    }

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        ALLOC.setThreadAllocatedMemoryEnabled(true);
        String mode = System.getProperty("lattice.benchMode", "cached");
        if (!mode.equals("original") && !mode.equals("cached")) throw new IllegalArgumentException(mode);
        boolean optimized = mode.equals("cached");
        int samples = 15;
        long[] keys = new long[SIZE];
        Value[][] values = new Value[2][SIZE];
        for (int i = 0; i < SIZE; i++) {
            keys[i] = ((long) (i % 128 - 64) & 0xffffffffL) | ((long) (i / 128 - 64) << 32);
            for (int generation = 0; generation < 2; generation++) values[generation][i] = new Value(1 + i + generation * SIZE);
        }
        int[] spread = new int[65536], cold = new int[SIZE];
        Random random = new Random(251);
        for (int i = 0; i < spread.length; i++) spread[i] = random.nextInt(SIZE);
        for (int i = 0; i < SIZE; i++) cold[i] = i;
        for (int i = SIZE - 1; i > 0; i--) {
            int j = random.nextInt(i + 1), swap = cold[i];
            cold[i] = cold[j]; cold[j] = swap;
        }
        System.out.printf("CHUNK_BENCH java=%s mode=%s samples=%d cache=single-entry%n", System.getProperty("java.version"), mode, samples);
        for (boolean full : new boolean[] {true, false}) {
            for (String pattern : new String[] {"repeat", "locality", "spread", "cold", "miss", "churn", "foreign"}) {
                State state = new State(full, optimized, Thread.currentThread(), keys, values[0]);
                int iterations = pattern.equals("cold") ? SIZE : 1_000_000;
                int[] access = pattern.equals("cold") ? cold : spread;
                long expected = oracle(pattern, access, iterations);
                Runnable measure = () -> measure(state, pattern, keys, values, access, samples, iterations, expected);
                if (pattern.equals("foreign")) {
                    AtomicReference<Throwable> failure = new AtomicReference<>();
                    Thread worker = new Thread(() -> {
                        try { measure.run(); } catch (Throwable ex) { failure.set(ex); }
                    });
                    worker.start(); worker.join();
                    if (failure.get() != null) throw new AssertionError("Foreign reader failed", failure.get());
                } else {
                    measure.run();
                }
            }
        }
        if (published == null) throw new AssertionError("Missing benchmark state");
    }

    private static long oracle(String pattern, int[] access, int iterations) {
        int[] tokens = new int[SIZE];
        for (int i = 0; i < SIZE; i++) tokens[i] = i + 1;
        long sum = 0;
        for (int i = 0; i < iterations; i++) {
            int index = index(pattern, access, i);
            if (pattern.equals("churn") && (i & 63) == 0) tokens[index] = index + 1 + ((i >>> 6) & 1) * SIZE;
            if (!pattern.equals("miss")) sum += tokens[index];
        }
        return sum;
    }

    private static int index(String pattern, int[] access, int i) {
        return switch (pattern) {
            case "repeat" -> 0;
            case "locality" -> (i >>> 4) & (SIZE - 1);
            default -> access[i & (access.length - 1)];
        };
    }

    private static void measure(State state, String pattern, long[] keys, Value[][] values, int[] access,
                                int samples, int iterations, long expected) {
        for (int n = 0; n < 10; n++) check(run(state, pattern, keys, values, access, iterations), expected);
        double[] ns = new double[samples], bytes = new double[samples];
        for (int n = 0; n < samples; n++) {
            Measurement m = run(state, pattern, keys, values, access, iterations);
            check(m, expected);
            ns[n] = (double) m.nanos / iterations;
            bytes[n] = (double) m.bytes / iterations;
            System.out.printf("CHUNK_SAMPLE table=%s pattern=%s mode=%s sample=%d ns=%.6f bytes=%.6f checksum=%d%n",
                state.full ? "full" : "holder", pattern, state.optimized ? "cached" : "original", n, ns[n], bytes[n], m.checksum);
        }
        Arrays.sort(ns); Arrays.sort(bytes);
        System.out.printf("CHUNK_RESULT table=%s pattern=%s mode=%s ns=%.6f bytes=%.6f%n",
            state.full ? "full" : "holder", pattern, state.optimized ? "cached" : "original", ns[samples / 2], bytes[samples / 2]);
    }

    private static void check(Measurement measurement, long expected) {
        if (measurement.checksum != expected) throw new AssertionError("Lookup mismatch: " + measurement.checksum + " != " + expected);
    }

    private static Measurement run(State state, String pattern, long[] keys, Value[][] values, int[] access, int iterations) {
        published = state;
        // 冷查询每轮只遍历每个正值一次；只重置逻辑缓存，不声称清空 CPU cache。
        if (pattern.equals("churn") || pattern.equals("cold")) state.reset(keys, values[0]);
        int kind = switch (pattern) { case "repeat" -> 0; case "locality" -> 1; case "miss" -> 3; case "churn" -> 4; default -> 2; };
        long thread = Thread.currentThread().threadId(), allocation = ALLOC.getThreadAllocatedBytes(thread);
        long start = System.nanoTime(), sum = 0;
        for (int i = 0; i < iterations; i++) {
            int index = kind == 0 ? 0 : kind == 1 ? (i >>> 4) & (SIZE - 1) : access[i & (access.length - 1)];
            long key = keys[index];
            if (kind == 3) key ^= 0x4000000000000000L;
            if (kind == 4 && (i & 63) == 0) state.replace(key, values[(i >>> 6) & 1][index]);
            Value value = state.get(key);
            if (value != null) sum += value.token;
        }
        long nanos = System.nanoTime() - start, bytes = ALLOC.getThreadAllocatedBytes(thread) - allocation;
        sink = sum;
        return new Measurement(nanos, bytes, sum);
    }

    private record Measurement(long nanos, long bytes, long checksum) {}
}
