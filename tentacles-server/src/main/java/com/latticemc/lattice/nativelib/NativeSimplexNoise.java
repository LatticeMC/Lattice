package com.latticemc.lattice.nativelib;

import java.lang.ref.Cleaner;

public final class NativeSimplexNoise {
    private static final Cleaner CLEANER = Cleaner.create();
    private static final int PERMUTATION_SIZE = 256;

    private final long handle;
    @SuppressWarnings("unused")
    private final Cleaner.Cleanable cleanable;

    private NativeSimplexNoise(long handle) {
        this.handle = handle;
        this.cleanable = CLEANER.register(this, new Destroy(handle));
    }

    public static boolean isAvailable() {
        LatticeNative.ensureLoaded();
        return LatticeNative.isLoaded();
    }

    public static NativeSimplexNoise tryCreate(int[] permutation, double originX, double originY, double originZ) {
        if (!isAvailable()) return null;
        try {
            long handle = nativeCreate(normalizePermutation(permutation), originX, originY, originZ);
            return handle == 0L ? null : new NativeSimplexNoise(handle);
        } catch (UnsatisfiedLinkError | RuntimeException e) {
            LatticeNative.logFallbackOnce("simplex_noise", e.getMessage());
            return null;
        }
    }

    public double sample2d(double x, double y) {
        return nativeSample2d(this.handle, x, y);
    }

    public double sample3d(double x, double y, double z) {
        return nativeSample3d(this.handle, x, y, z);
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

    private static native long nativeCreate(int[] permutation, double originX, double originY, double originZ);
    private static native void nativeDestroy(long handle);
    private static native double nativeSample2d(long handle, double x, double y);
    private static native double nativeSample3d(long handle, double x, double y, double z);

    private static int[] normalizePermutation(int[] permutation) {
        if (permutation == null || permutation.length == PERMUTATION_SIZE) {
            return permutation;
        }
        if (permutation.length < PERMUTATION_SIZE) {
            throw new IllegalArgumentException("lattice simplex: permutation must contain at least 256 ints");
        }

        int[] normalized = new int[PERMUTATION_SIZE];
        System.arraycopy(permutation, 0, normalized, 0, PERMUTATION_SIZE);
        return normalized;
    }
}
