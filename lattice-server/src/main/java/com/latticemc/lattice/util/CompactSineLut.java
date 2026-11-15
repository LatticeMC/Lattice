/*
 * SPDX-License-Identifier: LGPL-3.0-only
 * Adapted from Lithium's CompactSineLUT (CaffeineMC).
 * Original algorithm: coderbot16; Java port and optimizations: jellysquid3.
 * https://github.com/CaffeineMC/lithium/blob/1.21.11/common/src/main/java/net/caffeinemc/mods/lithium/common/util/math/CompactSineLUT.java
 * https://gitlab.com/coderbot16/i73/-/tree/master/i73-trig/src
 */
package com.latticemc.lattice.util;

public final class CompactSineLut {
    private static final double INDEX_SCALE = 10430.378350470453;
    private static final int[] TABLE = new int[16385];
    private static final float MIDPOINT = original(32768);

    static {
        for (int index = 0; index < TABLE.length; index++) {
            TABLE[index] = Float.floatToRawIntBits(original(index));
        }
        // Validate raw bits, including signed zero, without retaining the full table.
        for (int index = 0; index < 65536; index++) {
            if (Float.floatToRawIntBits(original(index)) != Float.floatToRawIntBits(lookup(index))) {
                throw new IllegalStateException("Compact sine LUT mismatch at index " + index);
            }
        }
    }

    private CompactSineLut() {
    }

    private static float original(int index) {
        return (float)Math.sin(index / INDEX_SCALE);
    }

    public static float sin(double value) {
        return lookup((int)((long)(value * INDEX_SCALE) & 65535L));
    }

    public static float cos(double value) {
        return lookup((int)((long)(value * INDEX_SCALE + 16384.0) & 65535L));
    }

    static float lookup(int index) {
        if (index == 32768) {
            return MIDPOINT;
        }
        int negative = (index & 0x8000) << 16;
        int mask = (index << 17) >> 31;
        int position = ((0x8001 & mask) + (index ^ mask)) & 0x7fff;
        return Float.intBitsToFloat(TABLE[position] ^ negative);
    }
}
