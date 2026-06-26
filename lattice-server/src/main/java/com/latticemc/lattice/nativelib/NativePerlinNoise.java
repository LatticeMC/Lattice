package com.latticemc.lattice.nativelib;

import java.lang.ref.Cleaner;

public final class NativePerlinNoise {
    private static final Cleaner CLEANER = Cleaner.create();

    private final long handle;
    @SuppressWarnings("unused")
    private final Cleaner.Cleanable cleanable;

    private NativePerlinNoise(long handle) {
        this.handle = handle;
        this.cleanable = CLEANER.register(this, new Destroy(handle));
    }

    public static boolean isAvailable() {
        LatticeNative.ensureLoaded();
        return LatticeNative.isLoaded();
    }

    public static NativePerlinNoise tryCreate(byte[] permutation, double originX, double originY, double originZ) {
        if (!isAvailable()) return null;
        try {
            long handle = nativeCreate(permutation, originX, originY, originZ);
            return handle == 0L ? null : new NativePerlinNoise(handle);
        } catch (UnsatisfiedLinkError | RuntimeException e) {
            LatticeNative.logFallbackOnce("perlin_noise", e.getMessage());
            return null;
        }
    }

    public double sample(double x, double y, double z) {
        return nativeSample(this.handle, x, y, z);
    }

    public double sampleYScaled(double x, double y, double z, double yScale, double yMax) {
        return nativeSampleYScaled(this.handle, x, y, z, yScale, yMax);
    }

    public double sampleDerivative(double x, double y, double z, double[] outDeriv) {
        return nativeSampleDerivative(this.handle, x, y, z, outDeriv);
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

    private static native long nativeCreate(byte[] permutation, double originX, double originY, double originZ);
    private static native void nativeDestroy(long handle);
    private static native double nativeSample(long handle, double x, double y, double z);
    private static native double nativeSampleYScaled(long handle, double x, double y, double z, double yScale, double yMax);
    private static native double nativeSampleDerivative(long handle, double x, double y, double z, double[] outDeriv);
}
