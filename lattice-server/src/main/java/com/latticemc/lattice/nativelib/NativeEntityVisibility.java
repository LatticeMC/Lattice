package com.latticemc.lattice.nativelib;

import java.util.Arrays;

public final class NativeEntityVisibility {
    private NativeEntityVisibility() {}

    public static int rowLongs(int playerCount) {
        return (playerCount + 63) >>> 6;
    }

    public static boolean isAvailable() {
        LatticeNative.ensureLoaded();
        return LatticeNative.isLoaded();
    }

    public static void scan(double[] entityXyz, double[] entityRangeSq, int entityCount,
                            double[] playerXyz, int playerCount,
                            long[] visibility) {
        if (visibility == null) throw new IllegalArgumentException("null visibility");
        if (entityCount < 0 || playerCount < 0) {
            throw new IllegalArgumentException("negative count");
        }
        int row = rowLongs(playerCount);
        int needed = entityCount * row;
        if (visibility.length < needed) {
            throw new IllegalArgumentException("visibility array too short");
        }

        if (LatticeNative.isLoaded()) {
            if (LatticeNative.VERIFY) {
                long[] shadow = new long[needed];
                javaScan(entityXyz, entityRangeSq, entityCount, playerXyz, playerCount, shadow);
                nativeScanVisibility(entityXyz, entityRangeSq, entityCount, playerXyz, playerCount, visibility);
                if (!Arrays.equals(shadow, 0, needed, visibility, 0, needed)) {
                    int firstDiff = -1;
                    for (int i = 0; i < needed; ++i) {
                        if (shadow[i] != visibility[i]) {
                            firstDiff = i;
                            break;
                        }
                    }
                    throw new AssertionError(
                            "lattice.verify: entity-visibility mismatch at long " + firstDiff
                                    + " jvm=0x" + (firstDiff >= 0 ? Long.toHexString(shadow[firstDiff]) : "?")
                                    + " native=0x" + (firstDiff >= 0 ? Long.toHexString(visibility[firstDiff]) : "?"));
                }
                return;
            }
            nativeScanVisibility(entityXyz, entityRangeSq, entityCount, playerXyz, playerCount, visibility);
            return;
        }
        LatticeNative.logFallbackOnce("entity_visibility", "native tracked-player visibility scan unavailable");
        javaScan(entityXyz, entityRangeSq, entityCount, playerXyz, playerCount, visibility);
    }

    public static void javaScan(double[] entityXyz, double[] entityRangeSq, int entityCount,
                                double[] playerXyz, int playerCount,
                                long[] visibility) {
        int row = rowLongs(playerCount);
        Arrays.fill(visibility, 0, entityCount * row, 0L);
        if (entityCount == 0 || playerCount == 0) return;

        for (int i = 0; i < entityCount; ++i) {
            double ex = entityXyz[i * 3];
            double ey = entityXyz[i * 3 + 1];
            double ez = entityXyz[i * 3 + 2];
            double r2 = entityRangeSq[i];
            int rowBase = i * row;
            for (int j = 0; j < playerCount; ++j) {
                double dx = playerXyz[j * 3] - ex;
                double dy = playerXyz[j * 3 + 1] - ey;
                double dz = playerXyz[j * 3 + 2] - ez;
                double d2 = dx * dx + dy * dy + dz * dz;
                if (d2 <= r2) {
                    visibility[rowBase + (j >>> 6)] |= 1L << (j & 63);
                }
            }
        }
    }

    private static native void nativeScanVisibility(
            double[] entityXyz, double[] entityRangeSq, int entityCount,
            double[] playerXyz, int playerCount,
            long[] visibility);
}
