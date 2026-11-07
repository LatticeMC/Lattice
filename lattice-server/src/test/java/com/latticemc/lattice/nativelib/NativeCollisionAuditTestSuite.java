package com.latticemc.lattice.nativelib;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mockStatic;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import ca.spottedleaf.moonrise.patches.collisions.CollisionUtil;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.Shapes;
import org.junit.jupiter.api.Test;

class NativeCollisionAuditTestSuite {
    @Test
    void yzxJniPreservesLegacySpecialMovementAndArrayAliasing() throws Throwable {
        assertTrue(NativeCollisionSweep.isAvailable(), LatticeNative.failureReason());
        MethodHandle raw = CollisionAuditBenchmark.rawSweep();
        MethodHandle yzx = rawYzx();
        double[] moving = {0, 0, 0, 1, 1, 1};
        double[] obstacles = {2, -2, -2, 3, 2, 2, -2, -2, 2, 2, 2, 3};
        for (double[] desired : new double[][] {
                {0, 0, 2}, {-0.0, -0.0, -2}, {1, 0.25, 2}, {0.25, -0.5, 2},
                {Double.NaN, 0, 2}, {0.25, 0, Double.NaN},
                {0.25, 0, Double.POSITIVE_INFINITY}, {0.25, 0, Double.NEGATIVE_INFINITY}}) {
            double[] legacy = desired.clone();
            CollisionAuditBenchmark.legacyAdjustMovement(raw, moving, legacy, obstacles, 2);
            double[] actual = desired.clone();
            yzx.invokeExact(moving, actual, obstacles, 2);
            for (int axis = 0; axis < 3; ++axis) {
                assertEquals(Double.doubleToLongBits(legacy[axis]), Double.doubleToLongBits(actual[axis]));
            }
        }
        double[] legacy = moving.clone(), actual = moving.clone();
        CollisionAuditBenchmark.legacyAdjustMovement(raw, legacy, legacy, obstacles, 2);
        yzx.invokeExact(actual, actual, obstacles, 2);
        assertArrayEquals(legacy, actual);
        legacy = new double[] {0.25, 0, 2, 1, 1, 3};
        actual = legacy.clone();
        CollisionAuditBenchmark.legacyAdjustMovement(raw, moving, legacy, legacy, 1);
        yzx.invokeExact(moving, actual, actual, 1);
        assertArrayEquals(legacy, actual);
    }

    @Test
    void yzxJniValidatesBeforeChangingOutputAndPreservesTail() throws Throwable {
        assertTrue(NativeCollisionSweep.isAvailable(), LatticeNative.failureReason());
        MethodHandle yzx = rawYzx();
        double[] moving = {0, 0, 0, 1, 1, 1};
        double[] output = {0.25, 0, 2, 99};
        assertThrows(IllegalArgumentException.class,
                () -> { yzx.invokeExact(new double[5], output, new double[6], 1); });
        assertThrows(IllegalArgumentException.class,
                () -> { yzx.invokeExact(moving, output, new double[5], 1); });
        assertThrows(IllegalArgumentException.class,
                () -> { yzx.invokeExact(moving, output, (double[]) null, -1); });
        assertThrows(IllegalArgumentException.class,
                () -> { yzx.invokeExact(moving, output, new double[6], Integer.MAX_VALUE); });
        assertArrayEquals(new double[] {0.25, 0, 2, 99}, output);
        yzx.invokeExact(moving, output, (double[]) null, 0);
        assertArrayEquals(new double[] {0.25, 0, 2, 99}, output);
        yzx.invokeExact(moving, output, new double[] {-1, -1, 2, 2, 2, 3}, 1);
        assertArrayEquals(new double[] {0.25, 0, 1, 99}, output);
        assertArrayEquals(new double[] {0, 0, 0, 1, 1, 1}, moving);
    }

    private static MethodHandle rawYzx() throws ReflectiveOperationException {
        return MethodHandles.privateLookupIn(NativeCollisionSweep.class, MethodHandles.lookup()).unreflect(
                NativeCollisionSweep.class.getDeclaredMethod("nativeAdjustMovementYzx",
                        double[].class, double[].class, double[].class, int.class));
    }

    @Test
    void actualJniAndProductionRoutesMatchIndependentAxisOracle() throws Throwable {
        assertTrue(NativeCollisionSweep.isAvailable(), LatticeNative.failureReason());
        checkFixtures();
    }

    @Test
    void unavailableNativePreservesJavaSweepAndSmallProductionGate() {
        try (var nativeState = mockStatic(LatticeNative.class)) {
            nativeState.when(LatticeNative::isLoaded).thenReturn(false);
            for (int axis = 0; axis < 3; ++axis) {
                var fixture = new CollisionAuditBenchmark.Fixture(7, axis, 1);
                double[] movement = fixture.desired.clone();
                NativeCollisionSweep.adjustMovement(fixture.moving, movement, fixture.obstacles, 7);
                assertArrayEquals(fixture.expectedSweep, movement);
                assertEquals(fixture.expectedSingle, fixture.single(0));
                fixture.verifyInputs();
            }
        }
    }

