package com.latticemc.lattice.nativelib;

public final class NativeWaterTargetSampler {
    private NativeWaterTargetSampler() {}

    public static boolean isAvailable() {
        LatticeNative.ensureLoaded();
        return LatticeNative.isLoaded();
    }

    public static int sampleWaterTarget(double[] candidateXyz,
                                        boolean[] candidateIsWater,
                                        int candidateCount,
                                        double selfX,
                                        double selfY,
                                        double selfZ,
                                        boolean preferWater) {
        if (candidateCount < 0) {
            throw new IllegalArgumentException("negative count");
        }
        if (candidateCount > 0 && (candidateXyz == null || candidateXyz.length < candidateCount * 3)) {
            throw new IllegalArgumentException("candidate array too short");
        }
        if (candidateCount > 0 && (candidateIsWater == null || candidateIsWater.length < candidateCount)) {
            throw new IllegalArgumentException("water flag array too short");
        }

        LatticeNative.ensureLoaded();
        if (LatticeNative.isLoaded()) {
            return nativeSampleWaterTarget(candidateXyz, candidateIsWater, candidateCount,
                    selfX, selfY, selfZ, preferWater);
        }
        return javaSampleWaterTarget(candidateXyz, candidateIsWater, candidateCount,
                selfX, selfY, selfZ, preferWater);
    }

    public static int javaSampleWaterTarget(double[] candidateXyz,
                                            boolean[] candidateIsWater,
                                            int candidateCount,
                                            double selfX,
                                            double selfY,
                                            double selfZ,
                                            boolean preferWater) {
        double bestScore = Double.NEGATIVE_INFINITY;
        int bestIndex = -1;
        for (int i = 0; i < candidateCount; ++i) {
            double x = candidateXyz[i * 3];
            double y = candidateXyz[i * 3 + 1];
            double z = candidateXyz[i * 3 + 2];
            double dx = x - selfX;
            double dy = y - selfY;
            double dz = z - selfZ;
            double distancePenalty = (dx * dx + dy * dy + dz * dz) * 0.10;
            double terrainBonus = candidateIsWater[i] == preferWater ? 10.0 : 0.0;
            double score = terrainBonus - distancePenalty;
            if (score > bestScore) {
                bestScore = score;
                bestIndex = i;
            }
        }
        return bestIndex;
    }

    private static native int nativeSampleWaterTarget(
            double[] candidateXyz,
            boolean[] candidateIsWater,
            int candidateCount,
            double selfX,
            double selfY,
            double selfZ,
            boolean preferWater);
}
