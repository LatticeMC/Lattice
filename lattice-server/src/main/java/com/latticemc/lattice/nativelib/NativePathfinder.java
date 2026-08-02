package com.latticemc.lattice.nativelib;

import java.util.concurrent.atomic.LongAdder;
import net.minecraft.world.level.pathfinder.PathType;

public final class NativePathfinder {
    public static final int EXPECTED_NATIVE_ABI = 10;
    private static final int HISTOGRAM_BUCKETS = Long.SIZE;
    private static final LongAdder ATTEMPTS = new LongAdder();
    private static final LongAdder SUCCESSES = new LongAdder();
    private static final LongAdder FALLBACKS = new LongAdder();
    private static final LongAdder VERIFY_MISMATCHES = new LongAdder();
    private static final LongAdder COMPLETED_REQUESTS = new LongAdder();
    private static final LongAdder TOTAL_NANOS = new LongAdder();
    private static final LongAdder[] TOTAL_MICROS_HISTOGRAM = createHistogram();
    private static final LongAdder PRECOMPUTE_CALLS = new LongAdder();
    private static final LongAdder PRECOMPUTE_NANOS = new LongAdder();
    private static final LongAdder NATIVE_CALLS = new LongAdder();
    private static final LongAdder NATIVE_NANOS = new LongAdder();
    private static final LongAdder COMPARABLE_NATIVE_CALLS = new LongAdder();
    private static final LongAdder COMPARABLE_NATIVE_NANOS = new LongAdder();
    private static final LongAdder VANILLA_CALLS = new LongAdder();
    private static final LongAdder VANILLA_NANOS = new LongAdder();
    private static final LongAdder SHADOW_VANILLA_CALLS = new LongAdder();
    private static final LongAdder SHADOW_VANILLA_NANOS = new LongAdder();
    private static final LongAdder STATE_SNAPSHOT_CALLS = new LongAdder();
    private static final LongAdder STATE_SNAPSHOT_NANOS = new LongAdder();
    private static final LongAdder STATE_SNAPSHOT_CELLS = new LongAdder();
    private static final LongAdder STATE_SNAPSHOT_DESCRIPTORS = new LongAdder();
    private static final LongAdder STATE_SNAPSHOT_UNSUPPORTED = new LongAdder();
    private static final LongAdder STATE_SNAPSHOT_CACHE_HITS = new LongAdder();
    private static final LongAdder STATE_SNAPSHOT_CACHE_MISSES = new LongAdder();
    private static final LongAdder STATE_MIRROR_HITS = new LongAdder();
    private static final LongAdder STATE_MIRROR_MISSES = new LongAdder();
    private static final LongAdder STATE_MIRROR_UPLOADS = new LongAdder();
    private static final LongAdder STATE_MIRROR_NATIVE_CALLS = new LongAdder();
    private static final LongAdder STATE_MIRROR_NATIVE_NANOS = new LongAdder();
    private static final LongAdder STATE_SNAPSHOT_NATIVE_CALLS = new LongAdder();
    private static final LongAdder STATE_SNAPSHOT_NATIVE_NANOS = new LongAdder();
    private static final LongAdder STATE_MIRROR_SEARCH_NODES = new LongAdder();
    private static final LongAdder SHORT_PATH_GATES = new LongAdder();
    private static final LongAdder MIRROR_COLD_GATES = new LongAdder();
    private static final LongAdder MIRROR_WARMUP_PASSES = new LongAdder();
    private static final LongAdder MIRROR_COVER_PROBES = new LongAdder();
    private static final LongAdder MIRROR_COVER_HITS = new LongAdder();
    private static final LongAdder MIRROR_COVER_NANOS = new LongAdder();
    private static final LongAdder RAW_PATH_TYPE_CACHE_HITS = new LongAdder();
    private static final LongAdder RAW_PATH_TYPE_CACHE_MISSES = new LongAdder();
    private static final LongAdder RAW_PATH_TYPE_CACHE_OUTSIDE = new LongAdder();
    private static final LongAdder REGIONS_TOO_SMALL = new LongAdder();
    private static final LongAdder EMPTY_RESULTS = new LongAdder();
    private static final LongAdder[] UNSUPPORTED_PATH_TYPES = createPathTypeCounters();
    private static volatile boolean nativeChecked;
    private static volatile boolean nativeCompatible;
    private static volatile boolean nativeDisabled;
    // Operator-facing switch: `/lattice pathfinder enabled <bool>` plus the
    // `-Dlattice.nativePathfinder=false` startup override. Kept separate from
    // nativeDisabled, which latches after a hard native failure and must stay
    // latched even if an operator toggles this back on.
    private static volatile boolean enabled =
            !"false".equalsIgnoreCase(System.getProperty("lattice.nativePathfinder", "true"));

