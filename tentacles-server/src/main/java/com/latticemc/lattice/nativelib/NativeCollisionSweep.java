package com.latticemc.lattice.nativelib;

import java.util.Arrays;

public final class NativeCollisionSweep {
    public static final int AABB_STRIDE = 6;
    public static final int MOVEMENT_STRIDE = 3;

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
                javaAdjustMovement(moving, shadow, obstacles, obstacleCount);
                nativeAdjustMovement(moving, movement, obstacles, obstacleCount);
                if (!Arrays.equals(shadow, movement)) {
                    throw new AssertionError("lattice.verify: collision sweep mismatch"
                            + " jvm=" + Arrays.toString(shadow)
                            + " native=" + Arrays.toString(movement));
                }
                return;
            }
            nativeAdjustMovement(moving, movement, obstacles, obstacleCount);
            return;
        }

        LatticeNative.logFallbackOnce("collision_sweep", "native collision sweep unavailable");
        javaAdjustMovement(moving, movement, obstacles, obstacleCount);
    }

    public static boolean canUseNativeAxisOrder(double[] movement) {
        if (movement == null || movement.length < MOVEMENT_STRIDE) {
            return false;
        }
        return Math.abs(movement[0]) >= Math.abs(movement[2]);
    }

    public static void javaAdjustMovement(double[] moving,
                                          double[] movement,
                                          double[] obstacles,
                                          int obstacleCount) {
        if (obstacleCount == 0) return;

        double[] current = moving.clone();
        int[] axisOrder = {1, 0, 2};
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
