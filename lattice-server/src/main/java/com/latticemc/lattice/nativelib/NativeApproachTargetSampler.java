package com.latticemc.lattice.nativelib;

public final class NativeApproachTargetSampler {
    private NativeApproachTargetSampler() {}

    public static boolean isAvailable() {
        LatticeNative.ensureLoaded();
        return LatticeNative.isLoaded();
    }

    public static int sampleApproachTarget(double[] candidateXyz,
                                           int candidateCount,
                                           double selfX,
                                           double selfY,
                                           double selfZ,
                                           double targetX,
                                           double targetY,
                                           double targetZ,
                                           double[] obstacleAabbs,
                                           int obstacleCount,
                                           double preferredDistance,
                                           double minClearance) {
        if (candidateCount < 0 || obstacleCount < 0) {
            throw new IllegalArgumentException("negative count");
        }
        if (candidateCount > 0 && (candidateXyz == null || candidateXyz.length < candidateCount * 3)) {
            throw new IllegalArgumentException("candidate array too short");
        }
        if (obstacleCount > 0 && (obstacleAabbs == null || obstacleAabbs.length < obstacleCount * 6)) {
            throw new IllegalArgumentException("obstacle array too short");
        }

        LatticeNative.ensureLoaded();
        if (LatticeNative.isLoaded()) {
            return nativeSampleApproachTarget(candidateXyz, candidateCount,
                    selfX, selfY, selfZ,
                    targetX, targetY, targetZ,
                    obstacleAabbs, obstacleCount,
                    preferredDistance,
                    minClearance);
        }
        LatticeNative.logFallbackOnce("approach_target_sampler", "native approach target sampler unavailable");
        return javaSampleApproachTarget(candidateXyz, candidateCount,
                selfX, selfY, selfZ,
                targetX, targetY, targetZ,
                obstacleAabbs, obstacleCount,
                preferredDistance,
                minClearance);
    }

    public static int javaSampleApproachTarget(double[] candidateXyz,
                                               int candidateCount,
                                               double selfX,
                                               double selfY,
                                               double selfZ,
                                               double targetX,
                                               double targetY,
                                               double targetZ,
                                               double[] obstacleAabbs,
                                               int obstacleCount,
                                               double preferredDistance,
                                               double minClearance) {
        double preferredSq = preferredDistance * preferredDistance;
        double bestScore = Double.NEGATIVE_INFINITY;
        int bestIndex = -1;

        for (int i = 0; i < candidateCount; ++i) {
            double x = candidateXyz[i * 3];
            double y = candidateXyz[i * 3 + 1];
            double z = candidateXyz[i * 3 + 2];
            if (collides(obstacleAabbs, obstacleCount, x, y, z, minClearance)) continue;

            double candidateTargetSq = sqDistance(x, y, z, targetX, targetY, targetZ);
            double candidateSelfSq = sqDistance(x, y, z, selfX, selfY, selfZ);
            double distanceError = Math.abs(candidateTargetSq - preferredSq);
            double score = -distanceError - candidateSelfSq * 0.10;
            if (score > bestScore) {
                bestScore = score;
                bestIndex = i;
            }
        }

        return bestIndex;
    }

    private static boolean collides(double[] obstacleAabbs,
                                    int obstacleCount,
                                    double x,
                                    double y,
                                    double z,
                                    double minClearance) {
        if (obstacleAabbs == null || obstacleCount == 0) return false;
        double minX = x - minClearance;
        double minY = y;
        double minZ = z - minClearance;
        double maxX = x + minClearance;
        double maxY = y + 1.0;
        double maxZ = z + minClearance;
        for (int i = 0; i < obstacleCount; ++i) {
            int base = i * 6;
            if (minX <= obstacleAabbs[base + 3] && maxX >= obstacleAabbs[base]
                    && minY <= obstacleAabbs[base + 4] && maxY >= obstacleAabbs[base + 1]
                    && minZ <= obstacleAabbs[base + 5] && maxZ >= obstacleAabbs[base + 2]) {
                return true;
            }
        }
        return false;
    }

    private static double sqDistance(double ax, double ay, double az,
                                     double bx, double by, double bz) {
        double dx = ax - bx;
        double dy = ay - by;
        double dz = az - bz;
        return dx * dx + dy * dy + dz * dz;
    }

    private static native int nativeSampleApproachTarget(
            double[] candidateXyz,
            int candidateCount,
            double selfX,
            double selfY,
            double selfZ,
            double targetX,
            double targetY,
            double targetZ,
            double[] obstacleAabbs,
            int obstacleCount,
            double preferredDistance,
            double minClearance);
}