    private NativePathfinder() {}

    public static boolean isEnabled() {
        return enabled;
    }

    public static void setEnabled(boolean value) {
        enabled = value;
    }

    public record PathResult(int[] encodedPath,
                             int length,
                             boolean reachedTarget,
                             int targetIndex) {
        private static final int HEADER_INTS = 3;
        private static final int NODE_INTS = 3;

        public int x(int index) {
            return this.encodedPath[HEADER_INTS + index * NODE_INTS];
        }

        public int y(int index) {
            return this.encodedPath[HEADER_INTS + index * NODE_INTS + 1];
        }

        public int z(int index) {
            return this.encodedPath[HEADER_INTS + index * NODE_INTS + 2];
        }
    }

    public static boolean isAvailable() {
        LatticeNative.ensureLoaded();
        if (!LatticeNative.isLoaded() || nativeDisabled || !enabled) return false;
        if (nativeChecked) return nativeCompatible;
        synchronized (NativePathfinder.class) {
            if (nativeChecked) return nativeCompatible;
            try {
                int actual = nativeAbiVersion();
                nativeCompatible = actual == EXPECTED_NATIVE_ABI;
                if (!nativeCompatible) {
                    LatticeNative.logFallbackOnce("native_pathfinder",
                            "native ABI mismatch: expected " + EXPECTED_NATIVE_ABI + ", got " + actual);
                }
            } catch (UnsatisfiedLinkError | RuntimeException e) {
                nativeCompatible = false;
                LatticeNative.logFallbackOnce("native_pathfinder", "native ABI unavailable: " + e.getMessage());
            }
            nativeChecked = true;
            return nativeCompatible;
        }
    }

    public static PathResult findPath(byte[] pathTypes,
                                      float[] floorLevels,
                                      int regionMinX, int regionMinY, int regionMinZ,
                                      int regionSizeX, int regionSizeY, int regionSizeZ,
                                      int startX, int startY, int startZ,
                                      int[] targetX, int[] targetY, int[] targetZ, int targetCount,
                                      float maxRange, int maxVisitedNodes, int reachRange,
                                      int entityWidth, int entityHeight, float maxUpStep,
                                      int maxFallDistance, float[] pathfindingMalus,
                                      float mobJumpHeight, float bbWidth,
                                      boolean canWalkOverFences, boolean mobsIgnoreRails,
                                      boolean canFloat, boolean isAmphibious, int levelMinY,
                                      int[] outPath) {
        validate(pathTypes, floorLevels, regionSizeX, regionSizeY, regionSizeZ, targetX, targetY, targetZ, targetCount,
                maxRange, maxVisitedNodes, pathfindingMalus, outPath);
        if (!isAvailable()) {
            throw new IllegalStateException("native pathfinder unavailable");
        }

        try {
            long header = nativeFindPath(pathTypes, floorLevels,
                    regionMinX, regionMinY, regionMinZ,
                    regionSizeX, regionSizeY, regionSizeZ,
                    startX, startY, startZ,
                    targetX, targetY, targetZ, targetCount,
                    maxRange, maxVisitedNodes, reachRange,
                    entityWidth, entityHeight, maxUpStep, maxFallDistance, pathfindingMalus,
                    mobJumpHeight, bbWidth, canWalkOverFences, mobsIgnoreRails,
                    canFloat, isAmphibious, levelMinY,
                    outPath);
            return decode(outPath, header);
        } catch (UnsatisfiedLinkError | RuntimeException e) {
            nativeDisabled = true;
            LatticeNative.logFallbackOnce("native_pathfinder", e.getMessage());
            throw e;
        }
    }

    public static float[] pathfindingMalusFor(PathType[] values) {
        float[] malus = new float[values.length];
        for (int i = 0; i < values.length; ++i) {
            malus[i] = values[i].getMalus();
        }
        return malus;
    }

    public static void recordAttempt() {
        ATTEMPTS.increment();
    }

    public static void recordSuccess() {
        SUCCESSES.increment();
    }

    public static void recordFallback() {
        FALLBACKS.increment();
    }

    public static void recordVerifyMismatch() {
        VERIFY_MISMATCHES.increment();
    }

    public static void recordCompletedRequest(long nanos) {
        long clampedNanos = Math.max(0L, nanos);
        long micros = clampedNanos / 1_000L;
        COMPLETED_REQUESTS.increment();
        TOTAL_NANOS.add(clampedNanos);
        TOTAL_MICROS_HISTOGRAM[histogramBucket(micros)].increment();
    }

