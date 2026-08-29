package com.latticemc.lattice.nativelib;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Compares the Java mask DDA with the native LOS DDA on identical inputs.
 *
 * <p>This is a diagnostic benchmark rather than a performance gate: machine
 * frequency, JNI implementation details and test-runner load must not turn a
 * timing result into a correctness failure. The gated measurements use a
 * coordinate-only test heuristic; they do not change the production gate and
 * do not include Level/block-mask construction.</p>
 */
class NativeLineOfSightBenchmarkTestSuite {
    private static final int REGION_X = 160;
    private static final int REGION_Y = 32;
    private static final int REGION_Z = 160;
    private static final int RAY_COUNT = 256;
    private static final int WARMUP = 32;
    private static final int SAMPLES = 9;
    private static final int REPETITIONS = 8;
    private static volatile int benchmarkSink;

    @Test
    void batchMobApiValidatesNullInputsWithoutLoadingNative() {
        boolean threw = false;
        try {
            NativeLineOfSight.tryHasLineOfSightBatch(null, null);
        } catch (IllegalArgumentException expected) {
            threw = true;
        }
        assertTrue(threw, "null mob/list must be rejected");
    }

    @BeforeAll
    static void bootstrapRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void emptyEntityBatchReturnsAnEmptyResult() {
        Mob mob = Mockito.mock(Mob.class);

        assertArrayEquals(new Boolean[0], NativeLineOfSight.tryHasLineOfSightBatch(mob, List.of()));
    }

    @Test
    void batchRetainsCrossWorldRejectionWhenNativePathCannotHandleAnEntry() {
        Level mobLevel = Mockito.mock(Level.class);
        Level otherLevel = Mockito.mock(Level.class);
        Mob mob = Mockito.mock(Mob.class);
        Entity sameWorld = Mockito.mock(Entity.class);
        Entity otherWorld = Mockito.mock(Entity.class);
        Mockito.when(mob.level()).thenReturn(mobLevel);
        Mockito.when(sameWorld.level()).thenReturn(mobLevel);
        Mockito.when(otherWorld.level()).thenReturn(otherLevel);

        assertArrayEquals(
            new Boolean[] {null, Boolean.FALSE, null},
            NativeLineOfSight.tryHasLineOfSightBatch(mob, List.of(sameWorld, otherWorld, sameWorld)));
    }

    @Test
    void nativeBatchMatchesJavaDdaAndReportsTiming() {
        Assumptions.assumeTrue(
            NativeLineOfSight.isAvailable(),
            "native LOS library unavailable: " + LatticeNative.failureReason());

        NativeLineOfSight.SolidMask mask = fixtureMask();
        Rays rays = fixtureRays();
        boolean[] expected = javaBatch(rays, mask);
        boolean[] actual = NativeLineOfSight.hasLineOfSightBatch(
            rays.fromX, rays.fromY, rays.fromZ,
            rays.toX, rays.toY, rays.toZ,
            mask);
        assertArrayEquals(expected, actual, "native LOS differs from Java DDA");

        for (int i = 0; i < WARMUP; ++i) {
            NativeLineOfSight.hasLineOfSightBatch(
                rays.fromX, rays.fromY, rays.fromZ,
                rays.toX, rays.toY, rays.toZ,
                mask);
            javaBatch(rays, mask);
        }

        long[] nativeNanos = new long[SAMPLES];
        long[] javaNanos = new long[SAMPLES];
        for (int sample = 0; sample < SAMPLES; ++sample) {
            if ((sample & 1) == 0) {
                nativeNanos[sample] = timeNative(rays, mask);
                javaNanos[sample] = timeJava(rays, mask);
            } else {
                javaNanos[sample] = timeJava(rays, mask);
                nativeNanos[sample] = timeNative(rays, mask);
            }
        }

        System.out.printf(
            "LOS Java/native benchmark: rays=%d repetitions=%d java-p50-ns=%d java-p95-ns=%d native-p50-ns=%d native-p95-ns=%d native/java-p50=%.3f%n",
            RAY_COUNT, REPETITIONS,
            percentile(javaNanos, 0.50), percentile(javaNanos, 0.95),
            percentile(nativeNanos, 0.50), percentile(nativeNanos, 0.95),
            (double) percentile(nativeNanos, 0.50) / percentile(javaNanos, 0.50));
    }

