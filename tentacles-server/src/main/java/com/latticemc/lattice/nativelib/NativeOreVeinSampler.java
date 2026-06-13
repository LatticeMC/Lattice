package com.latticemc.lattice.nativelib;

public final class NativeOreVeinSampler {
    private NativeOreVeinSampler() {}

    public enum Result {
        NONE,
        COPPER_ORE,
        IRON_ORE,
        RAW_COPPER_BLOCK,
        RAW_IRON_BLOCK,
        COPPER_FILLER,
        IRON_FILLER;

        private static final Result[] VALUES = values();

        static Result fromOrdinal(int ord) {
            if (ord < 0 || ord >= VALUES.length) return NONE;
            return VALUES[ord];
        }
    }

    public static boolean isAvailable() {
        LatticeNative.ensureLoaded();
        return LatticeNative.isLoaded();
    }

    public static Result sample(double veinToggle, double veinRidged, double veinGap,
                                long splitterSeedLo, long splitterSeedHi,
                                int blockX, int blockY, int blockZ) {
        LatticeNative.ensureLoaded();
        if (!LatticeNative.isLoaded()) {
            throw new IllegalStateException("NativeOreVeinSampler requires the lattice native library");
        }
        final int code = nativeSample(veinToggle, veinRidged, veinGap,
                splitterSeedLo, splitterSeedHi, blockX, blockY, blockZ);
        return Result.fromOrdinal(code);
    }

    private static native int nativeSample(double veinToggle, double veinRidged, double veinGap,
                                           long splitterSeedLo, long splitterSeedHi,
                                           int blockX, int blockY, int blockZ);
}