    public static void recordPrecomputeNanos(long nanos) {
        PRECOMPUTE_CALLS.increment();
        PRECOMPUTE_NANOS.add(Math.max(0L, nanos));
    }

    public static void recordNativeNanos(long nanos) {
        NATIVE_CALLS.increment();
        NATIVE_NANOS.add(Math.max(0L, nanos));
    }

    public static PathResult findPathFromStateSnapshot(int[] stateCells,
                                                       byte[] descriptorPathTypes,
                                                       float[] descriptorFloorHeights,
                                                       int descriptorCount,
                                                       int stateMinX, int stateMinY, int stateMinZ,
                                                       int stateSizeX, int stateSizeY, int stateSizeZ,
                                                       int regionMinX, int regionMinY, int regionMinZ,
                                                       int regionSizeX, int regionSizeY, int regionSizeZ,
                                                       int startX, int startY, int startZ,
                                                       int[] targetX, int[] targetY, int[] targetZ, int targetCount,
                                                       float maxRange, int maxVisitedNodes, int reachRange,
                                                       int entityWidth, int entityHeight, float maxUpStep,
                                                       int maxFallDistance, float[] pathfindingMalus,
                                                       float mobJumpHeight, float bbWidth,
                                                       boolean canPassDoors, boolean canOpenDoors,
                                                       int mobBlockX, int mobBlockY, int mobBlockZ,
                                                       boolean canWalkOverFences, boolean mobsIgnoreRails,
                                                       boolean canFloat, boolean isAmphibious, int levelMinY,
                                                       int worldKey,
                                                       int[] outPath) {
        validateStateSnapshot(stateCells, descriptorPathTypes, descriptorFloorHeights, descriptorCount,
                stateSizeX, stateSizeY, stateSizeZ);
        validate(null, null, regionSizeX, regionSizeY, regionSizeZ, targetX, targetY, targetZ, targetCount,
                maxRange, maxVisitedNodes, pathfindingMalus, outPath);
        if (!isAvailable()) throw new IllegalStateException("native pathfinder unavailable");
        try {
            long header = nativeFindPathFromStateSnapshot(
                    stateCells, descriptorPathTypes, descriptorFloorHeights, descriptorCount,
                    stateMinX, stateMinY, stateMinZ, stateSizeX, stateSizeY, stateSizeZ,
                    regionMinX, regionMinY, regionMinZ, regionSizeX, regionSizeY, regionSizeZ,
                    startX, startY, startZ, targetX, targetY, targetZ, targetCount,
                    maxRange, maxVisitedNodes, reachRange, entityWidth, entityHeight, maxUpStep,
                    maxFallDistance, pathfindingMalus, mobJumpHeight, bbWidth,
                    canPassDoors, canOpenDoors, mobBlockX, mobBlockY, mobBlockZ,
                    canWalkOverFences, mobsIgnoreRails, canFloat, isAmphibious, levelMinY, worldKey, outPath);
            return decode(outPath, header);
        } catch (UnsatisfiedLinkError | RuntimeException e) {
            nativeDisabled = true;
            LatticeNative.logFallbackOnce("native_pathfinder", e.getMessage());
            throw e;
        }
    }

    public static PathResult findPathFromStateMirror(int worldKey,
                                                     int regionMinX, int regionMinY, int regionMinZ,
                                                     int regionSizeX, int regionSizeY, int regionSizeZ,
                                                     int startX, int startY, int startZ,
                                                     int[] targetX, int[] targetY, int[] targetZ, int targetCount,
                                                     float maxRange, int maxVisitedNodes, int reachRange,
                                                     int entityWidth, int entityHeight, float maxUpStep,
                                                     int maxFallDistance, float[] pathfindingMalus,
                                                     float mobJumpHeight, float bbWidth,
                                                     boolean canPassDoors, boolean canOpenDoors,
                                                     int mobBlockX, int mobBlockY, int mobBlockZ,
                                                     boolean canWalkOverFences, boolean mobsIgnoreRails,
                                                     boolean canFloat, boolean isAmphibious, int levelMinY,
                                                     int[] outPath) {
        validate(null, null, regionSizeX, regionSizeY, regionSizeZ, targetX, targetY, targetZ, targetCount,
                maxRange, maxVisitedNodes, pathfindingMalus, outPath);
        if (!isAvailable()) throw new IllegalStateException("native pathfinder unavailable");
        return findPathFromStateMirrorUnchecked(worldKey,
                regionMinX, regionMinY, regionMinZ, regionSizeX, regionSizeY, regionSizeZ,
                startX, startY, startZ, targetX, targetY, targetZ, targetCount,
                maxRange, maxVisitedNodes, reachRange, entityWidth, entityHeight, maxUpStep,
                maxFallDistance, pathfindingMalus, mobJumpHeight, bbWidth,
                canPassDoors, canOpenDoors, mobBlockX, mobBlockY, mobBlockZ,
                canWalkOverFences, mobsIgnoreRails, canFloat, isAmphibious, levelMinY, outPath);
    }

