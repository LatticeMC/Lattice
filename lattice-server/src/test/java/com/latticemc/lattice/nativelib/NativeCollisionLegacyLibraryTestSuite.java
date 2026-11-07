package com.latticemc.lattice.nativelib;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.invoke.MethodHandles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

@EnabledIfSystemProperty(named = "collisionAudit.legacyLibrary", matches = "true")
class NativeCollisionLegacyLibraryTestSuite {
    @Test
    void missingYzxEntryRetainsOriginalNativeSweep() throws Throwable {
        assertTrue(NativeCollisionSweep.isAvailable(), LatticeNative.failureReason());
        var yzx = MethodHandles.privateLookupIn(NativeCollisionSweep.class, MethodHandles.lookup()).unreflect(
                NativeCollisionSweep.class.getDeclaredMethod("nativeAdjustMovementYzx",
                        double[].class, double[].class, double[].class, int.class));
        double[] moving = {0, 0, 0, 1, 1, 1};
        double[] output = {0.25, 0, 2};
        double[] obstacle = {-1, -1, 2, 2, 2, 3};
        assertThrows(UnsatisfiedLinkError.class,
                () -> { yzx.invokeExact(moving, output, obstacle, 1); });
        assertArrayEquals(new double[] {0.25, 0, 2}, output);
        for (int round = 0; round < 3; ++round) {
            for (int count : CollisionAuditBenchmark.COUNTS) {
                for (int axis = 0; axis < 3; ++axis) {
                    for (int sign : new int[] {-1, 1}) {
                        var fixture = new CollisionAuditBenchmark.Fixture(count, axis, sign);
                        double[] actual = fixture.desired.clone();
                        NativeCollisionSweep.adjustMovement(fixture.moving, actual, fixture.obstacles, count);
                        assertArrayEquals(fixture.expectedSweep, actual);
                        fixture.verifyInputs();
                    }
                }
            }
        }
        var raw = CollisionAuditBenchmark.rawSweep();
        for (double[] desired : new double[][] {
                {-0.0, -0.0, -2}, {Double.NaN, 0, 2},
                {0.25, 0, Double.POSITIVE_INFINITY}, {0.25, 0, Double.NEGATIVE_INFINITY}}) {
            double[] expected = desired.clone(), actual = desired.clone();
            CollisionAuditBenchmark.legacyAdjustMovement(raw, moving, expected, obstacle, 1);
            NativeCollisionSweep.adjustMovement(moving, actual, obstacle, 1);
            assertArrayEquals(expected, actual);
        }
    }
}
