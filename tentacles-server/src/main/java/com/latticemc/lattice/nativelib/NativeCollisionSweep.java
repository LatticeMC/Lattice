package com.latticemc.lattice.nativelib;

import java.util.Arrays;

public final class NativeCollisionSweep {
    public static final int AABB_STRIDE = 6;
    public static final int MOVEMENT_STRIDE = 3;
    private static final int[] AXIS_ORDER_YXZ = {1, 0, 2};
    private static final int[] AXIS_ORDER_YZX = {1, 2, 0};

    private NativeCollisionSweep() {}

    public static boolean isAvailable() {
        LatticeNative.ensureLoaded();
        return LatticeNative.isLoaded();
    }

    public static void adjustMovement(double[] moving,
                                      double[] movement,
                                      double[] obstacles,
                                      int obstacleCount) {
        validate(moving, movement, obstacles, obstacleCount);

        if (LatticeNative.isLoaded()) {
            if (LatticeNative.VERIFY) {
                double[] shadow = movement.clone();
                javaAdjustMovementVanilla(moving, shadow, obstacles, obstacleCount);
                nativeAdjustMovementVanilla(moving, movement, obstacles, obstacleCount);
                if (!Arrays.equals(shadow, movement)) {
                    throw new AssertionError("lattice.verify: collision sweep mismatch"
                            + " jvm=" + Arrays.toString(shadow)
                            + " native=" + Arrays.toString(movement));
                }
                return;
            }
            nativeAdjustMovementVanilla(moving, movement, obstacles, obstacleCount);
            return;
        }

        LatticeNative.logFallbackOnce("collision_sweep", "native collision sweep unavailable");
        javaAdjustMovementVanilla(moving, movement, obstacles, obstacleCount);
    }

    private static void nativeAdjustMovementVanilla(double[] moving,
                                                    double[] movement,
                                                    double[] obstacles,
                                                    int obstacleCount) {
        if (usesNativeAxisOrder(movement)) {
            nativeAdjustMovement(moving, movement, obstacles, obstacleCount);
            return;
        }

        double[] remappedMoving = remapAabbXzy(moving);
        double[] remappedMovement = {movement[2], movement[1], movement[0]};
        double[] remappedObstacles = remapAabbsXzy(obstacles, obstacleCount);
        nativeAdjustMovement(remappedMoving, remappedMovement, remappedObstacles, obstacleCount);
        movement[0] = remappedMovement[2];
        movement[1] = remappedMovement[1];
        movement[2] = remappedMovement[0];
    }

    private static boolean usesNativeAxisOrder(double[] movement) {
        return Math.abs(movement[0]) >= Math.abs(movement[2]);
    }

    public static void javaAdjustMovementVanilla(double[] moving,
                                                 double[] movement,
                                                 double[] obstacles,
                                                 int obstacleCount) {
        javaAdjustMovement(moving, movement, obstacles, obstacleCount,
                usesNativeAxisOrder(movement) ? AXIS_ORDER_YXZ : AXIS_ORDER_YZX);
    }

    public static void javaAdjustMovement(double[] moving,
                                          double[] movement,
                                          double[] obstacles,
                                          int obstacleCount) {
        javaAdjustMovement(moving, movement, obstacles, obstacleCount, AXIS_ORDER_YXZ);
    }

    private static void javaAdjustMovement(double[] moving,
                                           double[] movement,
                                           double[] obstacles,
                                           int obstacleCount,
                                           int[] axisOrder) {
        if (obstacleCount == 0) return;

        double[] current = moving.clone();
        for (int axis : axisOrder) {
            double adjusted = movement[axis];
            for (int i = 0; i < obstacleCount; ++i) {
                adjusted = clampAxis(axis, current, adjusted, obstacles, i * AABB_STRIDE);
                if (adjusted == 0.0D) break;
            }
            movement[axis] = adjusted;
            if (adjusted != 0.0D) {
                current[axis] += adjusted;
                current[axis + 3] += adjusted;
            }
        }
    }

    private static double clampAxis(int axis,
                                    double[] moving,
                                    double desired,
                                    double[] obstacles,
                                    int obstacleBase) {
        if (desired == 0.0D) return 0.0D;

        int axis1 = (axis + 1) % 3;
        int axis2 = (axis + 2) % 3;
        if (moving[axis1 + 3] <= obstacles[obstacleBase + axis1]
                || moving[axis1] >= obstacles[obstacleBase + axis1 + 3]) {
            return desired;
        }
        if (moving[axis2 + 3] <= obstacles[obstacleBase + axis2]
                || moving[axis2] >= obstacles[obstacleBase + axis2 + 3]) {
            return desired;
        }

        if (desired > 0.0D && moving[axis + 3] <= obstacles[obstacleBase + axis]) {
            double gap = obstacles[obstacleBase + axis] - moving[axis + 3];
            if (gap < desired) return gap;
        } else if (desired < 0.0D && moving[axis] >= obstacles[obstacleBase + axis + 3]) {
            double gap = obstacles[obstacleBase + axis + 3] - moving[axis];
            if (gap > desired) return gap;
        }
        return desired;
    }

    private static double[] remapAabbXzy(double[] aabb) {
        return new double[] {
                aabb[2], aabb[1], aabb[0],
                aabb[5], aabb[4], aabb[3],
        };
    }

    private static double[] remapAabbsXzy(double[] aabbs, int obstacleCount) {
        double[] remapped = new double[obstacleCount * AABB_STRIDE];
        for (int i = 0; i < obstacleCount; ++i) {
            int base = i * AABB_STRIDE;
            remapped[base] = aabbs[base + 2];
            remapped[base + 1] = aabbs[base + 1];
            remapped[base + 2] = aabbs[base];
            remapped[base + 3] = aabbs[base + 5];
            remapped[base + 4] = aabbs[base + 4];
            remapped[base + 5] = aabbs[base + 3];
        }
        return remapped;
    }

    private static void validate(double[] moving,
                                 double[] movement,
                                 double[] obstacles,
                                 int obstacleCount) {
        if (moving == null || movement == null) {
            throw new IllegalArgumentException("null moving/movement array");
        }
        if (moving.length < AABB_STRIDE || movement.length < MOVEMENT_STRIDE) {
            throw new IllegalArgumentException("moving/movement array too short");
        }
        if (obstacleCount < 0) {
            throw new IllegalArgumentException("negative obstacle count");
        }
        if (obstacleCount > 0 && (obstacles == null || obstacles.length < obstacleCount * AABB_STRIDE)) {
            throw new IllegalArgumentException("obstacles array too short");
        }
    }

    private static native void nativeAdjustMovement(
            double[] moving,
            double[] movement,
            double[] obstacles,
            int obstacleCount);
}