    private static PathResult findPathFromStateMirrorUnchecked(int worldKey,
                                                                int regionMinX, int regionMinY, int regionMinZ,
                                                                int regionSizeX, int regionSizeY, int regionSizeZ,
                                                                int startX, int startY, int startZ,
                                                                int[] targetX, int[] targetY, int[] targetZ, int targetCount,
                                                                float maxRange, int maxVisitedNodes, int reachRange,
                                                                int entityWidth, int entityHeight, float maxUpStep,
                                                                int maxFallDistance, float[] pathfindingMalus,
                                                                float mobJumpHeight, float bbWidth,
                                                                boolean canPassDoors, boolean canOpenDoors,
                                                                int mobBlockX, int mobBlockY, int mobBlockZ,
                                                                boolean canWalkOverFences, boolean mobsIgnoreRails,
                                                                boolean canFloat, boolean isAmphibious, int levelMinY,
                                                                int[] outPath) {
        try {
            long header = nativeFindPathFromStateMirror(worldKey,
                    regionMinX, regionMinY, regionMinZ, regionSizeX, regionSizeY, regionSizeZ,
                    startX, startY, startZ, targetX, targetY, targetZ, targetCount,
                    maxRange, maxVisitedNodes, reachRange, entityWidth, entityHeight, maxUpStep,
                    maxFallDistance, pathfindingMalus, mobJumpHeight, bbWidth,
                    canPassDoors, canOpenDoors, mobBlockX, mobBlockY, mobBlockZ,
                    canWalkOverFences, mobsIgnoreRails, canFloat, isAmphibious, levelMinY, outPath);
            if (header == 0L) return null;
            STATE_MIRROR_SEARCH_NODES.add(Math.max(0, outPath[0]));
            return decode(outPath, header);
        } catch (UnsatisfiedLinkError | RuntimeException e) {
            nativeDisabled = true;
            LatticeNative.logFallbackOnce("native_pathfinder", e.getMessage());
            throw e;
        }
    }

    public static void invalidateStateMirror(int worldKey, int x, int y, int z) {
        if (!nativeChecked || !nativeCompatible || nativeDisabled || !LatticeNative.isLoaded()) return;
        try {
            nativeInvalidateStateMirror(worldKey, x, y, z);
        } catch (UnsatisfiedLinkError | RuntimeException e) {
            nativeDisabled = true;
            LatticeNative.logFallbackOnce("native_pathfinder", e.getMessage());
        }
    }

    public static void recordComparableNativeNanos(long nanos) {
        COMPARABLE_NATIVE_CALLS.increment();
        COMPARABLE_NATIVE_NANOS.add(Math.max(0L, nanos));
    }

    public static void recordVanillaNanos(long nanos) {
        VANILLA_CALLS.increment();
        VANILLA_NANOS.add(Math.max(0L, nanos));
    }

    public static void recordShadowVanillaNanos(long nanos) {
        SHADOW_VANILLA_CALLS.increment();
        SHADOW_VANILLA_NANOS.add(Math.max(0L, nanos));
    }

    public static void recordStateSnapshot(long nanos, int cells, int descriptors, boolean supported) {
        STATE_SNAPSHOT_CALLS.increment();
        STATE_SNAPSHOT_NANOS.add(Math.max(0L, nanos));
        STATE_SNAPSHOT_CELLS.add(Math.max(0, cells));
        STATE_SNAPSHOT_DESCRIPTORS.add(Math.max(0, descriptors));
        if (!supported) STATE_SNAPSHOT_UNSUPPORTED.increment();
    }

    public static void recordShortPathGate() {
        SHORT_PATH_GATES.increment();
    }

    public static void recordMirrorColdGate() {
        MIRROR_COLD_GATES.increment();
    }

    public static void recordMirrorWarmupPass() {
        MIRROR_WARMUP_PASSES.increment();
    }