    @Test
    void comparesScalingAndHighLatencyScenarios() {
        Assumptions.assumeTrue(
            NativeLineOfSight.isAvailable(),
            "native LOS library unavailable: " + LatticeNative.failureReason());

        int[] counts = {256, 512, 1024, 2048};
        String[] scenarios = {"early-block", "clear-long", "sparse-wall", "boundary"};
        for (int scenario = 0; scenario < scenarios.length; ++scenario) {
            for (int count : counts) {
                NativeLineOfSight.SolidMask mask = fixtureMask(scenario);
                Rays rays = fixtureRays(count, scenario);
                boolean[] expected = javaBatch(rays, mask);
                boolean[] actual = NativeLineOfSight.hasLineOfSightBatch(
                    rays.fromX, rays.fromY, rays.fromZ,
                    rays.toX, rays.toY, rays.toZ, mask);
                assertArrayEquals(expected, actual,
                    "native LOS differs from Java DDA scenario=" + scenarios[scenario] + " rays=" + count);

                int repetitions = Math.max(1, 256 / count);
                for (int i = 0; i < 8; ++i) {
                    consume(NativeLineOfSight.hasLineOfSightBatch(
                        rays.fromX, rays.fromY, rays.fromZ,
                        rays.toX, rays.toY, rays.toZ, mask));
                    consume(javaBatch(rays, mask));
                }

                long[] nativeNanos = new long[5];
                long[] javaNanos = new long[5];
                for (int sample = 0; sample < nativeNanos.length; ++sample) {
                    if ((sample & 1) == 0) {
                        nativeNanos[sample] = timeNative(rays, mask, repetitions);
                        javaNanos[sample] = timeJava(rays, mask, repetitions);
                    } else {
                        javaNanos[sample] = timeJava(rays, mask, repetitions);
                        nativeNanos[sample] = timeNative(rays, mask, repetitions);
                    }
                }

                long javaP50 = percentile(javaNanos, 0.50);
                long nativeP50 = percentile(nativeNanos, 0.50);
                System.out.printf(
                    "LOS scale: scenario=%s rays=%d repetitions=%d java-p50-ns=%d java-p95-ns=%d java-max-ns=%d native-p50-ns=%d native-p95-ns=%d native-max-ns=%d native/java-p50=%.3f%n",
                    scenarios[scenario], count, repetitions,
                    javaP50, percentile(javaNanos, 0.95), max(javaNanos),
                    nativeP50, percentile(nativeNanos, 0.95), max(nativeNanos),
                    (double) nativeP50 / javaP50);
            }
        }
    }

    @Test
    void comparesDdaLengthAndLatencyScenarios() {
        Assumptions.assumeTrue(
            NativeLineOfSight.isAvailable(),
            "native LOS library unavailable: " + LatticeNative.failureReason());

        int[] lengths = {4, 8, 12, 16, 24, 32, 48, 64, 96, 128};
        String[] scenarios = {"early-block", "mid-block", "clear-long", "section-boundary"};
        for (int scenario = 0; scenario < scenarios.length; ++scenario) {
            for (int length : lengths) {
                NativeLineOfSight.SolidMask mask = fixtureMaskForLength(scenario, length);
                Rays rays = fixtureRaysForLength(RAY_COUNT, scenario, length);
                boolean[] expected = javaBatch(rays, mask);
                boolean[] actual = NativeLineOfSight.hasLineOfSightBatch(
                    rays.fromX, rays.fromY, rays.fromZ,
                    rays.toX, rays.toY, rays.toZ, mask);
                assertArrayEquals(expected, actual,
                    "native LOS differs from Java DDA scenario=" + scenarios[scenario]
                        + " length=" + length);

                int repetitions = Math.max(1, 64 / length);
                for (int i = 0; i < 8; ++i) {
                    if ((i & 1) == 0) {
                        consumeNative(rays, mask, repetitions);
                        consumeJava(rays, mask, repetitions);
                    } else {
                        consumeJava(rays, mask, repetitions);
                        consumeNative(rays, mask, repetitions);
                    }
                }

                long[] nativeNanos = new long[7];
                long[] javaNanos = new long[7];
                for (int sample = 0; sample < nativeNanos.length; ++sample) {
                    if ((sample & 1) == 0) {
                        nativeNanos[sample] = timeNative(rays, mask, repetitions);
                        javaNanos[sample] = timeJava(rays, mask, repetitions);
                    } else {
                        javaNanos[sample] = timeJava(rays, mask, repetitions);
                        nativeNanos[sample] = timeNative(rays, mask, repetitions);
                    }
                }

                long javaP50 = percentile(javaNanos, 0.50);
                long nativeP50 = percentile(nativeNanos, 0.50);
                long javaP95 = percentile(javaNanos, 0.95);
                long nativeP95 = percentile(nativeNanos, 0.95);
                long estimatedSteps = approximateDdaSteps(rays) * repetitions;
                long measuredWork = (long) RAY_COUNT * repetitions;
                System.out.printf(
                    "LOS DDA length: scenario=%s length=%d rays=%d repetitions=%d approx-steps-per-ray=%d "
                        + "java-p50-ns=%d java-p95-ns=%d native-p50-ns=%d native-p95-ns=%d "
                        + "java-per-ray-ns=%.1f native-per-ray-ns=%.1f "
                        + "java-per-dda-step-ns=%.2f native-per-dda-step-ns=%.2f native/java-p50=%.3f%n",
                    scenarios[scenario], length, RAY_COUNT, repetitions,
                    estimatedSteps / repetitions / RAY_COUNT,
                    javaP50, javaP95, nativeP50, nativeP95,
                    (double) javaP50 / measuredWork, (double) nativeP50 / measuredWork,
                    (double) javaP50 / estimatedSteps, (double) nativeP50 / estimatedSteps,
                    (double) nativeP50 / javaP50);
            }
        }
    }

