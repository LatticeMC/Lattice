package com.latticemc.lattice.nativelib;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

/**
 * First-batch native fast path for RegionFile zlib compression.
 *
 * <p>This Lattice migration only needs the DEFLATE/zlib helpers used by the
 * direct {@code RegionFile} patch. NBT indexing and other second-stage features
 * stay out of this first batch on purpose.</p>
 */
public final class NativeChunkSerializer {

    public static final long DEFAULT_MAX_INFLATED_BYTES = 64L * 1024L * 1024L;
    public static final int DEFAULT_LEVEL = 6;

    private NativeChunkSerializer() {}

    public static byte[] inflateZlib(byte[] src, int srcOff, int srcLen) {
        return inflateZlib(src, srcOff, srcLen, DEFAULT_MAX_INFLATED_BYTES);
    }

    public static byte[] inflateZlib(byte[] src, int srcOff, int srcLen, long maxInflatedBytes) {
        if (LatticeNative.isLoaded()) {
            byte[] out = nativeInflateZlibToNewArray(src, srcOff, srcLen, maxInflatedBytes);
            if (LatticeNative.VERIFY) verifyInflate(src, srcOff, srcLen, out);
            return out;
        }
        try {
            return jdkInflate(src, srcOff, srcLen, maxInflatedBytes);
        } catch (DataFormatException | IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static byte[] deflateZlib(byte[] src, int srcOff, int srcLen) {
        return deflateZlib(src, srcOff, srcLen, DEFAULT_LEVEL);
    }

    public static byte[] deflateZlib(byte[] src, int srcOff, int srcLen, int level) {
        if (LatticeNative.isLoaded()) {
            byte[] out = nativeDeflateZlibToNewArray(src, srcOff, srcLen, level);
            if (LatticeNative.VERIFY) verifyDeflate(src, srcOff, srcLen, level, out);
            return out;
        }
        try {
            return jdkDeflate(src, srcOff, srcLen, level);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static byte[] jdkInflate(byte[] src, int srcOff, int srcLen, long maxInflatedBytes)
            throws IOException, DataFormatException {
        ByteArrayInputStream bais = new ByteArrayInputStream(src, srcOff, srcLen);
        try (InflaterInputStream iis = new InflaterInputStream(bais)) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream(Math.max(srcLen * 4, 64 * 1024));
            byte[] buf = new byte[16 * 1024];
            long total = 0;
            int n;
            while ((n = iis.read(buf)) > 0) {
                total += n;
                if (total > maxInflatedBytes) {
                    throw new DataFormatException("decompressed size exceeds maxInflatedBytes");
                }
                baos.write(buf, 0, n);
            }
            return baos.toByteArray();
        }
    }

    private static byte[] jdkDeflate(byte[] src, int srcOff, int srcLen, int level)
            throws IOException {
        Deflater def = new Deflater(level);
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream(srcLen / 2 + 64);
             DeflaterOutputStream dos = new DeflaterOutputStream(baos, def)) {
            dos.write(src, srcOff, srcLen);
            dos.finish();
            return baos.toByteArray();
        } finally {
            def.end();
        }
    }

    private static void verifyInflate(byte[] src, int srcOff, int srcLen, byte[] nativeOut) {
        try {
            byte[] jvm = jdkInflate(src, srcOff, srcLen, DEFAULT_MAX_INFLATED_BYTES);
            if (!Arrays.equals(jvm, nativeOut)) {
                throw new AssertionError(
                        "lattice.verify: inflate mismatch - jvm=" + jvm.length + "B native=" + nativeOut.length + "B");
            }
        } catch (DataFormatException | IOException e) {
            throw new AssertionError("lattice.verify: jvm inflate threw " + e + " while native succeeded", e);
        }
    }

    private static void verifyDeflate(byte[] src, int srcOff, int srcLen, int level, byte[] nativeOut) {
        try {
            byte[] roundTrip = jdkInflate(nativeOut, 0, nativeOut.length, DEFAULT_MAX_INFLATED_BYTES);
            byte[] expected = Arrays.copyOfRange(src, srcOff, srcOff + srcLen);
            if (!Arrays.equals(roundTrip, expected)) {
                throw new AssertionError("lattice.verify: deflate round-trip mismatch (level=" + level + ")");
            }
        } catch (DataFormatException | IOException e) {
            throw new AssertionError("lattice.verify: round-trip failed", e);
        }
    }

    private static native byte[] nativeInflateZlibToNewArray(byte[] src, int srcOff, int srcLen, long maxInflatedBytes);

    private static native byte[] nativeDeflateZlibToNewArray(byte[] src, int srcOff, int srcLen, int level);
}
