package com.latticemc.lattice.nativelib;

import java.util.Arrays;

public final class NativeAabbQuery {
    public static final int AABB_STRIDE = 6;
    // Dense Moonrise sections keep a live, strided SoA cache updated from
    // Entity#setBoundingBox, so queries do not materialise a fresh snapshot.
    private static final boolean ENABLED = Boolean.parseBoolean(
        System.getProperty("lattice.nativeAabbQuery", "true")
    );

    private NativeAabbQuery() {}

    public static int rowLongs(int entityCount) {
        return (entityCount + 63) >>> 6;
    }

    public static boolean isAvailable() {
        if (!ENABLED) return false;
        LatticeNative.ensureLoaded();
        return LatticeNative.isLoaded();
    }

    public static void scan(double[] queryAabbs, int queryCount,
                            double[] entityAabbs, int entityCount,
                            long[] visibility) {
        scanInternal(queryAabbs, queryCount, entityAabbs, entityCount, entityCount, visibility, false);
    }

    /**
     * Same result as {@link #scan}, with entity bounds in six contiguous
     * coordinate planes: minX[N], minY[N], minZ[N], maxX[N], maxY[N], maxZ[N].
     */
    public static void scanSoa(double[] queryAabbs, int queryCount,
                               double[] entityAabbs, int entityCount,
                               long[] visibility) {
        scanInternal(queryAabbs, queryCount, entityAabbs, entityCount, entityCount, visibility, true);
    }

    /**
     * SoA scan where each coordinate plane has {@code entityStride} elements.
     * The first {@code entityCount} entries of every plane are live.
     */
    public static void scanSoaStrided(double[] queryAabbs, int queryCount,
                                      double[] entityAabbs, int entityCount, int entityStride,
                                      long[] visibility) {
        scanInternal(queryAabbs, queryCount, entityAabbs, entityCount, entityStride, visibility, true);
    }

    private static void scanInternal(double[] queryAabbs, int queryCount,
                                     double[] entityAabbs, int entityCount, int entityStride,
                                     long[] visibility, boolean entitiesSoa) {
        if (visibility == null) throw new IllegalArgumentException("null visibility");
        if (queryCount < 0 || entityCount < 0 || entityStride < entityCount) {
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
                if (entitiesSoa) {
                    javaScanSoa(queryAabbs, queryCount, entityAabbs, entityCount, entityStride, shadow);
                } else {
                    javaScan(queryAabbs, queryCount, entityAabbs, entityCount, shadow);
                }
                nativeScanIntersect(queryAabbs, queryCount, entityAabbs, entityCount, entityStride, visibility, entitiesSoa);
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
            nativeScanIntersect(queryAabbs, queryCount, entityAabbs, entityCount, entityStride, visibility, entitiesSoa);
            return;
        }
        LatticeNative.logFallbackOnce("aabb_query", "native entity AABB scan unavailable");
        if (entitiesSoa) {
            javaScanSoa(queryAabbs, queryCount, entityAabbs, entityCount, entityStride, visibility);
        } else {
            javaScan(queryAabbs, queryCount, entityAabbs, entityCount, visibility);
        }
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

    static void javaScanSoa(double[] queryAabbs, int queryCount,
                            double[] entityAabbs, int entityCount,
                            long[] visibility) {
        javaScanSoa(queryAabbs, queryCount, entityAabbs, entityCount, entityCount, visibility);
    }

    static void javaScanSoa(double[] queryAabbs, int queryCount,
                            double[] entityAabbs, int entityCount, int entityStride,
                            long[] visibility) {
        int row = rowLongs(entityCount);
        Arrays.fill(visibility, 0, queryCount * row, 0L);
        if (queryCount == 0 || entityCount == 0) return;
        int minY = entityStride;
        int minZ = minY + entityStride;
        int maxX = minZ + entityStride;
        int maxY = maxX + entityStride;
        int maxZ = maxY + entityStride;
        for (int q = 0; q < queryCount; ++q) {
            double qMinX = queryAabbs[q * AABB_STRIDE];
            double qMinY = queryAabbs[q * AABB_STRIDE + 1];
            double qMinZ = queryAabbs[q * AABB_STRIDE + 2];
            double qMaxX = queryAabbs[q * AABB_STRIDE + 3];
            double qMaxY = queryAabbs[q * AABB_STRIDE + 4];
            double qMaxZ = queryAabbs[q * AABB_STRIDE + 5];
            int rowBase = q * row;
            for (int e = 0; e < entityCount; ++e) {
                if (qMinX <= entityAabbs[maxX + e] && qMaxX >= entityAabbs[e]
                        && qMinY <= entityAabbs[maxY + e] && qMaxY >= entityAabbs[minY + e]
                        && qMinZ <= entityAabbs[maxZ + e] && qMaxZ >= entityAabbs[minZ + e]) {
                    visibility[rowBase + (e >>> 6)] |= 1L << (e & 63);
                }
            }
        }
    }

    private static native void nativeScanIntersect(
            double[] queryAabbs, int queryCount,
            double[] entityAabbs, int entityCount, int entityStride,
            long[] visibility, boolean entitiesSoa);
}