    @Test
    void decomposesCoordinateGateAndGatedBatchCost() {
        Assumptions.assumeTrue(
            NativeLineOfSight.isAvailable(),
            "native LOS library unavailable: " + LatticeNative.failureReason());

        int[] lengths = {8, 16, 32, 64, 128};
        String[] scenarios = {"early-block", "clear-long"};
        for (int scenario = 0; scenario < scenarios.length; ++scenario) {
            for (int length : lengths) {
                NativeLineOfSight.SolidMask mask = fixtureMaskForLength(scenario, length);
                Rays rays = fixtureRaysForLength(RAY_COUNT, scenario, length);
                boolean[] expected = javaBatch(rays, mask);
                boolean[] nativeResult = NativeLineOfSight.hasLineOfSightBatch(
                    rays.fromX, rays.fromY, rays.fromZ,
                    rays.toX, rays.toY, rays.toZ, mask);
                assertArrayEquals(expected, nativeResult,
                    "native LOS differs from Java DDA scenario=" + scenarios[scenario]
                        + " length=" + length);

                int gateThreshold = 24;
                int gatePasses = countGatePasses(rays, gateThreshold);
                // This fixture keeps all rays at the same length, so the gated
                // path is intentionally homogeneous and remains batch-shaped.
                assertTrue(gatePasses == 0 || gatePasses == rays.fromX.length);

                for (int i = 0; i < WARMUP; ++i) {
                    consumeGateOnly(rays, gateThreshold);
                    consume(javaBatch(rays, mask));
                    consume(NativeLineOfSight.hasLineOfSightBatch(
                        rays.fromX, rays.fromY, rays.fromZ,
                        rays.toX, rays.toY, rays.toZ, mask));
                    consume(gatedBatch(rays, mask, gateThreshold));
                }

                long[] gateNanos = new long[SAMPLES];
                long[] javaNanos = new long[SAMPLES];
                long[] nativeNanos = new long[SAMPLES];
                long[] gatedNanos = new long[SAMPLES];
                for (int sample = 0; sample < SAMPLES; ++sample) {
                    if ((sample & 1) == 0) {
                        gateNanos[sample] = timeGateOnly(rays, gateThreshold);
                        javaNanos[sample] = timeJava(rays, mask, REPETITIONS);
                        nativeNanos[sample] = timeNative(rays, mask, REPETITIONS);
                        gatedNanos[sample] = timeGated(rays, mask, gateThreshold, REPETITIONS);
                    } else {
                        gatedNanos[sample] = timeGated(rays, mask, gateThreshold, REPETITIONS);
                        nativeNanos[sample] = timeNative(rays, mask, REPETITIONS);
                        javaNanos[sample] = timeJava(rays, mask, REPETITIONS);
                        gateNanos[sample] = timeGateOnly(rays, gateThreshold);
                    }
                }

                long gateP50 = percentile(gateNanos, 0.50);
                long gateP95 = percentile(gateNanos, 0.95);
                long javaP50 = percentile(javaNanos, 0.50);
                long javaP95 = percentile(javaNanos, 0.95);
                long nativeP50 = percentile(nativeNanos, 0.50);
                long nativeP95 = percentile(nativeNanos, 0.95);
                long gatedP50 = percentile(gatedNanos, 0.50);
                long gatedP95 = percentile(gatedNanos, 0.95);
                double work = (double)RAY_COUNT * REPETITIONS;
                System.out.printf(
                    "LOS gate breakdown: scenario=%s length=%d gate-threshold=%d gate-pass=%d/%d "
                        + "gate-p50-ns=%d gate-p95-ns=%d gate-per-ray-ns=%.2f "
                        + "java-p50-ns=%d java-p95-ns=%d java-per-ray-ns=%.2f "
                        + "native-p50-ns=%d native-p95-ns=%d native-per-ray-ns=%.2f "
                        + "gated-p50-ns=%d gated-p95-ns=%d gated-per-ray-ns=%.2f "
                        + "gated/java-p50=%.3f gated/native-p50=%.3f%n",
                    scenarios[scenario], length, gateThreshold, gatePasses, rays.fromX.length,
                    gateP50, gateP95, gateP50 / ((double)rays.fromX.length * REPETITIONS),
                    javaP50, javaP95, javaP50 / work,
                    nativeP50, nativeP95, nativeP50 / work,
                    gatedP50, gatedP95, gatedP50 / work,
                    (double)gatedP50 / javaP50, (double)gatedP50 / nativeP50);
            }
        }
    }

