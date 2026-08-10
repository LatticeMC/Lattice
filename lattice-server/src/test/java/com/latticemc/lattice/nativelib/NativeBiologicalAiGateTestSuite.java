package com.latticemc.lattice.nativelib;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class NativeBiologicalAiGateTestSuite {
    @Test
    void ordinaryStimulusCountsStayOnJava() {
        assertFalse(NativeBiologicalAi.shouldUseNative(0, Integer.MAX_VALUE));
        assertFalse(NativeBiologicalAi.shouldUseNative(512, Integer.MAX_VALUE));
        assertFalse(NativeBiologicalAi.shouldUseNative(7, 8));
        assertTrue(NativeBiologicalAi.shouldUseNative(8, 8));
        assertTrue(NativeBiologicalAi.shouldUseNative(0, 0));
    }
}
