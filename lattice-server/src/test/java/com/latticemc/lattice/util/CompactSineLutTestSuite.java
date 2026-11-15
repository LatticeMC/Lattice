package com.latticemc.lattice.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Random;
import net.minecraft.util.Mth;
import org.junit.jupiter.api.Test;

class CompactSineLutTestSuite {
    private static final double SCALE = 10430.378350470453;

    @Test
    void everyTableEntryMatchesOriginalRawBits() {
        for (int index = 0; index < 65536; index++) {
            assertBits((float)Math.sin(index / SCALE), CompactSineLut.lookup(index));
        }
    }

    @Test
    void doubleEntrypointsKeepLongConversionIncludingSaturationAndNan() {
        double[] special = {0.0, -0.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
            Double.MIN_VALUE, -Double.MIN_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE,
            Integer.MAX_VALUE / SCALE, Integer.MIN_VALUE / SCALE,
            Long.MAX_VALUE / SCALE, Long.MIN_VALUE / SCALE};
        for (double value : special) {
            check(value);
            check(Math.nextDown(value));
            check(Math.nextUp(value));
        }
        for (int index = -65536; index <= 65536; index++) {
            double value = index / SCALE;
            check(value);
            check(Math.nextDown(value));
            check(Math.nextUp(value));
        }
        Random random = new Random(0x51ae);
        for (int index = 0; index < 100000; index++) {
            check(Double.longBitsToDouble(random.nextLong()));
        }
    }

    private static void check(double value) {
        int sinIndex = (int)((long)(value * SCALE) & 65535L);
        int cosIndex = (int)((long)(value * SCALE + 16384.0) & 65535L);
        assertBits((float)Math.sin(sinIndex / SCALE), Mth.sin(value));
        assertBits((float)Math.sin(cosIndex / SCALE), Mth.cos(value));
    }

    private static void assertBits(float expected, float actual) {
        assertEquals(Float.floatToRawIntBits(expected), Float.floatToRawIntBits(actual));
    }
}
