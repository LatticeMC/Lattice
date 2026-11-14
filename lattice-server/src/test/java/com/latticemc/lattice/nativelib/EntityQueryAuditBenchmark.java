package com.latticemc.lattice.nativelib;

import com.sun.management.ThreadMXBean;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;
import java.util.Random;

public final class EntityQueryAuditBenchmark {
    private static final int SENTINEL = -777;
    private static final long SEED = 20261107L;
    private static final NativeEntityQuery.PredicateKind PREDICATE =
            NativeEntityQuery.PredicateKind.IS_ALIVE_NOT_SELF_NOT_SPEC;
    private static final String[] ARMS = {"wrapper-native", "encoded-native", "encoded-java"};
    private static volatile long sink;

    private EntityQueryAuditBenchmark() {}

    public static void main(String[] args) throws Throwable {
        if (!NativeEntityQuery.isAvailable()) throw new IllegalStateException(LatticeNative.failureReason());
        if (LatticeNative.VERIFY) throw new IllegalStateException("Disable VERIFY for timing");
        int warmup = Integer.getInteger("entityAudit.warmup", 16);
        int samples = Integer.getInteger("entityAudit.samples", 21);
        int rounds = Integer.getInteger("entityAudit.rounds", 256);
        if (warmup < 0 || samples < 1 || rounds < 1) throw new IllegalArgumentException("Invalid sample counts");
        ThreadMXBean bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        if (!bean.isThreadAllocatedMemorySupported()) throw new IllegalStateException("Allocation counter unavailable");
        bean.setThreadAllocatedMemoryEnabled(true);
        long threadId = Thread.currentThread().threadId();
        Method method = Arrays.stream(NativeEntityQuery.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals("nativeQuery")).findFirst().orElseThrow();
        MethodHandle nativeQuery = MethodHandles.privateLookupIn(NativeEntityQuery.class, MethodHandles.lookup())
                .unreflect(method);
        System.out.println(String.format(Locale.ROOT,
                "java=%s cpu=%s seed=%d warmup=%d samples=%d rounds=%d",
                System.getProperty("java.version"), LatticeNative.cpuSummary(), SEED, warmup, samples, rounds));
        System.out.println("kind,count,sorted,type-hit-percent,max-results,path,sample,ns-per-query,java-bytes-per-query");
        int scenarios = 0;
        for (int count : new int[] {8, 32, 128, 512}) {
            for (boolean sorted : new boolean[] {false, true}) {
                for (int hitPercent : new int[] {50, 100}) {
                    for (int max : new int[] {0, 1}) {
                        Fixture fixture = new Fixture(count, sorted, hitPercent, max);
                        int[] owned = fixture.wrapperQuery();
                        if (!Arrays.equals(owned, fixture.expected)) throw new AssertionError("Wrapper oracle mismatch");
                        int[] another = fixture.wrapperQuery();
                        if (owned == another) throw new AssertionError("Wrapper result shared across calls");
                        if (another.length != 0) another[0] = SENTINEL;
                        if (!Arrays.equals(owned, fixture.expected)) throw new AssertionError("Wrapper result not owned");
                        for (int sample = -warmup; sample < samples; ++sample) {
                            int first = Math.floorMod(sample + warmup, ARMS.length);
                            for (int offset = 0; offset < ARMS.length; ++offset) {
                                int arm = (first + offset) % ARMS.length;
                                Arrays.fill(fixture.outputIds, SENTINEL);
                                if (fixture.outputDistances != null) Arrays.fill(fixture.outputDistances, SENTINEL);
                                long allocationStart = bean.getThreadAllocatedBytes(threadId);
                                long before = System.nanoTime();
                                long checksum = 0;
                                int returned = 0;
                                int[] lastResult = fixture.outputIds;
                                for (int round = 0; round < rounds; ++round) {
                                    if (arm == 0) {
                                        lastResult = fixture.wrapperQuery();
                                        returned = lastResult.length;
                                    } else {
                                        returned = arm == 1 ? fixture.nativeQuery(nativeQuery) : fixture.javaQuery();
                                    }
                                    checksum += returned;
                                    if (returned != 0) checksum += lastResult[round % returned];
                                }
                                long elapsed = System.nanoTime() - before;
                                long allocated = bean.getThreadAllocatedBytes(threadId) - allocationStart;
                                sink = checksum;
                                fixture.verify(arm, returned, lastResult);
                                long expectedChecksum = (long) rounds * fixture.expected.length;
                                if (fixture.expected.length != 0) {
                                    for (int round = 0; round < rounds; ++round) {
                                        expectedChecksum += fixture.expected[round % fixture.expected.length];
                                    }
                                }
                                if (checksum != expectedChecksum) throw new AssertionError("Batch checksum mismatch");
                                if (sample >= 0) {
                                    System.out.printf(Locale.ROOT, "sample,%d,%s,%d,%d,%s,%d,%.3f,%.3f%n",
                                            count, sorted, hitPercent, max, ARMS[arm], sample,
                                            (double) elapsed / rounds, (double) allocated / rounds);
                                }
                            }
                        }
                        ++scenarios;
                    }
                }
            }
        }
        System.out.printf("entity-query-audit-complete scenarios=%d parity=passed checksum=%d%n", scenarios, sink);
    }

