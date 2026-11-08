package com.latticemc.lattice.nativelib;

import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * Isolates snapshot/column marshalling plus the same native query. This is not
 * a level scan or a replay of the complete old server. No Mockito is timed.
 */
public final class AnimalMarshallingAuditBenchmark {
    private static final long SEED = 20261108L;
    private static volatile long sink;

    private record Candidate(int id, int ordinal, boolean matches, double x, double y, double z,
                             boolean alive, boolean spectator) {
        double distance() {
            return x * x + y * y + z * z;
        }
    }

    private record Fixture(Candidate[] candidates, int matches, int[] expected, long checksum) {}
    private record Sample(long nanos, long bytes, long checksum) {}

    public static void main(String[] args) {
        Locale.setDefault(Locale.ROOT);
        int warmup = positive("warmup"), samples = positive("samples"), rounds = positive("rounds");
        if (!NativeEntityQuery.isAvailable()) throw new IllegalStateException(LatticeNative.failureReason());
        ThreadMXBean allocations = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        if (!allocations.isThreadAllocatedMemorySupported()) throw new IllegalStateException("allocation counter unavailable");
        allocations.setThreadAllocatedMemoryEnabled(true);
        long threadId = Thread.currentThread().threadId();
        System.out.printf("metadata,java=%s,vendor=%s,cpu=%s,seed=%d,warmup=%d,samples=%d,rounds=%d%n",
                System.getProperty("java.runtime.version"), System.getProperty("java.vendor"),
                LatticeNative.cpuSummary(), SEED, warmup, samples, rounds);
        System.out.println("sample,candidates,hitPercent,matches,productionNativeGate,arm,index,rounds,totalNs,allocatedBytes,checksum");
        int cases = 0;
        for (int count : new int[] {8, 32, 128, 512}) {
            for (int percent : new int[] {50, 100}) {
                Fixture fixture = fixture(count, percent);
                validateResult(fixture, false);
                validateResult(fixture, true);
                for (int i = 0; i < warmup; ++i) {
                    batch(fixture, i % 2 == 0, rounds);
                    batch(fixture, i % 2 != 0, rounds);
                }
                Sample[][] measured = new Sample[2][samples];
                for (int i = 0; i < samples; ++i) {
                    int first = i % 2;
                    measured[first][i] = measure(fixture, first == 1, rounds, allocations, threadId);
                    measured[1 - first][i] = measure(fixture, first != 1, rounds, allocations, threadId);
                }
                for (int arm = 0; arm < 2; ++arm) {
                    for (int i = 0; i < samples; ++i) {
                        Sample sample = measured[arm][i];
                        System.out.printf("sample,%d,%d,%d,%s,%s,%d,%d,%d,%d,%d%n",
                                count, percent, fixture.matches(), fixture.matches() >= 8,
                                arm == 0 ? "snapshot" : "columns", i, rounds,
                                sample.nanos(), sample.bytes(), sample.checksum());
                    }
                }
                ++cases;
            }
        }
        System.out.printf("completed,cases=%d,samples=%d,parity=passed%n", cases, cases * samples * 2);
    }

    private static int positive(String name) {
        int value = Integer.getInteger("animalMarshalling." + name, name.equals("rounds") ? 256 : name.equals("warmup") ? 32 : 21);
        if (value <= 0) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }

    private static Fixture fixture(int count, int percent) {
        Random random = new Random(SEED + count * 100L + percent);
        Candidate[] candidates = new Candidate[count];
        int matches = count * percent / 100;
        for (int i = 0; i < count; ++i) {
            candidates[i] = new Candidate(1000 + i, i, i < matches,
                    random.nextInt(16), random.nextInt(16), random.nextInt(16),
                    i % 11 != 0, i % 13 == 0);
        }
        int[] expected = Arrays.stream(candidates)
                .filter(c -> c.matches() && c.alive() && !c.spectator())
                .sorted(Comparator.comparingDouble(Candidate::distance)
                        .thenComparing(Comparator.comparingInt(Candidate::ordinal).reversed()))
                .mapToInt(Candidate::id).toArray();
        long checksum = 1;
        for (int id : expected) checksum = checksum * 31 + id;
        return new Fixture(candidates, matches, expected, checksum);
    }