    @Test
    void epsilonContactsMatchIndependentSingleAxisOracle() {
        assertTrue(NativeCollisionSweep.isAvailable(), LatticeNative.failureReason());
        AABB movingBox = new AABB(0, 0, 0, 1, 1, 1);
        double[] moving = {0, 0, 0, 1, 1, 1};
        for (int axis = 0; axis < 3; ++axis) {
            for (int sign : new int[] {-1, 1}) {
                for (double gap : new double[] {-2.0E-7, -0.5E-7, 0, 0.5E-7, 2.0E-7}) {
                    double[] min = {0.2, 0.2, 0.2}, max = {0.8, 0.8, 0.8};
                    min[axis] = sign > 0 ? 1 + gap : -1 - gap;
                    max[axis] = sign > 0 ? 2 + gap : -gap;
                    var boxes = List.of(new AABB(min[0], min[1], min[2], max[0], max[1], max[2]));
                    double expected = CollisionAuditBenchmark.oracleSingle(axis, movingBox, sign, boxes);
                    double actual = NativeCollisionSweep.calcMaxOffset(axis, moving, sign,
                            NativeCollisionSweep.flattenAabbs(boxes), 1);
                    assertEquals(Double.doubleToLongBits(expected), Double.doubleToLongBits(actual));
                }
            }
        }
    }

    @Test
    void invalidSweepInputDoesNotChangeCallerOutput() {
        double[] output = {1, 2, 3};
        assertThrows(IllegalArgumentException.class,
                () -> NativeCollisionSweep.adjustMovement(new double[5], output, new double[6], 1));
        assertArrayEquals(new double[] {1, 2, 3}, output);
        assertThrows(IllegalArgumentException.class,
                () -> NativeCollisionSweep.adjustMovement(new double[6], output, new double[5], 1));
        assertThrows(IllegalArgumentException.class,
                () -> NativeCollisionSweep.adjustMovement(new double[6], output, null, -1));
        assertArrayEquals(new double[] {1, 2, 3}, output);
        NativeCollisionSweep.adjustMovement(new double[6], output, null, 0);
        assertArrayEquals(new double[] {1, 2, 3}, output);
    }

    @Test
    void fourThreadsUseIndependentCallerArrays() throws Exception {
        assertTrue(NativeCollisionSweep.isAvailable(), LatticeNative.failureReason());
        try (var executor = Executors.newFixedThreadPool(4)) {
            List<Callable<Void>> workers = java.util.stream.IntStream.range(0, 4)
                    .mapToObj(worker -> (Callable<Void>) () -> {
                        try {
                            checkFixtures();
                        } catch (Throwable failure) {
                            throw new AssertionError(failure);
                        }
                        return null;
                    }).toList();
            for (var result : executor.invokeAll(workers)) result.get();
        }
    }

    @Test
    void nonCuboidDecompositionPreservesMultiAxisClamp() {
        assertTrue(NativeCollisionSweep.isAvailable(), LatticeNative.failureReason());
        var stairs = Shapes.or(Shapes.box(1.25, -1, -1, 2, 0.5, 2),
                Shapes.box(1.25, 0.5, 0.5, 2, 1.5, 2));
        var wall = Shapes.or(Shapes.box(-1, -1, 1.25, 2, 1.5, 1.5),
                Shapes.box(0.25, -1, 1, 0.75, 2, 2));
        var boxes = new java.util.ArrayList<AABB>(stairs.toAabbs());
        boxes.addAll(wall.toAabbs());
        for (int i = 0; i < 8; ++i) boxes.add(new AABB(20 + i, 0, 0, 21 + i, 1, 1));
        AABB movingBox = new AABB(-0.25, -0.75, 0.25, 0.75, 0.75, 1);
        double[] moving = {-0.25, -0.75, 0.25, 0.75, 0.75, 1};
        double[] obstacles = NativeCollisionSweep.flattenAabbs(boxes);
        double[] original = obstacles.clone();
        for (double[] desired : new double[][] {{1.5, 0.25, 0.5}, {0.5, 0.25, 1.5}, {0.5, -0.5, 1.5}}) {
            double[] expected = CollisionAuditBenchmark.oracleSweep(movingBox, desired, boxes);
            double[] shapeResult = desired.clone();
            AABB current = movingBox;
            int[] order = Math.abs(desired[0]) < Math.abs(desired[2]) ? new int[] {1, 2, 0} : new int[] {1, 0, 2};
            for (int axis : order) {
                for (var shape : List.of(stairs, wall)) {
                    shapeResult[axis] = switch (axis) {
                        case 0 -> CollisionUtil.collideX(shape, current, shapeResult[axis]);
                        case 1 -> CollisionUtil.collideY(shape, current, shapeResult[axis]);
                        default -> CollisionUtil.collideZ(shape, current, shapeResult[axis]);
                    };
                }
                current = current.move(axis == 0 ? shapeResult[axis] : 0, axis == 1 ? shapeResult[axis] : 0,
                        axis == 2 ? shapeResult[axis] : 0);
            }
            assertArrayEquals(shapeResult, expected);
            double[] actual = desired.clone();
            NativeCollisionSweep.adjustMovement(moving, actual, obstacles, boxes.size());
            assertArrayEquals(expected, actual);
            assertArrayEquals(original, obstacles);
            assertArrayEquals(new double[] {-0.25, -0.75, 0.25, 0.75, 0.75, 1}, moving);
        }
    }

    private static void checkFixtures() throws Throwable {
        MethodHandle raw = CollisionAuditBenchmark.rawSweep();
        for (int count : CollisionAuditBenchmark.COUNTS) {
            for (int axis = 0; axis < 3; ++axis) {
                for (int sign : new int[] {-1, 1}) {
                    var fixture = new CollisionAuditBenchmark.Fixture(count, axis, sign);
                    for (int arm = 0; arm < 3; ++arm) {
                        assertEquals(Double.doubleToLongBits(fixture.expectedSingle),
                                Double.doubleToLongBits(fixture.single(arm)));
                    }
                    for (int arm = 0; arm < 5; ++arm) {
                        fixture.sweep(arm, raw, 0);
                        fixture.verifySweep(arm);
                        assertArrayEquals(fixture.originalMoving, fixture.moving);
                        fixture.verifyInputs();
                    }
                }
            }
        }
    }
}
