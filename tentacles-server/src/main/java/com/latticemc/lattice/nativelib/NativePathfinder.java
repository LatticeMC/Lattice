package com.latticemc.lattice.nativelib;

import java.util.concurrent.atomic.LongAdder;
import net.minecraft.world.level.pathfinder.PathType;

public final class NativePathfinder {
    public static final int EXPECTED_NATIVE_ABI = 6;
    private static final LongAdder ATTEMPTS = new LongAdder();
    private static final LongAdder SUCCESSES = new LongAdder();
    private static final LongAdder FALLBACKS = new LongAdder();
    private static final LongAdder VERIFY_MISMATCHES = new LongAdder();
    private static final LongAdder PRECOMPUTE_CALLS = new LongAdder();
    private static final LongAdder PRECOMPUTE_NANOS = new LongAdder();
    private static final LongAdder NATIVE_CALLS = new LongAdder();
    private static final LongAdder NATIVE_NANOS = new LongAdder();
    private static volatile boolean nativeChecked;
    private static volatile boolean nativeCompatible;
    private static volatile boolean nativeDisabled;

    private NativePathfinder() {}

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
        if (!LatticeNative.isLoaded() || nativeDisabled) return false;
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
                                      int regionMinX, int regionMinY, int regionMinZ,
                                      int regionSizeX, int regionSizeY, int regionSizeZ,
                                      int startX, int startY, int startZ,
                                      int[] targetX, int[] targetY, int[] targetZ, int targetCount,
                                      float maxRange, int maxVisitedNodes, int reachRange,
                                      int entityWidth, int entityHeight, float maxUpStep,
                                      int maxFallDistance, float[] pathfindingMalus,
                                      int[] outPath) {
        validate(pathTypes, regionSizeX, regionSizeY, regionSizeZ, targetX, targetY, targetZ, targetCount,
                maxRange, maxVisitedNodes, pathfindingMalus, outPath);
        if (!isAvailable()) {
            throw new IllegalStateException("native pathfinder unavailable");
        }

        try {
            long header = nativeFindPath(pathTypes,
                    regionMinX, regionMinY, regionMinZ,
                    regionSizeX, regionSizeY, regionSizeZ,
                    startX, startY, startZ,
                    targetX, targetY, targetZ, targetCount,
                    maxRange, maxVisitedNodes, reachRange,
                    entityWidth, entityHeight, maxUpStep, maxFallDistance, pathfindingMalus,
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

    public static void recordPrecomputeNanos(long nanos) {
        PRECOMPUTE_CALLS.increment();
        PRECOMPUTE_NANOS.add(Math.max(0L, nanos));
    }

    public static void recordNativeNanos(long nanos) {
        NATIVE_CALLS.increment();
        NATIVE_NANOS.add(Math.max(0L, nanos));
    }

    public static String stats() {
        long attempts = ATTEMPTS.sum();
        long successes = SUCCESSES.sum();
        return "attempts=" + attempts
                + " successes=" + successes
                + " fallbacks=" + FALLBACKS.sum()
                + " verifyMismatches=" + VERIFY_MISMATCHES.sum()
                + " avgPrecomputeMicros=" + averageMicros(PRECOMPUTE_NANOS.sum(), PRECOMPUTE_CALLS.sum())
                + " avgNativeMicros=" + averageMicros(NATIVE_NANOS.sum(), NATIVE_CALLS.sum());
    }

    private static long averageMicros(long nanos, long count) {
        return count <= 0L ? 0L : nanos / count / 1_000L;
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
                                 int regionSizeX, int regionSizeY, int regionSizeZ,
                                 int[] targetX, int[] targetY, int[] targetZ,
                                 int targetCount,
                                 float maxRange, int maxVisitedNodes, float[] pathfindingMalus,
                                 int[] outPath) {
        if (pathTypes == null || targetX == null || targetY == null || targetZ == null || pathfindingMalus == null || outPath == null) {
            throw new IllegalArgumentException("null pathfinder array");
        }
        if (regionSizeX <= 0 || regionSizeY <= 0 || regionSizeZ <= 0 || maxRange <= 0.0F || maxVisitedNodes <= 0) {
            throw new IllegalArgumentException("invalid pathfinder config");
        }
        int volume = Math.multiplyExact(Math.multiplyExact(regionSizeX, regionSizeY), regionSizeZ);
        if (pathTypes.length < volume) {
            throw new IllegalArgumentException("path type array too short");
        }
        if (targetCount <= 0 || targetX.length < targetCount || targetY.length < targetCount || targetZ.length < targetCount) {
            throw new IllegalArgumentException("target arrays too short");
        }
        int requiredOutput = Math.addExact(3, Math.multiplyExact(maxVisitedNodes, 3));
        if (outPath.length < requiredOutput) {
            throw new IllegalArgumentException("output path array too short");
        }
    }

    private static native int nativeAbiVersion();

    private static native long nativeFindPath(
            byte[] pathTypes,
            int regionMinX, int regionMinY, int regionMinZ,
            int regionSizeX, int regionSizeY, int regionSizeZ,
            int startX, int startY, int startZ,
            int[] targetX, int[] targetY, int[] targetZ, int targetCount,
            float maxRange, int maxVisitedNodes, int reachRange,
            int entityWidth, int entityHeight, float maxUpStep,
            int maxFallDistance, float[] pathfindingMalus,
            int[] outPath);
}
