package com.latticemc.lattice.nativelib;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

class NativeDensityExecutionStatsTestSuite {
    @Test
    void preservesNodeOffsetsAndAppendsProgramCountersForEachKnownLayout() {
        for (int length : new int[] {83, 85, 88}) {
            long[] input = new long[length];
            int nodeOffset = length == 83 ? 11 : 13;
            input[0] = 7;
            input[6] = 3;
            for (int node = 0; node < 72; node++) input[nodeOffset + node] = 100 + node;
            if (length >= 85) {
                input[11] = 13;
                input[12] = 17;
            }
            if (length == 88) {
                input[85] = 19;
                input[86] = 23;
                input[87] = 29;
            }
            long[] result = NativeDensityFunction.normalizeExecutionStats(input);
            assertNotNull(result);
            assertEquals(88, result.length);
            assertEquals(7, result[0]);
            assertEquals(3, result[6]);
            for (int node = 0; node < 72; node++) assertEquals(100 + node, result[13 + node]);
            assertEquals(length >= 85 ? 13 : 0, result[11]);
            assertEquals(length >= 85 ? 17 : 0, result[12]);
            assertEquals(length == 88 ? 19 : 0, result[85]);
            assertEquals(length == 88 ? 23 : 0, result[86]);
            assertEquals(length == 88 ? 29 : 0, result[87]);
        }
    }

    @Test
    void rejectsUnknownLayoutsAndRetainsSegmentedCounterNormalization() {
        assertNull(NativeDensityFunction.normalizeExecutionStats(null));
        for (int length : new int[] {0, 82, 84, 86, 87, 89}) {
            assertNull(NativeDensityFunction.normalizeExecutionStats(new long[length]));
        }
        long[] input = new long[85];
        input[11] = -1;
        input[12] = Long.MIN_VALUE;
        long[] result = NativeDensityFunction.normalizeExecutionStats(input);
        assertNotNull(result);
        assertEquals(0, result[11]);
        assertEquals(0, result[12]);
    }
}