    /**
     * Exact answer to "would the state mirror already cover this box?".
     *
     * <p>Cost asymmetry makes this the only viable gate. Measured at 150 zombies:
     * a mirror hit costs ~110us, a mirror miss ~6400us (before the store-loop fix)
     * and the plain Java path ~140us. Breaking even against Java therefore needs a
     * hit rate above 99%, which no statistical proxy — Manhattan distance, region
     * volume, historical node counts — can deliver. This probe walks sections
     * volume, historical node counts — can deliver. The probe walks sections rather
     * than cells, but it is not free: measured end to end it costs ~16-19us per call,
     * because this timer also covers the JNI transition and the invalidation-log
     * replay in refresh_state_mirror. Keep a volume floor in front of it so tiny
     * boxes never pay that.
     */
    public static boolean stateMirrorCovers(int worldKey,
                                            int minX, int minY, int minZ,
                                            int sizeX, int sizeY, int sizeZ) {
        if (!isAvailable()) return false;
        long start = System.nanoTime();
        boolean covered = false;
        try {
            covered = nativeStateMirrorCovers(worldKey, minX, minY, minZ, sizeX, sizeY, sizeZ);
            return covered;
        } catch (UnsatisfiedLinkError | RuntimeException e) {
            nativeDisabled = true;
            LatticeNative.logFallbackOnce("native_pathfinder", e.getMessage());
            return false;
        } finally {
            MIRROR_COVER_PROBES.increment();
            if (covered) MIRROR_COVER_HITS.increment();
            MIRROR_COVER_NANOS.add(Math.max(0L, System.nanoTime() - start));
        }
    }

    public static void recordStateSnapshotCache(long hits, long misses) {
        STATE_SNAPSHOT_CACHE_HITS.add(Math.max(0L, hits));
        STATE_SNAPSHOT_CACHE_MISSES.add(Math.max(0L, misses));
    }

    public static void recordStateMirrorHit() {
        STATE_MIRROR_HITS.increment();
    }

    public static void recordStateMirrorMiss() {
        STATE_MIRROR_MISSES.increment();
    }

    public static void recordStateMirrorUpload() {
        STATE_MIRROR_UPLOADS.increment();
    }

    public static void recordStateMirrorNativeNanos(long nanos) {
        STATE_MIRROR_NATIVE_CALLS.increment();
        STATE_MIRROR_NATIVE_NANOS.add(Math.max(0L, nanos));
    }

    public static void recordStateSnapshotNativeNanos(long nanos) {
        STATE_SNAPSHOT_NATIVE_CALLS.increment();
        STATE_SNAPSHOT_NATIVE_NANOS.add(Math.max(0L, nanos));
    }

    public static void recordRawPathTypeCache(long hits, long misses, long outside) {
        RAW_PATH_TYPE_CACHE_HITS.add(hits);
        RAW_PATH_TYPE_CACHE_MISSES.add(misses);
        RAW_PATH_TYPE_CACHE_OUTSIDE.add(outside);
    }

    public static void recordRegionTooSmall() {
        REGIONS_TOO_SMALL.increment();
    }

    public static void recordEmptyResult() {
        EMPTY_RESULTS.increment();
    }

    public static void recordUnsupportedPathType(PathType type) {
        UNSUPPORTED_PATH_TYPES[type.ordinal()].increment();
    }

