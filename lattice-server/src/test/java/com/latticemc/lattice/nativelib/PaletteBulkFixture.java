package com.latticemc.lattice.nativelib;

import java.util.Arrays;
import java.util.Random;
import net.minecraft.util.SimpleBitStorage;

final class PaletteBulkFixture {
    static final int SENTINEL = 0x5a5a5a5a;
    final int bits;
    final int[] values;
    final SimpleBitStorage storage;

    PaletteBulkFixture(int bits, int size) {
        this.bits = bits;
        values = new int[size];
        Random random = new Random(0x50414c45545445L + bits * 65537L + size);
        long mask = (1L << bits) - 1L;
        for (int i = 0; i < size; i++) values[i] = (int) (random.nextLong() & mask);
        if (size > 0) values[0] = 0;
        if (size > 1) values[size - 1] = (int) mask;
        storage = new SimpleBitStorage(bits, size, values);
    }

    void assertWindow(int[] output, int start, int offset, int count) {
        for (int i = 0; i < output.length; i++) {
            int expected = i >= offset && i < offset + count ? values[start + i - offset] : SENTINEL;
            if (output[i] != expected) {
                throw new AssertionError("bits=" + bits + " start=" + start + " offset=" + offset
                        + " count=" + count + " i=" + i + " expected=" + expected + " actual=" + output[i]);
            }
        }
    }

    static int[] output(int size) {
        int[] result = new int[size];
        Arrays.fill(result, SENTINEL);
        return result;
    }

    // Frozen SimpleBitStorage.unpack fallback body, including the final partial word.
    void javaUnpack(int[] output) {
        long[] data = storage.getRaw();
        int valuesPerLong = 64 / bits;
        long mask = (1L << bits) - 1L;
        int words = data.length;
        int index = 0;
        for (int word = 0; word < words - 1; word++) {
            long value = data[word];
            for (int j = 0; j < valuesPerLong; j++) {
                output[index + j] = (int) (value & mask);
                value >>= bits;
            }
            index += valuesPerLong;
        }
        int remaining = values.length - index;
        if (remaining > 0) {
            long value = data[words - 1];
            for (int j = 0; j < remaining; j++) {
                output[index + j] = (int) (value & mask);
                value >>= bits;
            }
        }
    }
}