    private static long timeNative(Rays rays, NativeLineOfSight.SolidMask mask) {
        long start = System.nanoTime();
        boolean[] result = null;
        for (int i = 0; i < REPETITIONS; ++i) {
            result = NativeLineOfSight.hasLineOfSightBatch(
                rays.fromX, rays.fromY, rays.fromZ,
                rays.toX, rays.toY, rays.toZ,
                mask);
        }
        assertTrue(result != null && result.length == rays.fromX.length);
        return System.nanoTime() - start;
    }

    private static long timeJava(Rays rays, NativeLineOfSight.SolidMask mask) {
        long start = System.nanoTime();
        boolean[] result = null;
        for (int i = 0; i < REPETITIONS; ++i) {
            result = javaBatch(rays, mask);
        }
        assertTrue(result != null && result.length == RAY_COUNT);
        return System.nanoTime() - start;
    }

    private static long timeNative(Rays rays, NativeLineOfSight.SolidMask mask, int repetitions) {
        long start = System.nanoTime();
        for (int i = 0; i < repetitions; ++i) {
            consume(NativeLineOfSight.hasLineOfSightBatch(
                rays.fromX, rays.fromY, rays.fromZ,
                rays.toX, rays.toY, rays.toZ, mask));
        }
        return System.nanoTime() - start;
    }

    private static long timeJava(Rays rays, NativeLineOfSight.SolidMask mask, int repetitions) {
        long start = System.nanoTime();
        for (int i = 0; i < repetitions; ++i) {
            consume(javaBatch(rays, mask));
        }
        return System.nanoTime() - start;
    }

    private static long timeGateOnly(Rays rays, int threshold) {
        long start = System.nanoTime();
        int passes = 0;
        for (int repetition = 0; repetition < REPETITIONS; ++repetition) {
            passes += countGatePasses(rays, threshold);
        }
        benchmarkSink = passes;
        return System.nanoTime() - start;
    }

    private static long timeGated(Rays rays, NativeLineOfSight.SolidMask mask,
                                  int threshold, int repetitions) {
        long start = System.nanoTime();
        for (int repetition = 0; repetition < repetitions; ++repetition) {
            consume(gatedBatch(rays, mask, threshold));
        }
        return System.nanoTime() - start;
    }

    private static void consumeNative(Rays rays, NativeLineOfSight.SolidMask mask, int repetitions) {
        for (int i = 0; i < repetitions; ++i) {
            consume(NativeLineOfSight.hasLineOfSightBatch(
                rays.fromX, rays.fromY, rays.fromZ,
                rays.toX, rays.toY, rays.toZ, mask));
        }
    }

