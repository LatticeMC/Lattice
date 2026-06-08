package com.latticemc.lattice.nativelib;

import java.util.Arrays;
import java.util.function.IntPredicate;

public final class NativeHeightmap {

    public static final int COLUMN_COUNT = 256;
    public static final int SECTION_HEIGHT = 16;

    private NativeHeightmap() {}

    public static int maskLongsPerSection(int paletteSize) {
        return Math.max(1, (paletteSize + 63) >>> 6);
    }

    public static boolean isAvailable() {
        LatticeNative.ensureLoaded();
        return LatticeNative.isLoaded();
    }

    public static void populateHeightmap(long[][] storages,
                                         int[] elementBits,
                                         long[] passingMasksFlat,
                                         int maskLongsPerSection,
                                         int sectionCount,
                                         int sectionBaseY,
                                         int defaultHeight,
                                         int[] out) {
        if (storages == null || elementBits == null || passingMasksFlat == null || out == null) {
            throw new IllegalArgumentException("null array");
        }
        if (sectionCount < 0 || sectionCount > storages.length || sectionCount > elementBits.length) {
            throw new IllegalArgumentException("sectionCount out of range");
        }
        if (maskLongsPerSection <= 0) {
            throw new IllegalArgumentException("maskLongsPerSection must be positive");
        }
        if (passingMasksFlat.length < sectionCount * maskLongsPerSection) {
            throw new IllegalArgumentException("passingMasksFlat too short");
        }
        if (out.length < COLUMN_COUNT) {
            throw new IllegalArgumentException("out too short");
        }

        if (LatticeNative.isLoaded()) {
            if (LatticeNative.VERIFY) {
                int[] shadow = new int[COLUMN_COUNT];
                javaPopulate(storages, elementBits, passingMasksFlat, maskLongsPerSection,
                        sectionCount, sectionBaseY, defaultHeight, shadow);
                nativePopulateHeightmap(storages, elementBits, passingMasksFlat, maskLongsPerSection,
                        sectionCount, sectionBaseY, defaultHeight, out);
                if (!Arrays.equals(shadow, out)) {
                    throw new AssertionError("lattice.verify: heightmap mismatch");
                }
                return;
            }
            nativePopulateHeightmap(storages, elementBits, passingMasksFlat, maskLongsPerSection,
                    sectionCount, sectionBaseY, defaultHeight, out);
            return;
        }
        javaPopulate(storages, elementBits, passingMasksFlat, maskLongsPerSection,
                sectionCount, sectionBaseY, defaultHeight, out);
    }

    public static void javaPopulate(long[][] storages,
                                    int[] elementBits,
                                    long[] passingMasksFlat,
                                    int maskLongsPerSection,
                                    int sectionCount,
                                    int sectionBaseY,
                                    int defaultHeight,
                                    int[] out) {
        Arrays.fill(out, 0, COLUMN_COUNT, defaultHeight);
        if (sectionCount == 0) return;

        long r0 = -1L, r1 = -1L, r2 = -1L, r3 = -1L;
        int remainingCount = COLUMN_COUNT;

        for (int s = sectionCount - 1; s >= 0; --s) {
            int maskBase = s * maskLongsPerSection;
            if (!maskAny(passingMasksFlat, maskBase, maskLongsPerSection)) continue;

            int sectionWorldFloor = sectionBaseY + s * SECTION_HEIGHT;
            long[] storage = storages[s];
            int bits = elementBits[s];
            boolean defaultSection = (storage == null) || (bits == 0);

            if (defaultSection) {
                if (!maskBit(passingMasksFlat, maskBase, maskLongsPerSection, 0)) continue;
                int y = sectionWorldFloor + SECTION_HEIGHT - 1;
                for (int w = 0; w < 4; ++w) {
                    long bitsRow = pickRow(w, r0, r1, r2, r3);
                    while (bitsRow != 0) {
                        int bit = Long.numberOfTrailingZeros(bitsRow);
                        bitsRow &= bitsRow - 1;
                        out[w * 64 + bit] = y;
                    }
                }
                int popped = Long.bitCount(r0) + Long.bitCount(r1) + Long.bitCount(r2) + Long.bitCount(r3);
                remainingCount -= popped;
                r0 = 0; r1 = 0; r2 = 0; r3 = 0;
                if (remainingCount == 0) break;
                continue;
            }

            int epl = 64 / bits;
            long mask = (bits >= 64) ? -1L : ((1L << bits) - 1L);

            for (int yLocal = SECTION_HEIGHT - 1; yLocal >= 0; --yLocal) {
                int worldY = sectionWorldFloor + yLocal;

                for (int w = 0; w < 4; ++w) {
                    long bitsRow = pickRow(w, r0, r1, r2, r3);
                    if (bitsRow == 0) continue;

                    long clearMask = 0L;
                    long itr = bitsRow;
                    while (itr != 0) {
                        int bitOff = Long.numberOfTrailingZeros(itr);
                        itr &= itr - 1;
                        int column = w * 64 + bitOff;
                        int x = column & 0xF;
                        int z = (column >> 4) & 0xF;
                        int storageIndex = (yLocal * 16 + z) * 16 + x;
                        int longIndex = storageIndex / epl;
                        int bitOffset = (storageIndex % epl) * bits;
                        int palIdx = (int) ((storage[longIndex] >>> bitOffset) & mask);
                        if (maskBit(passingMasksFlat, maskBase, maskLongsPerSection, palIdx)) {
                            out[column] = worldY;
                            clearMask |= 1L << bitOff;
                            --remainingCount;
                        }
                    }
                    if (clearMask != 0) {
                        switch (w) {
                            case 0 -> r0 &= ~clearMask;
                            case 1 -> r1 &= ~clearMask;
                            case 2 -> r2 &= ~clearMask;
                            case 3 -> r3 &= ~clearMask;
                        }
                    }
                }
                if (remainingCount == 0) break;
            }
            if (remainingCount == 0) break;
        }
    }

    private static long pickRow(int w, long r0, long r1, long r2, long r3) {
        return switch (w) {
            case 0 -> r0;
            case 1 -> r1;
            case 2 -> r2;
            case 3 -> r3;
            default -> 0L;
        };
    }

    private static boolean maskAny(long[] passingMasksFlat, int maskBase, int maskLongsPerSection) {
        for (int i = 0; i < maskLongsPerSection; ++i) {
            if (passingMasksFlat[maskBase + i] != 0L) return true;
        }
        return false;
    }

    private static boolean maskBit(long[] passingMasksFlat, int maskBase, int maskLongsPerSection, int paletteIndex) {
        int word = paletteIndex >>> 6;
        if (paletteIndex < 0 || word >= maskLongsPerSection) return false;
        long maskWord = passingMasksFlat[maskBase + word];
        return ((maskWord >>> (paletteIndex & 63)) & 1L) != 0L;
    }

    public static void buildPassingMask(int paletteSize, IntPredicate predicate, long[] outMask) {
        if (outMask == null || outMask.length < maskLongsPerSection(paletteSize)) {
            throw new IllegalArgumentException("outMask too short");
        }
        if (predicate == null) throw new IllegalArgumentException("null predicate");
        Arrays.fill(outMask, 0L);
        for (int i = 0; i < paletteSize; ++i) {
            if (predicate.test(i)) {
                outMask[i >>> 6] |= 1L << (i & 63);
            }
        }
    }

    private static native void nativePopulateHeightmap(
            long[][] storages,
            int[] elementBits,
            long[] passingMasksFlat,
            int maskLongsPerSection,
            int sectionCount,
            int sectionBaseY,
            int defaultHeight,
            int[] out);
}
