package com.latticemc.lattice.nativelib;

import java.lang.ref.Cleaner;

public final class NativeDoublePerlinNoise {
    private static final Cleaner CLEANER = Cleaner.create();

    private final long handle;
    @SuppressWarnings("unused")
    private final Cleaner.Cleanable cleanable;

    private NativeDoublePerlinNoise(long handle) {
        this.handle = handle;
        this.cleanable = CLEANER.register(this, new Destroy(handle));
    }

    public static boolean isAvailable() {
        LatticeNative.ensureLoaded();
        return LatticeNative.isLoaded();
    }

    public static NativeDoublePerlinNoise tryCreate(double[] firstOrigins,
                                                    byte[] firstPermutations,
                                                    double[] firstAmplitudes,
                                                    double firstLacunarity,
                                                    double firstPersistence,
                                                    double[] secondOrigins,
                                                    byte[] secondPermutations,
                                                    double[] secondAmplitudes,
                                                    double secondLacunarity,
                                                    double secondPersistence,
                                                    double amplitude) {
        if (!isAvailable()) return null;
        try {
            long handle = nativeCreate(
                    firstOrigins,
                    firstPermutations,
                    firstAmplitudes,
                    firstLacunarity,
                    firstPersistence,
                    secondOrigins,
                    secondPermutations,
                    secondAmplitudes,
                    secondLacunarity,
                    secondPersistence,
                    amplitude
            );
            return handle == 0L ? null : new NativeDoublePerlinNoise(handle);
        } catch (UnsatisfiedLinkError | RuntimeException e) {
            LatticeNative.logFallbackOnce("double_perlin_noise", e.getMessage());
            return null;
        }
    }

    public double sample(double x, double y, double z) {
        return nativeSample(this.handle, x, y, z);
    }

    public long handle() {
        return this.handle;
    }

    private record Destroy(long handle) implements Runnable {
        @Override
        public void run() {
            if (handle == 0L) return;
            try {
                nativeDestroy(handle);
            } catch (UnsatisfiedLinkError ignored) {
            }
        }
    }

    private static native long nativeCreate(double[] firstOrigins,
                                            byte[] firstPermutations,
                                            double[] firstAmplitudes,
                                            double firstLacunarity,
                                            double firstPersistence,
                                            double[] secondOrigins,
                                            byte[] secondPermutations,
                                            double[] secondAmplitudes,
                                            double secondLacunarity,
                                            double secondPersistence,
                                            double amplitude);
    private static native void nativeDestroy(long handle);
    private static native double nativeSample(long handle, double x, double y, double z);
}