    private static void consumeJava(Rays rays, NativeLineOfSight.SolidMask mask, int repetitions) {
        for (int i = 0; i < repetitions; ++i) consume(javaBatch(rays, mask));
    }

    private static void consumeGateOnly(Rays rays, int threshold) {
        benchmarkSink = countGatePasses(rays, threshold);
    }

    private static boolean[] gatedBatch(Rays rays, NativeLineOfSight.SolidMask mask, int threshold) {
        int passes = countGatePasses(rays, threshold);
        if (passes == rays.fromX.length) {
            return NativeLineOfSight.hasLineOfSightBatch(
                rays.fromX, rays.fromY, rays.fromZ,
                rays.toX, rays.toY, rays.toZ, mask);
        }
        return javaBatch(rays, mask);
    }

    private static int countGatePasses(Rays rays, int threshold) {
        int passes = 0;
        for (int i = 0; i < rays.fromX.length; ++i) {
            if (shouldUseNativeGate(
                rays.fromX[i], rays.fromY[i], rays.fromZ[i],
                rays.toX[i], rays.toY[i], rays.toZ[i], threshold)) {
                ++passes;
            }
        }
        return passes;
    }

    /** Coordinate-only diagnostic heuristic; deliberately not production policy. */
    private static boolean shouldUseNativeGate(double fromX, double fromY, double fromZ,
                                               double toX, double toY, double toZ,
                                               int threshold) {
        double dx = Math.abs(toX - fromX);
        double dy = Math.abs(toY - fromY);
        double dz = Math.abs(toZ - fromZ);
        double maxAxis = Math.max(dx, Math.max(dy, dz));
        double distanceSq = dx * dx + dy * dy + dz * dz;
        return maxAxis >= threshold && distanceSq >= (double)threshold * threshold;
    }

    private static boolean[] javaBatch(Rays rays, NativeLineOfSight.SolidMask mask) {
        boolean[] result = new boolean[rays.fromX.length];
        for (int i = 0; i < rays.fromX.length; ++i) {
            result[i] = javaDda(
                rays.fromX[i], rays.fromY[i], rays.fromZ[i],
                rays.toX[i], rays.toY[i], rays.toZ[i], mask);
        }
        return result;
    }

    private static NativeLineOfSight.SolidMask fixtureMask() {
        byte[] data = new byte[REGION_X * REGION_Y * REGION_Z];
        for (int y = 0; y < REGION_Y; ++y) {
            for (int z = 0; z < REGION_Z; ++z) {
                for (int x = 0; x < REGION_X; ++x) {
                    // Deterministic sparse walls exercise both early blockers and full rays.
                    if ((x == 31 && (z & 3) != 0) || (z == 57 && (x & 7) < 5)
                        || (y == 12 && ((x * 13 + z * 7) & 31) == 0)) {
                        data[index(x, y, z)] = 1;
                    }
                }
            }
        }
        return new NativeLineOfSight.SolidMask(data, 0, 0, 0, REGION_X, REGION_Y, REGION_Z);
    }

    private static NativeLineOfSight.SolidMask fixtureMask(int scenario) {
        byte[] data = new byte[REGION_X * REGION_Y * REGION_Z];
        for (int y = 0; y < REGION_Y; ++y) {
            for (int z = 0; z < REGION_Z; ++z) {
                for (int x = 0; x < REGION_X; ++x) {
                    boolean solid = switch (scenario) {
                        case 0 -> x == 6 && (z & 1) == 0;
                        case 1 -> false;
                        case 2 -> (x == 31 && (z & 3) != 0)
                            || (z == 57 && (x & 7) < 5)
                            || (y == 12 && ((x * 13 + z * 7) & 31) == 0);
                        case 3 -> x == 1 || x == REGION_X - 2 || z == 1 || z == REGION_Z - 2;
                        default -> false;
                    };
                    if (solid) data[(y * REGION_Z + z) * REGION_X + x] = 1;
                }
            }
        }
        return new NativeLineOfSight.SolidMask(data, 0, 0, 0, REGION_X, REGION_Y, REGION_Z);
    }

