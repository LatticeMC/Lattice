package com.latticemc.lattice.nativelib;

import java.lang.ref.Cleaner;

public final class NativeOctavePerlinNoise {
    private static final Cleaner CLEANER = Cleaner.create();

    private final long handle;
    @SuppressWarnings("unused")
    private final Cleaner.Cleanable cleanable;

    private NativeOctavePerlinNoise(long handle) {
        this.handle = handle;
        this.cleanable = CLEANER.register(this, new Destroy(handle));
    }

    public static boolean isAvailable() {
        LatticeNative.ensureLoaded();
        return LatticeNative.isLoaded();
    }

    public static NativeOctavePerlinNoise tryCreate(double[] origins,
                                                    byte[] permutations,
                                                    double[] amplitudes,
                                                    double lacunarity,
                                                    double persistence) {
        if (!isAvailable()) return null;
        try {
            long handle = nativeCreate(origins, permutations, amplitudes, lacunarity, persistence);
            return handle == 0L ? null : new NativeOctavePerlinNoise(handle);
        } catch (UnsatisfiedLinkError | RuntimeException e) {
            LatticeNative.logFallbackOnce("octave_perlin_noise", e.getMessage());
            return null;
        }
    }

    long handle() {
        return this.handle;
    }

    public double sample(double x, double y, double z) {
        return nativeSample(this.handle, x, y, z);
    }

    public double sampleFull(double x, double y, double z, double yScale, double yMax, boolean useOrigin) {
        return nativeSampleFull(this.handle, x, y, z, yScale, yMax, useOrigin);
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

    private static native long nativeCreate(double[] origins,
                                            byte[] permutations,
                                            double[] amplitudes,
                                            double lacunarity,
                                            double persistence);
    private static native void nativeDestroy(long handle);
    private static native double nativeSample(long handle, double x, double y, double z);
    private static native double nativeSampleFull(long handle,
                                                  double x,
                                                  double y,
                                                  double z,
                                                  double yScale,
                                                  double yMax,
                                                  boolean useOrigin);
}
