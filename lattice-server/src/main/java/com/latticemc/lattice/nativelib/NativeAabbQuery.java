package com.latticemc.lattice.nativelib;

import java.util.Arrays;

public final class NativeAabbQuery {
    public static final int AABB_STRIDE = 6;

    private NativeAabbQuery() {}

    public static int rowLongs(int entityCount) {
        return (entityCount + 63) >>> 6;
    }

    public static boolean isAvailable() {
        LatticeNative.ensureLoaded();
        return LatticeNative.isLoaded();
    }

    public static void scan(double[] queryAabbs, int queryCount,
                            double[] entityAabbs, int entityCount,
                            long[] visibility) {
        if (visibility == null) throw new IllegalArgumentException("null visibility");
        if (queryCount < 0 || entityCount < 0) {
            throw new IllegalArgumentException("negative count");
        }
        int row = rowLongs(entityCount);
        int needed = queryCount * row;
        if (visibility.length < needed) {
            throw new IllegalArgumentException("visibility array too short");
        }

        if (LatticeNative.isLoaded()) {
            if (LatticeNative.VERIFY) {
                long[] shadow = new long[needed];
                javaScan(queryAabbs, queryCount, entityAabbs, entityCount, shadow);
                nativeScanIntersect(queryAabbs, queryCount, entityAabbs, entityCount, visibility);
                if (!Arrays.equals(shadow, 0, needed, visibility, 0, needed)) {
                    int firstDiff = -1;
                    for (int i = 0; i < needed; ++i) {
                        if (shadow[i] != visibility[i]) {
                            firstDiff = i;
                            break;
                        }
                    }
                    throw new AssertionError(
                            "lattice.verify: aabb-intersect mismatch at long " + firstDiff
                                    + " jvm=0x" + (firstDiff >= 0 ? Long.toHexString(shadow[firstDiff]) : "?")
                                    + " native=0x" + (firstDiff >= 0 ? Long.toHexString(visibility[firstDiff]) : "?"));
                }
                return;
            }
            nativeScanIntersect(queryAabbs, queryCount, entityAabbs, entityCount, visibility);
            return;
        }
        LatticeNative.logFallbackOnce("aabb_query", "native entity AABB scan unavailable");
        javaScan(queryAabbs, queryCount, entityAabbs, entityCount, visibility);
    }

    public static void javaScan(double[] queryAabbs, int queryCount,
                                double[] entityAabbs, int entityCount,
                                long[] visibility) {
        int row = rowLongs(entityCount);
        Arrays.fill(visibility, 0, queryCount * row, 0L);
        if (queryCount == 0 || entityCount == 0) return;

        for (int q = 0; q < queryCount; ++q) {
            double qMinX = queryAabbs[q * AABB_STRIDE];
            double qMinY = queryAabbs[q * AABB_STRIDE + 1];
            double qMinZ = queryAabbs[q * AABB_STRIDE + 2];
            double qMaxX = queryAabbs[q * AABB_STRIDE + 3];
            double qMaxY = queryAabbs[q * AABB_STRIDE + 4];
            double qMaxZ = queryAabbs[q * AABB_STRIDE + 5];
            int rowBase = q * row;
            for (int e = 0; e < entityCount; ++e) {
                double eMinX = entityAabbs[e * AABB_STRIDE];
                double eMinY = entityAabbs[e * AABB_STRIDE + 1];
                double eMinZ = entityAabbs[e * AABB_STRIDE + 2];
                double eMaxX = entityAabbs[e * AABB_STRIDE + 3];
                double eMaxY = entityAabbs[e * AABB_STRIDE + 4];
                double eMaxZ = entityAabbs[e * AABB_STRIDE + 5];
                boolean overlap =
                        qMinX <= eMaxX && qMaxX >= eMinX &&
                                qMinY <= eMaxY && qMaxY >= eMinY &&
                                qMinZ <= eMaxZ && qMaxZ >= eMinZ;
                if (overlap) {
                    visibility[rowBase + (e >>> 6)] |= 1L << (e & 63);
                }
            }
        }
    }

    private static native void nativeScanIntersect(
            double[] queryAabbs, int queryCount,
            double[] entityAabbs, int entityCount,
            long[] visibility);
}