    private static Rays fixtureRays() {
        double[] fromX = new double[RAY_COUNT];
        double[] fromY = new double[RAY_COUNT];
        double[] fromZ = new double[RAY_COUNT];
        double[] toX = new double[RAY_COUNT];
        double[] toY = new double[RAY_COUNT];
        double[] toZ = new double[RAY_COUNT];
        for (int i = 0; i < RAY_COUNT; ++i) {
            fromX[i] = 1.5 + (i & 7);
            fromY[i] = 1.5 + ((i * 5) & 15);
            fromZ[i] = 1.5 + ((i * 3) & 7);
            toX[i] = 88.5 - ((i * 11) & 15);
            toY[i] = 2.5 + ((i * 7) & 15);
            toZ[i] = 88.5 - ((i * 5) & 15);
        }
        return new Rays(fromX, fromY, fromZ, toX, toY, toZ);
    }

    private static Rays fixtureRays(int count, int scenario) {
        double[] fromX = new double[count];
        double[] fromY = new double[count];
        double[] fromZ = new double[count];
        double[] toX = new double[count];
        double[] toY = new double[count];
        double[] toZ = new double[count];
        for (int i = 0; i < count; ++i) {
            if (scenario == 0) {
                fromX[i] = 1.5;
                fromY[i] = 1.5 + ((i * 5) & 15);
                fromZ[i] = 2.5 + (i & 15);
                toX[i] = 88.5;
                toY[i] = fromY[i];
                toZ[i] = fromZ[i];
            } else if (scenario == 3) {
                fromX[i] = 0.01 + ((i & 3) * 0.01);
                fromY[i] = 0.01 + ((i * 5) & 15);
                fromZ[i] = 0.01 + ((i & 7) * 0.01);
                toX[i] = 95.99 - ((i & 3) * 0.01);
                toY[i] = 31.99 - ((i * 3) & 15);
                toZ[i] = 95.99 - ((i & 7) * 0.01);
            } else {
                fromX[i] = 1.5 + (i & 7);
                fromY[i] = 1.5 + ((i * 5) & 15);
                fromZ[i] = 1.5 + ((i * 3) & 7);
                toX[i] = 88.5 - ((i * 11) & 15);
                toY[i] = 2.5 + ((i * 7) & 15);
                toZ[i] = 88.5 - ((i * 5) & 15);
            }
        }
        return new Rays(fromX, fromY, fromZ, toX, toY, toZ);
    }

    private static NativeLineOfSight.SolidMask fixtureMaskForLength(int scenario, int length) {
        byte[] data = new byte[REGION_X * REGION_Y * REGION_Z];
        int wallX = Math.max(1, length / 2);
        for (int y = 0; y < REGION_Y; ++y) {
            for (int z = 0; z < REGION_Z; ++z) {
                if (scenario == 0) data[index(0, y, z)] = 1;
                if (scenario == 1) data[index(wallX, y, z)] = 1;
                if (scenario == 3 && (z & 1) == 0) data[index(32, y, z)] = 1;
            }
        }
        return new NativeLineOfSight.SolidMask(data, 0, 0, 0, REGION_X, REGION_Y, REGION_Z);
    }

    private static Rays fixtureRaysForLength(int count, int scenario, int length) {
        double[] fromX = new double[count];
        double[] fromY = new double[count];
        double[] fromZ = new double[count];
        double[] toX = new double[count];
        double[] toY = new double[count];
        double[] toZ = new double[count];
        for (int i = 0; i < count; ++i) {
            fromY[i] = 8.5 + (i & 3);
            if (scenario == 3) {
                fromX[i] = 15.5 + ((i & 1) * 0.1);
                fromZ[i] = 15.5 + ((i & 3) * 0.1);
                toX[i] = fromX[i] + length;
                toY[i] = fromY[i];
                toZ[i] = fromZ[i] + (length * 0.5);
            } else {
                fromX[i] = 0.5;
                fromZ[i] = 2.5 + (i & 15);
                toX[i] = fromX[i] + length;
                toY[i] = fromY[i];
                toZ[i] = fromZ[i];
            }
        }
        return new Rays(fromX, fromY, fromZ, toX, toY, toZ);
    }

    private static long approximateDdaSteps(Rays rays) {
        long total = 0L;
        for (int i = 0; i < rays.fromX.length; ++i) {
            total += 1L
                + Math.abs(floor(rays.toX[i]) - floor(rays.fromX[i]))
                + Math.abs(floor(rays.toY[i]) - floor(rays.fromY[i]))
                + Math.abs(floor(rays.toZ[i]) - floor(rays.fromZ[i]));
        }
        return total;
    }