    public static String stats() {
        long attempts = ATTEMPTS.sum();
        long successes = SUCCESSES.sum();
        return "enabled=" + enabled
                + " attempts=" + attempts
                + " completed=" + COMPLETED_REQUESTS.sum()
                + " successes=" + successes
                + " fallbacks=" + FALLBACKS.sum()
                + " verifyMismatches=" + VERIFY_MISMATCHES.sum()
                + " avgTotalMicros=" + averageMicros(TOTAL_NANOS.sum(), COMPLETED_REQUESTS.sum())
                + " p50TotalMicros~=" + percentileMicros(50)
                + " p95TotalMicros~=" + percentileMicros(95)
                + " p99TotalMicros~=" + percentileMicros(99)
                + " precomputeCalls=" + PRECOMPUTE_CALLS.sum()
                + " avgPrecomputeMicros=" + averageMicros(PRECOMPUTE_NANOS.sum(), PRECOMPUTE_CALLS.sum())
                + " nativeCalls=" + NATIVE_CALLS.sum()
                + " avgNativeMicros=" + averageMicros(NATIVE_NANOS.sum(), NATIVE_CALLS.sum())
                + " comparableNative=" + COMPARABLE_NATIVE_CALLS.sum()
                + '/' + averageMicros(COMPARABLE_NATIVE_NANOS.sum(), COMPARABLE_NATIVE_CALLS.sum())
                + " vanilla=" + VANILLA_CALLS.sum()
                + '/' + averageMicros(VANILLA_NANOS.sum(), VANILLA_CALLS.sum())
                + " shadowVanilla=" + SHADOW_VANILLA_CALLS.sum()
                + '/' + averageMicros(SHADOW_VANILLA_NANOS.sum(), SHADOW_VANILLA_CALLS.sum())
                + " stateSnapshot=" + STATE_SNAPSHOT_CALLS.sum()
                + '/' + averageMicros(STATE_SNAPSHOT_NANOS.sum(), STATE_SNAPSHOT_CALLS.sum())
                + '/' + STATE_SNAPSHOT_CELLS.sum()
                + '/' + STATE_SNAPSHOT_DESCRIPTORS.sum()
                + '/' + STATE_SNAPSHOT_UNSUPPORTED.sum()
                + " stateSnapshotCache=" + STATE_SNAPSHOT_CACHE_HITS.sum() + '/' + STATE_SNAPSHOT_CACHE_MISSES.sum()
                + " stateMirror=" + STATE_MIRROR_HITS.sum() + '/' + STATE_MIRROR_MISSES.sum()
                + '/' + STATE_MIRROR_UPLOADS.sum()
                + " mirrorNative=" + STATE_MIRROR_NATIVE_CALLS.sum() + '/'
                + averageMicros(STATE_MIRROR_NATIVE_NANOS.sum(), STATE_MIRROR_NATIVE_CALLS.sum())
                + " mirrorNodes=" + STATE_MIRROR_SEARCH_NODES.sum() + '/'
                + average(STATE_MIRROR_SEARCH_NODES.sum(), STATE_MIRROR_HITS.sum())
                + " snapshotNative=" + STATE_SNAPSHOT_NATIVE_CALLS.sum() + '/'
                + averageMicros(STATE_SNAPSHOT_NATIVE_NANOS.sum(), STATE_SNAPSHOT_NATIVE_CALLS.sum())
                + " shortPathGates=" + SHORT_PATH_GATES.sum()
                + " mirrorColdGates=" + MIRROR_COLD_GATES.sum()
                + " mirrorWarmupPasses=" + MIRROR_WARMUP_PASSES.sum()
                // covered/total probes, then average probe cost in nanoseconds. This
                // spans the JNI round trip and refresh_state_mirror too, so ~16-19us
                // is the observed range, not sub-microsecond.
                + " mirrorCoverProbes=" + MIRROR_COVER_HITS.sum() + '/' + MIRROR_COVER_PROBES.sum()
                + '/' + average(MIRROR_COVER_NANOS.sum(), MIRROR_COVER_PROBES.sum()) + "ns"
                + " rawPathTypeCache=" + RAW_PATH_TYPE_CACHE_HITS.sum() + '/' + RAW_PATH_TYPE_CACHE_MISSES.sum()
                + '/' + RAW_PATH_TYPE_CACHE_OUTSIDE.sum()
                + " rejects={smallRegion=" + REGIONS_TOO_SMALL.sum()
                + ", emptyResult=" + EMPTY_RESULTS.sum()
                + ", pathTypes=" + unsupportedPathTypes() + "}"
                + " jfrEvent=" + PathfinderJfrEvent.NAME;
    }

    public static void resetStats() {
        ATTEMPTS.reset();
        SUCCESSES.reset();
        FALLBACKS.reset();
        VERIFY_MISMATCHES.reset();
        COMPLETED_REQUESTS.reset();
        TOTAL_NANOS.reset();
        PRECOMPUTE_CALLS.reset();
        PRECOMPUTE_NANOS.reset();
        NATIVE_CALLS.reset();
        NATIVE_NANOS.reset();
        COMPARABLE_NATIVE_CALLS.reset();
        COMPARABLE_NATIVE_NANOS.reset();
        VANILLA_CALLS.reset();
        VANILLA_NANOS.reset();
        SHADOW_VANILLA_CALLS.reset();
        SHADOW_VANILLA_NANOS.reset();
        STATE_SNAPSHOT_CALLS.reset();
        STATE_SNAPSHOT_NANOS.reset();
        STATE_SNAPSHOT_CELLS.reset();
        STATE_SNAPSHOT_DESCRIPTORS.reset();
        STATE_SNAPSHOT_UNSUPPORTED.reset();
        STATE_SNAPSHOT_CACHE_HITS.reset();
        STATE_SNAPSHOT_CACHE_MISSES.reset();
        STATE_MIRROR_HITS.reset();
        STATE_MIRROR_MISSES.reset();
        STATE_MIRROR_UPLOADS.reset();
        STATE_MIRROR_NATIVE_CALLS.reset();
        STATE_MIRROR_NATIVE_NANOS.reset();
        STATE_SNAPSHOT_NATIVE_CALLS.reset();
        STATE_SNAPSHOT_NATIVE_NANOS.reset();
        STATE_MIRROR_SEARCH_NODES.reset();
        SHORT_PATH_GATES.reset();
        MIRROR_COLD_GATES.reset();
        MIRROR_WARMUP_PASSES.reset();
        MIRROR_COVER_PROBES.reset();
        MIRROR_COVER_HITS.reset();
        MIRROR_COVER_NANOS.reset();
        RAW_PATH_TYPE_CACHE_HITS.reset();
        RAW_PATH_TYPE_CACHE_MISSES.reset();
        RAW_PATH_TYPE_CACHE_OUTSIDE.reset();
        for (LongAdder bucket : TOTAL_MICROS_HISTOGRAM) bucket.reset();
        REGIONS_TOO_SMALL.reset();
        EMPTY_RESULTS.reset();
        for (LongAdder count : UNSUPPORTED_PATH_TYPES) count.reset();
    }

