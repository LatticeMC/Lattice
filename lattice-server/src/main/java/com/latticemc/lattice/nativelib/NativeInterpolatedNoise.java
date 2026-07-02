package com.latticemc.lattice.nativelib;

import java.lang.ref.Cleaner;

public final class NativeInterpolatedNoise {
    private static final Cleaner CLEANER = Cleaner.create();

    private final NativeOctavePerlinNoise lower;
    private final NativeOctavePerlinNoise upper;
    private final NativeOctavePerlinNoise interpolation;
    private final long handle;
    @SuppressWarnings("unused")
    private final Cleaner.Cleanable cleanable;

    private NativeInterpolatedNoise(NativeOctavePerlinNoise lower,
                                    NativeOctavePerlinNoise upper,
                                    NativeOctavePerlinNoise interpolation,
                                    long handle) {
        this.lower = lower;
        this.upper = upper;
        this.interpolation = interpolation;
        this.handle = handle;
        this.cleanable = CLEANER.register(this, new Destroy(handle));
    }

    public static boolean isAvailable() {
        LatticeNative.ensureLoaded();
        return LatticeNative.isLoaded();
    }

    public static NativeInterpolatedNoise tryCreate(NativeOctavePerlinNoise lower,
                                                    NativeOctavePerlinNoise upper,
                                                    NativeOctavePerlinNoise interpolation,
                                                    double xzScale,
                                                    double yScale,
                                                    double xzFactor,
                                                    double yFactor,
                                                    double smearScaleMultiplier) {
        if (!isAvailable() || lower == null || upper == null || interpolation == null) return null;
        try {
            long handle = nativeCreate(
                    lower.handle(),
                    upper.handle(),
                    interpolation.handle(),
                    xzScale,
                    yScale,
                    xzFactor,
                    yFactor,
                    smearScaleMultiplier
            );
            return handle == 0L ? null : new NativeInterpolatedNoise(lower, upper, interpolation, handle);
        } catch (UnsatisfiedLinkError | RuntimeException e) {
            LatticeNative.logFallbackOnce("interpolated_noise", e.getMessage());
            return null;
        }
    }

    public double sample(double blockX, double blockY, double blockZ) {
        return nativeSample(this.handle, blockX, blockY, blockZ);
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

    private static native long nativeCreate(long lowerOctaveHandle,
                                            long upperOctaveHandle,
                                            long interpolationOctaveHandle,
                                            double xzScale,
                                            double yScale,
                                            double xzFactor,
                                            double yFactor,
                                            double smearScaleMultiplier);
    private static native void nativeDestroy(long handle);
    private static native double nativeSample(long handle, double blockX, double blockY, double blockZ);
}