    private static void consume(boolean[] values) {
        int checksum = 0;
        for (boolean value : values) checksum = checksum * 31 + (value ? 1 : 0);
        benchmarkSink = checksum;
    }

    private static boolean javaDda(double fromX, double fromY, double fromZ,
                                   double toX, double toY, double toZ,
                                   NativeLineOfSight.SolidMask mask) {
        double adjX = 1.0E-7D * (fromX - toX);
        double adjY = 1.0E-7D * (fromY - toY);
        double adjZ = 1.0E-7D * (fromZ - toZ);
        if (adjX == 0.0D && adjY == 0.0D && adjZ == 0.0D) return true;
        double fromXAdj = fromX + adjX;
        double fromYAdj = fromY + adjY;
        double fromZAdj = fromZ + adjZ;
        double toXAdj = toX - adjX;
        double toYAdj = toY - adjY;
        double toZAdj = toZ - adjZ;
        int currX = floor(fromXAdj);
        int currY = floor(fromYAdj);
        int currZ = floor(fromZAdj);
        double diffX = toXAdj - fromXAdj;
        double diffY = toYAdj - fromYAdj;
        double diffZ = toZAdj - fromZAdj;
        int dx = signum(diffX);
        int dy = signum(diffY);
        int dz = signum(diffZ);
        double normalizedDiffX = diffX == 0.0D ? Double.MAX_VALUE : dx / diffX;
        double normalizedDiffY = diffY == 0.0D ? Double.MAX_VALUE : dy / diffY;
        double normalizedDiffZ = diffZ == 0.0D ? Double.MAX_VALUE : dz / diffZ;
        double normalizedCurrX = normalizedDiffX * (diffX > 0.0D ? 1.0D - frac(fromXAdj) : frac(fromXAdj));
        double normalizedCurrY = normalizedDiffY * (diffY > 0.0D ? 1.0D - frac(fromYAdj) : frac(fromYAdj));
        double normalizedCurrZ = normalizedDiffZ * (diffZ > 0.0D ? 1.0D - frac(fromZAdj) : frac(fromZAdj));
        for (;;) {
            if (currX < mask.regionMinX() || currY < mask.regionMinY() || currZ < mask.regionMinZ()
                || currX >= mask.regionMinX() + mask.regionSizeX()
                || currY >= mask.regionMinY() + mask.regionSizeY()
                || currZ >= mask.regionMinZ() + mask.regionSizeZ()) return false;
            if (mask.data()[index(currX - mask.regionMinX(), currY - mask.regionMinY(), currZ - mask.regionMinZ())] != 0) {
                return false;
            }
            if (normalizedCurrX > 1.0D && normalizedCurrY > 1.0D && normalizedCurrZ > 1.0D) return true;
            if (normalizedCurrX < normalizedCurrY) {
                if (normalizedCurrX < normalizedCurrZ) {
                    currX += dx;
                    normalizedCurrX += normalizedDiffX;
                } else {
                    currZ += dz;
                    normalizedCurrZ += normalizedDiffZ;
                }
            } else if (normalizedCurrY < normalizedCurrZ) {
                currY += dy;
                normalizedCurrY += normalizedDiffY;
            } else {
                currZ += dz;
                normalizedCurrZ += normalizedDiffZ;
            }
        }
    }

    private static int index(int x, int y, int z) {
        return (y * REGION_Z + z) * REGION_X + x;
    }

    private static int floor(double value) {
        return (int) Math.floor(value);
    }

    private static double frac(double value) {
        return value - Math.floor(value);
    }

    private static int signum(double value) {
        return (value > 0.0D ? 1 : 0) - (value < 0.0D ? 1 : 0);
    }

    private static long percentile(long[] values, double quantile) {
        long[] sorted = values.clone();
        Arrays.sort(sorted);
        int index = (int) Math.ceil(quantile * sorted.length) - 1;
        return sorted[Math.max(0, index)];
    }

    private static long max(long[] values) {
        long result = 0L;
        for (long value : values) result = Math.max(result, value);
        return result;
    }

    private record Rays(double[] fromX, double[] fromY, double[] fromZ,
                        double[] toX, double[] toY, double[] toZ) {}
}
