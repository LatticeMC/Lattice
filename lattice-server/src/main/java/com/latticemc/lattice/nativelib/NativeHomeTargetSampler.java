package com.latticemc.lattice.nativelib;

public final class NativeHomeTargetSampler {
    private NativeHomeTargetSampler() {}

    public static boolean isAvailable() {
        LatticeNative.ensureLoaded();
        return LatticeNative.isLoaded();
    }

    public static int sampleHomeTarget(double[] candidateXyz,
                                       int candidateCount,
                                       double selfX,
                                       double selfY,
                                       double selfZ,
                                       double homeX,
                                       double homeY,
                                       double homeZ,
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

        if (NativeTargetSamplerGate.shouldUseNative(candidateCount, obstacleCount)) {
            LatticeNative.ensureLoaded();
        }
        if (NativeTargetSamplerGate.shouldUseNative(candidateCount, obstacleCount) && LatticeNative.isLoaded()) {
            return nativeSampleHomeTarget(candidateXyz, candidateCount,
                    selfX, selfY, selfZ,
                    homeX, homeY, homeZ,
                    obstacleAabbs, obstacleCount,
                    preferredDistance,
                    minClearance);
        }
        LatticeNative.logFallbackOnce("home_target_sampler", "native home target sampler unavailable");
        return javaSampleHomeTarget(candidateXyz, candidateCount,
                selfX, selfY, selfZ,
                homeX, homeY, homeZ,
                obstacleAabbs, obstacleCount,
                preferredDistance,
                minClearance);
    }

    public static int javaSampleHomeTarget(double[] candidateXyz,
                                           int candidateCount,
                                           double selfX,
                                           double selfY,
                                           double selfZ,
                                           double homeX,
                                           double homeY,
                                           double homeZ,
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

            double candidateHomeSq = sqDistance(x, y, z, homeX, homeY, homeZ);
            double candidateSelfSq = sqDistance(x, y, z, selfX, selfY, selfZ);
            double distanceError = Math.abs(candidateHomeSq - preferredSq);
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

    private static native int nativeSampleHomeTarget(
            double[] candidateXyz,
            int candidateCount,
            double selfX,
            double selfY,
            double selfZ,
            double homeX,
            double homeY,
            double homeZ,
            double[] obstacleAabbs,
            int obstacleCount,
            double preferredDistance,
            double minClearance);
}