    private static long averageMicros(long nanos, long count) {
        return count <= 0L ? 0L : nanos / count / 1_000L;
    }

    private static long average(long total, long count) {
        return count <= 0L ? 0L : total / count;
    }

    private static LongAdder[] createHistogram() {
        LongAdder[] histogram = new LongAdder[HISTOGRAM_BUCKETS];
        for (int i = 0; i < histogram.length; i++) histogram[i] = new LongAdder();
        return histogram;
    }

    private static LongAdder[] createPathTypeCounters() {
        LongAdder[] counters = new LongAdder[PathType.values().length];
        for (int i = 0; i < counters.length; i++) counters[i] = new LongAdder();
        return counters;
    }

    private static String unsupportedPathTypes() {
        StringBuilder result = new StringBuilder();
        for (PathType type : PathType.values()) {
            long count = UNSUPPORTED_PATH_TYPES[type.ordinal()].sum();
            if (count == 0L) continue;
            if (!result.isEmpty()) result.append(',');
            result.append(type.name()).append('=').append(count);
        }
        return result.isEmpty() ? "none" : result.toString();
    }

    private static int histogramBucket(long micros) {
        if (micros <= 1L) return 0;
        return Math.min(HISTOGRAM_BUCKETS - 1, Long.SIZE - Long.numberOfLeadingZeros(micros - 1L));
    }

    private static long percentileMicros(int percentile) {
        long count = COMPLETED_REQUESTS.sum();
        if (count <= 0L) return 0L;
        long rank = Math.max(1L, (count * percentile + 99L) / 100L);
        long cumulative = 0L;
        for (int i = 0; i < TOTAL_MICROS_HISTOGRAM.length; i++) {
            cumulative += TOTAL_MICROS_HISTOGRAM[i].sum();
            if (cumulative >= rank) return i == HISTOGRAM_BUCKETS - 1 ? Long.MAX_VALUE : 1L << i;
        }
        return Long.MAX_VALUE;
    }

    private static PathResult decode(int[] raw, long header) {
        if (raw == null || raw.length < 3) {
            throw new IllegalArgumentException("native pathfinder returned malformed result");
        }
        raw[0] = (int)(header & 0xFFFFFFFFL);
        raw[1] = (int)((header >>> 32) & 1L);
        raw[2] = (int)(header >> 33);
        int length = raw[0];
        if (length < 0 || raw.length < 3 + length * 3) {
            throw new IllegalArgumentException("native pathfinder result too short");
        }
        return new PathResult(raw, length, raw[1] != 0, raw[2]);
    }

    private static void validate(byte[] pathTypes,
                                 float[] floorLevels,
                                 int regionSizeX, int regionSizeY, int regionSizeZ,
                                 int[] targetX, int[] targetY, int[] targetZ,
                                 int targetCount,
                                 float maxRange, int maxVisitedNodes, float[] pathfindingMalus,
                                 int[] outPath) {
        if (targetX == null || targetY == null || targetZ == null || pathfindingMalus == null || outPath == null) {
            throw new IllegalArgumentException("null pathfinder array");
        }
        if (regionSizeX <= 0 || regionSizeY <= 0 || regionSizeZ <= 0 || maxRange <= 0.0F || maxVisitedNodes <= 0) {
            throw new IllegalArgumentException("invalid pathfinder config");
        }
        int volume = Math.multiplyExact(Math.multiplyExact(regionSizeX, regionSizeY), regionSizeZ);
        if (pathTypes != null && pathTypes.length < volume) {
            throw new IllegalArgumentException("path type array too short");
        }
        // floorLevels is optional: a null array makes native assume the integer
        // cell Y, which matches an empty collision shape.
        if (floorLevels != null && floorLevels.length < volume) {
            throw new IllegalArgumentException("floor level array too short");
        }
        if (targetCount <= 0 || targetX.length < targetCount || targetY.length < targetCount || targetZ.length < targetCount) {
            throw new IllegalArgumentException("target arrays too short");
        }
        int requiredOutput = Math.addExact(3, Math.multiplyExact(maxVisitedNodes, 3));
        if (outPath.length < requiredOutput) {
            throw new IllegalArgumentException("output path array too short");
        }
    }

