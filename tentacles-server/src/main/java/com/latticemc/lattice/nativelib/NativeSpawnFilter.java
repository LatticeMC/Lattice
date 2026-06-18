package com.latticemc.lattice.nativelib;

/**
 * JNI wrapper for the batched mob-spawn candidate filter.
 *
 * <p>Composes three native checks per candidate:
 * <ol>
 *   <li>Player distance — reject unless a player is within {@code maxSpawnDistanceSq}.</li>
 *   <li>Palette mask — reject unless the block at the candidate's position passes the
 *       pre-built 256-bit mask for its section.</li>
 *   <li>Entity clearance — reject if an existing entity AABB overlaps the candidate's
 *       spawn AABB.</li>
 * </ol>
 *
 * <p>All inputs are flattened primitive arrays to minimise JNI overhead.
 */
public final class NativeSpawnFilter {
    private NativeSpawnFilter() {}

    public static boolean isAvailable() {
        LatticeNative.ensureLoaded();
        return LatticeNative.isLoaded();
    }

    /**
     * Filter spawn candidates in batch.
     *
     * @param candidateXyz      flat (x, y, z) per candidate, length = candidateCount * 3
     * @param candidateCount    number of candidates
     * @param candidateDims     flat (halfWidth, height) per candidate, length = candidateCount * 2.
     *                          May be null to use the default 0.5 / 1.0 (1×1×1 AABB).
     * @param sectionStorages   per-section packed long[] storages (elements may be null for air)
     * @param sectionElementBits per-section element bit widths
     * @param sectionPassMasks  flat 256-bit palette masks (4 longs per section)
     * @param sectionCount      number of sections
     * @param sectionBaseY      world Y of sections[0] bottom
     * @param entityAabbs       flat (minX, minY, minZ, maxX, maxY, maxZ) per entity
     * @param entityCount       number of nearby entities
     * @param playerXyz         flat (x, y, z) per player
     * @param playerCount       number of players
     * @param maxSpawnDistanceSq squared max spawn distance
     * @param acceptable        output bitmap, length = ceil(candidateCount / 64)
     * @return number of accepted candidates
     */
    public static int filterCandidates(double[] candidateXyz, int candidateCount,
                                       double[] candidateDims,
                                       long[][] sectionStorages,
                                       int[] sectionElementBits,
                                       long[] sectionPassMasks,
                                       int sectionCount,
                                       int sectionBaseY,
                                       double[] entityAabbs, int entityCount,
                                       double[] playerXyz, int playerCount,
                                       double maxSpawnDistanceSq,
                                       long[] acceptable) {
        if (acceptable == null) throw new IllegalArgumentException("null acceptable");
        if (candidateCount < 0 || sectionCount < 0 || entityCount < 0 || playerCount < 0) {
            throw new IllegalArgumentException("negative count");
        }
        if (!LatticeNative.isLoaded()) {
            LatticeNative.logFallbackOnce("spawn_filter", "native spawn filter unavailable");
            return javaFilter(candidateXyz, candidateCount, candidateDims,
                    sectionStorages, sectionElementBits, sectionPassMasks,
                    sectionCount, sectionBaseY,
                    entityAabbs, entityCount, playerXyz, playerCount,
                    maxSpawnDistanceSq, acceptable);
        }
        return nativeFilterCandidates(candidateXyz, candidateCount, candidateDims,
                sectionStorages, sectionElementBits, sectionPassMasks,
                sectionCount, sectionBaseY,
                entityAabbs, entityCount, playerXyz, playerCount,
                maxSpawnDistanceSq, acceptable);
    }

    // ---- Java fallback (minimal — player distance + entity clearance only) ----

    public static int javaFilter(double[] candidateXyz, int candidateCount,
                                 double[] candidateDims,
                                 long[][] sectionStorages,
                                 int[] sectionElementBits,
                                 long[] sectionPassMasks,
                                 int sectionCount,
                                 int sectionBaseY,
                                 double[] entityAabbs, int entityCount,
                                 double[] playerXyz, int playerCount,
                                 double maxSpawnDistanceSq,
                                 long[] acceptable) {
        int bitmapLongs = (candidateCount + 63) >>> 6;
        java.util.Arrays.fill(acceptable, 0, bitmapLongs, 0L);
        if (candidateCount == 0) return 0;

        int accepted = 0;
        for (int i = 0; i < candidateCount; i++) {
            double cx = candidateXyz[i * 3];
            double cy = candidateXyz[i * 3 + 1];
            double cz = candidateXyz[i * 3 + 2];

            // Player distance check
            boolean nearPlayer = false;
            for (int p = 0; p < playerCount; p++) {
                double dx = playerXyz[p * 3] - cx;
                double dy = playerXyz[p * 3 + 1] - cy;
                double dz = playerXyz[p * 3 + 2] - cz;
                if (dx * dx + dy * dy + dz * dz <= maxSpawnDistanceSq) {
                    nearPlayer = true;
                    break;
                }
            }
            if (!nearPlayer) continue;

            // Entity clearance check
            double halfW = 0.5, height = 1.0;
            if (candidateDims != null) {
                halfW = candidateDims[i * 2];
                height = candidateDims[i * 2 + 1];
            }
            double minX = cx - halfW, minY = cy, minZ = cz - halfW;
            double maxX = cx + halfW, maxY = cy + height, maxZ = cz + halfW;
            boolean blocked = false;
            for (int e = 0; e < entityCount; e++) {
                if (minX <= entityAabbs[e * 6 + 3] && maxX >= entityAabbs[e * 6] &&
                    minY <= entityAabbs[e * 6 + 4] && maxY >= entityAabbs[e * 6 + 1] &&
                    minZ <= entityAabbs[e * 6 + 5] && maxZ >= entityAabbs[e * 6 + 2]) {
                    blocked = true;
                    break;
                }
            }
            if (blocked) continue;

            // NOTE: palette check omitted in Java fallback — caller must handle it
            acceptable[i >>> 6] |= 1L << (i & 63);
            accepted++;
        }
        return accepted;
    }

    // ---- Native entry point ----

    private static native int nativeFilterCandidates(
            double[] candidateXyz, int candidateCount,
            double[] candidateDims,
            Object[] sectionStorages,
            int[] sectionElementBits,
            long[] sectionPassMasks,
            int sectionCount,
            int sectionBaseY,
            double[] entityAabbs, int entityCount,
            double[] playerXyz, int playerCount,
            double maxSpawnDistanceSq,
            long[] acceptable);
}
