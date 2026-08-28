package com.latticemc.lattice.nativelib;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Compares the Java mask DDA with the native LOS DDA on identical inputs.
 *
 * <p>This is a diagnostic benchmark rather than a performance gate: machine
 * frequency, JNI implementation details and test-runner load must not turn a
 * timing result into a correctness failure.</p>
 */
class NativeLineOfSightBenchmarkTestSuite {
    private static final int REGION_X = 96;
    private static final int REGION_Y = 32;
    private static final int REGION_Z = 96;
    private static final int RAY_COUNT = 256;
    private static final int WARMUP = 32;
    private static final int SAMPLES = 9;
    private static final int REPETITIONS = 8;

    @BeforeAll
    static void bootstrapRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
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

    private static long timeNative(Rays rays, NativeLineOfSight.SolidMask mask) {
        long start = System.nanoTime();
        boolean[] result = null;
        for (int i = 0; i < REPETITIONS; ++i) {
            result = NativeLineOfSight.hasLineOfSightBatch(
                rays.fromX, rays.fromY, rays.fromZ,
                rays.toX, rays.toY, rays.toZ,
                mask);
        }
        assertTrue(result != null && result.length == RAY_COUNT);
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

    private static boolean[] javaBatch(Rays rays, NativeLineOfSight.SolidMask mask) {
        boolean[] result = new boolean[RAY_COUNT];
        for (int i = 0; i < RAY_COUNT; ++i) {
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

    private record Rays(double[] fromX, double[] fromY, double[] fromZ,
                        double[] toX, double[] toY, double[] toZ) {}
}