    private static void validateStateSnapshot(int[] stateCells,
                                              byte[] descriptorPathTypes,
                                              float[] descriptorFloorHeights,
                                              int descriptorCount,
                                              int sizeX, int sizeY, int sizeZ) {
        if (stateCells == null || descriptorPathTypes == null || descriptorFloorHeights == null) {
            throw new IllegalArgumentException("null state snapshot array");
        }
        if (descriptorCount <= 0 || sizeX <= 0 || sizeY <= 0 || sizeZ <= 0) {
            throw new IllegalArgumentException("invalid state snapshot dimensions");
        }
        int volume = Math.multiplyExact(Math.multiplyExact(sizeX, sizeY), sizeZ);
        if (stateCells.length < volume || descriptorPathTypes.length < descriptorCount
                || descriptorFloorHeights.length < descriptorCount) {
            throw new IllegalArgumentException("state snapshot array too short");
        }
    }

    private static native int nativeAbiVersion();

    private static native long nativeFindPath(
            byte[] pathTypes,
            float[] floorLevels,
            int regionMinX, int regionMinY, int regionMinZ,
            int regionSizeX, int regionSizeY, int regionSizeZ,
            int startX, int startY, int startZ,
            int[] targetX, int[] targetY, int[] targetZ, int targetCount,
            float maxRange, int maxVisitedNodes, int reachRange,
            int entityWidth, int entityHeight, float maxUpStep,
            int maxFallDistance, float[] pathfindingMalus,
            float mobJumpHeight, float bbWidth,
            boolean canWalkOverFences, boolean mobsIgnoreRails,
            boolean canFloat, boolean isAmphibious, int levelMinY,
            int[] outPath);

    private static native long nativeFindPathFromStateSnapshot(
            int[] stateCells, byte[] descriptorPathTypes, float[] descriptorFloorHeights, int descriptorCount,
            int stateMinX, int stateMinY, int stateMinZ, int stateSizeX, int stateSizeY, int stateSizeZ,
            int regionMinX, int regionMinY, int regionMinZ, int regionSizeX, int regionSizeY, int regionSizeZ,
            int startX, int startY, int startZ,
            int[] targetX, int[] targetY, int[] targetZ, int targetCount,
            float maxRange, int maxVisitedNodes, int reachRange,
            int entityWidth, int entityHeight, float maxUpStep,
            int maxFallDistance, float[] pathfindingMalus,
            float mobJumpHeight, float bbWidth,
            boolean canPassDoors, boolean canOpenDoors, int mobBlockX, int mobBlockY, int mobBlockZ,
            boolean canWalkOverFences, boolean mobsIgnoreRails,
            boolean canFloat, boolean isAmphibious, int levelMinY,
            int worldKey,
            int[] outPath);

    private static native long nativeFindPathFromStateMirror(
            int worldKey,
            int regionMinX, int regionMinY, int regionMinZ,
            int regionSizeX, int regionSizeY, int regionSizeZ,
            int startX, int startY, int startZ,
            int[] targetX, int[] targetY, int[] targetZ, int targetCount,
            float maxRange, int maxVisitedNodes, int reachRange,
            int entityWidth, int entityHeight, float maxUpStep,
            int maxFallDistance, float[] pathfindingMalus,
            float mobJumpHeight, float bbWidth,
            boolean canPassDoors, boolean canOpenDoors, int mobBlockX, int mobBlockY, int mobBlockZ,
            boolean canWalkOverFences, boolean mobsIgnoreRails,
            boolean canFloat, boolean isAmphibious, int levelMinY,
            int[] outPath);

    private static native void nativeInvalidateStateMirror(int worldKey, int x, int y, int z);

    private static native boolean nativeStateMirrorCovers(int worldKey,
            int minX, int minY, int minZ,
            int sizeX, int sizeY, int sizeZ);
}