    private static Sample measure(Fixture fixture, boolean columns, int rounds,
                                  ThreadMXBean allocations, long threadId) {
        long beforeBytes = allocations.getThreadAllocatedBytes(threadId);
        long start = System.nanoTime();
        long checksum = batch(fixture, columns, rounds);
        long nanos = System.nanoTime() - start;
        long bytes = allocations.getThreadAllocatedBytes(threadId) - beforeBytes;
        if (nanos <= 0 || bytes < 0) throw new IllegalStateException("invalid measurement");
        return new Sample(nanos, bytes, checksum);
    }

    private static long batch(Fixture fixture, boolean columns, int rounds) {
        long total = 0;
        for (int i = 0; i < rounds; ++i) {
            Map<Integer, Candidate> byId = new HashMap<>(fixture.matches());
            int[] result = query(fixture, columns, byId);
            long checksum = 1;
            for (int id : result) checksum = checksum * 31 + byId.get(id).id();
            total += checksum;
        }
        if (total != fixture.checksum() * rounds) throw new IllegalStateException("batch checksum mismatch");
        sink = total;
        return total;
    }

    private static void validateResult(Fixture fixture, boolean columns) {
        Map<Integer, Candidate> byId = new HashMap<>(fixture.matches());
        int[] result = query(fixture, columns, byId);
        if (!Arrays.equals(fixture.expected(), result)) throw new IllegalStateException("ordered result mismatch");
        for (int id : result) {
            Candidate original = fixture.candidates()[id - 1000];
            if (byId.get(id) != original) throw new IllegalStateException("candidate identity mismatch");
        }
    }

    private static int[] query(Fixture fixture, boolean columns, Map<Integer, Candidate> byId) {
        if (!columns) {
            NativeEntityQuery.EntitySnapshot[] snapshots = new NativeEntityQuery.EntitySnapshot[fixture.matches()];
            int index = 0;
            for (Candidate c : fixture.candidates()) {
                if (!c.matches()) continue;
                byId.put(c.id(), c);
                snapshots[index++] = new NativeEntityQuery.EntitySnapshot(c.id(), 1,
                        c.x(), c.y(), c.z(), c.x() - 0.5, c.y() - 0.5, c.z() - 0.5,
                        c.x() + 0.5, c.y() + 0.5, c.z() + 0.5, c.alive(), c.spectator());
            }
            return NativeEntityQuery.queryAll(-1, -1, -1, 16, 16, 16, snapshots, null,
                    NativeEntityQuery.PredicateKind.IS_ALIVE_NOT_SELF_NOT_SPEC, -1, true, 0, 0, 0);
        }
        int count = fixture.matches();
        int[] ids = new int[count], types = new int[count];
        double[] positions = new double[count * NativeEntityQuery.POSITION_STRIDE];
        double[] boxes = new double[count * NativeEntityQuery.AABB_STRIDE];
        boolean[] alive = new boolean[count], spectator = new boolean[count];
        int index = 0;
        for (Candidate c : fixture.candidates()) {
            if (!c.matches()) continue;
            byId.put(c.id(), c);
            ids[index] = c.id();
            types[index] = 1;
            int p = index * NativeEntityQuery.POSITION_STRIDE;
            positions[p] = c.x();
            positions[p + 1] = c.y();
            positions[p + 2] = c.z();
            int b = index * NativeEntityQuery.AABB_STRIDE;
            boxes[b] = c.x() - 0.5;
            boxes[b + 1] = c.y() - 0.5;
            boxes[b + 2] = c.z() - 0.5;
            boxes[b + 3] = c.x() + 0.5;
            boxes[b + 4] = c.y() + 0.5;
            boxes[b + 5] = c.z() + 0.5;
            alive[index] = c.alive();
            spectator[index] = c.spectator();
            ++index;
        }
        return NativeEntityQuery.queryAll(-1, -1, -1, 16, 16, 16,
                ids, types, positions, boxes, alive, spectator, null,
                NativeEntityQuery.PredicateKind.IS_ALIVE_NOT_SELF_NOT_SPEC, -1, true, 0, 0, 0);
    }
}