    private static final class Fixture {
        final boolean sorted;
        final int max;
        final NativeEntityQuery.EntitySnapshot[] snapshots;
        final int[] ids;
        final int[] types;
        final double[] positions;
        final double[] boxes;
        final boolean[] alive;
        final boolean[] spectators;
        final int[] allowed = {1};
        final int[] expected;
        final int[] outputIds;
        final double[] outputDistances;
        final int[] originalIds;
        final int[] originalTypes;
        final double[] originalPositions;
        final double[] originalBoxes;
        final boolean[] originalAlive;
        final boolean[] originalSpectators;

        Fixture(int count, boolean sorted, int hitPercent, int max) {
            this.sorted = sorted;
            this.max = max;
            snapshots = new NativeEntityQuery.EntitySnapshot[count];
            ids = new int[count];
            types = new int[count];
            positions = new double[count * NativeEntityQuery.POSITION_STRIDE];
            boxes = new double[count * NativeEntityQuery.AABB_STRIDE];
            alive = new boolean[count];
            spectators = new boolean[count];
            Random random = new Random(SEED + count);
            ArrayList<Integer> selected = new ArrayList<>();
            for (int i = 0; i < count; ++i) {
                ids[i] = 1000 + i;
                types[i] = i < count * hitPercent / 100 ? 1 : 2;
                double x = random.nextInt(11) - 5;
                double y = random.nextInt(11) - 5;
                double z = random.nextInt(11) - 5;
                positions[i * 3] = x;
                positions[i * 3 + 1] = y;
                positions[i * 3 + 2] = z;
                boxes[i * 6] = x;
                boxes[i * 6 + 1] = y;
                boxes[i * 6 + 2] = z;
                boxes[i * 6 + 3] = x + 1;
                boxes[i * 6 + 4] = y + 1;
                boxes[i * 6 + 5] = z + 1;
                alive[i] = i % 7 != 0;
                spectators[i] = i % 11 == 0;
                snapshots[i] = new NativeEntityQuery.EntitySnapshot(ids[i], types[i], x, y, z,
                        x, y, z, x + 1, y + 1, z + 1, alive[i], spectators[i]);
                if (types[i] == 1 && alive[i] && !spectators[i] && i != 1
                        && x <= 4 && x + 1 >= -4 && y <= 4 && y + 1 >= -4
                        && z <= 4 && z + 1 >= -4) {
                    selected.add(i);
                }
            }
            if (sorted) {
                selected.sort(Comparator.<Integer>comparingDouble(this::distance)
                        .thenComparing(Comparator.reverseOrder()));
            }
            if (max != 0 && selected.size() > max) selected.subList(max, selected.size()).clear();
            expected = selected.stream().mapToInt(index -> ids[index]).toArray();
            int capacity = max == 0 ? count : Math.min(max, count);
            outputIds = new int[capacity];
            outputDistances = sorted ? new double[capacity * 2] : null;
            originalIds = ids.clone();
            originalTypes = types.clone();
            originalPositions = positions.clone();
            originalBoxes = boxes.clone();
            originalAlive = alive.clone();
            originalSpectators = spectators.clone();
        }

        double distance(int index) {
            double x = positions[index * 3], y = positions[index * 3 + 1], z = positions[index * 3 + 2];
            return x * x + y * y + z * z;
        }

        int[] wrapperQuery() {
            return NativeEntityQuery.query(-4, -4, -4, 4, 4, 4, snapshots, allowed,
                    PREDICATE, 1001, sorted, max, 0, 0, 0);
        }

        int nativeQuery(MethodHandle function) throws Throwable {
            return (int) function.invokeExact(-4.0, -4.0, -4.0, 4.0, 4.0, 4.0,
                    ids, types, positions, boxes, alive, spectators, allowed,
                    4, 1001, sorted, max, 0.0, 0.0, 0.0, outputIds, outputDistances);
        }

        int javaQuery() {
            return NativeEntityQuery.javaQuery(-4, -4, -4, 4, 4, 4,
                    ids, types, positions, boxes, alive, spectators, allowed,
                    PREDICATE, 1001, sorted, max, 0, 0, 0, outputIds, outputDistances);
        }

        void verify(int arm, int returned, int[] result) {
            if (returned != expected.length) throw new AssertionError("Result count mismatch");
            for (int i = 0; i < returned; ++i) {
                if (result[i] != expected[i]) throw new AssertionError("Result order mismatch");
            }
            if (arm != 0) {
                for (int i = returned; i < outputIds.length; ++i) {
                    if (outputIds[i] != SENTINEL) throw new AssertionError("Output tail overwritten");
                    if (sorted && outputDistances[i] != SENTINEL) throw new AssertionError("Distance tail overwritten");
                    if (sorted && outputDistances[outputIds.length + i] != SENTINEL) {
                        throw new AssertionError("Ordinal tail overwritten");
                    }
                }
                for (int i = 0; i < returned; ++i) {
                    if (sorted) {
                        int ordinal = expected[i] - 1000;
                        if (outputDistances[i] != distance(ordinal)
                                || outputDistances[outputIds.length + i] != ordinal) {
                            throw new AssertionError("Sorted distance/ordinal mismatch");
                        }
                    }
                }
            }
            if (!Arrays.equals(ids, originalIds) || !Arrays.equals(types, originalTypes)
                    || !Arrays.equals(positions, originalPositions) || !Arrays.equals(boxes, originalBoxes)
                    || !Arrays.equals(alive, originalAlive) || !Arrays.equals(spectators, originalSpectators)
                    || !Arrays.equals(allowed, new int[] {1})) {
                throw new AssertionError("Query modified its input");
            }
        }
    }
}
