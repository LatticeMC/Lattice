package com.latticemc.lattice.nativelib;

public final class NativePaletteOps {

    private NativePaletteOps() {}

    public static int get(long[] data, int elementBits, long index) {
        if (LatticeNative.isLoaded()) {
            int nv = nativeGet(data, elementBits, index);
            if (LatticeNative.VERIFY) {
                int jv = javaGet(data, elementBits, index);
                if (nv != jv) mismatch("get", elementBits, index, jv, nv);
            }
            return nv;
        }
        return javaGet(data, elementBits, index);
    }

    public static int set(long[] data, int elementBits, long index, int value) {
        if (LatticeNative.isLoaded()) {
            return nativeSet(data, elementBits, index, value);
        }
        return javaSet(data, elementBits, index, value);
    }

    public static void bulkGet(long[] data, int elementBits,
                               long startIndex, int[] out, int outOff, int count) {
        if (LatticeNative.isLoaded()) {
            nativeBulkGet(data, elementBits, startIndex, out, outOff, count);
            return;
        }
        LatticeNative.logFallbackOnce("palette_ops", "native palette bulkGet unavailable");
        javaBulkGet(data, elementBits, startIndex, out, outOff, count);
    }

    public static void bulkSet(long[] data, int elementBits,
                               long startIndex, int[] in, int inOff, int count) {
        if (LatticeNative.isLoaded()) {
            nativeBulkSet(data, elementBits, startIndex, in, inOff, count);
            return;
        }
        LatticeNative.logFallbackOnce("palette_ops", "native palette bulkSet unavailable");
        javaBulkSet(data, elementBits, startIndex, in, inOff, count);
    }

    private static int javaGet(long[] data, int elementBits, long index) {
        int epl = 64 / elementBits;
        int longIndex = (int) (index / epl);
        int bitOff = (int) ((index % epl) * elementBits);
        long mask = (elementBits >= 64) ? ~0L : (1L << elementBits) - 1L;
        return (int) ((data[longIndex] >>> bitOff) & mask);
    }

    private static int javaSet(long[] data, int elementBits, long index, int value) {
        int epl = 64 / elementBits;
        int longIndex = (int) (index / epl);
        int bitOff = (int) ((index % epl) * elementBits);
        long mask = (elementBits >= 64) ? ~0L : (1L << elementBits) - 1L;
        long word = data[longIndex];
        int old = (int) ((word >>> bitOff) & mask);
        long v = value & mask;
        data[longIndex] = (word & ~(mask << bitOff)) | (v << bitOff);
        return old;
    }

    private static void javaBulkGet(long[] data, int elementBits,
                                    long startIndex, int[] out, int outOff, int count) {
        for (int i = 0; i < count; i++) {
            out[outOff + i] = javaGet(data, elementBits, startIndex + i);
        }
    }

    private static void javaBulkSet(long[] data, int elementBits,
                                    long startIndex, int[] in, int inOff, int count) {
        for (int i = 0; i < count; i++) {
            javaSet(data, elementBits, startIndex + i, in[inOff + i]);
        }
    }

    private static void mismatch(String op, int bits, long i, long jvm, long nat) {
        throw new AssertionError(
                "lattice.verify: " + op + " mismatch (bits=" + bits + " i=" + i + ") jvm=" + jvm + " native=" + nat);
    }

    private static native int nativeGet(long[] data, int elementBits, long index);
    private static native int nativeSet(long[] data, int elementBits, long index, int value);
    private static native void nativeBulkGet(long[] data, int elementBits, long startIndex, int[] out, int outOff, int count);
    private static native void nativeBulkSet(long[] data, int elementBits, long startIndex, int[] in, int inOff, int count);
}
