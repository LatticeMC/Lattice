package com.latticemc.lattice.nativelib;

/** Shared request-size gate for target samplers. Small requests stay entirely in Java. */
final class NativeTargetSamplerGate {
    private static final int MIN_WORK = Integer.getInteger(
            "lattice.nativeTargetSampler.minWork", Integer.MAX_VALUE);

    private NativeTargetSamplerGate() {}

    static boolean shouldUseNative(int candidateCount, int obstacleCount) {
        if (candidateCount < 0 || obstacleCount < 0) {
            throw new IllegalArgumentException("negative request size");
        }
        long work = (long) candidateCount * Math.max(1, obstacleCount);
        return work >= Math.max(0, MIN_WORK);
    }

    static boolean shouldUseNative(int candidateCount) {
        if (candidateCount < 0) throw new IllegalArgumentException("negative candidate count");
        return candidateCount >= Math.max(0, MIN_WORK);
    }
}
