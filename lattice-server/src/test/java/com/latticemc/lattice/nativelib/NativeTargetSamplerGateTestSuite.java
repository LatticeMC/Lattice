package com.latticemc.lattice.nativelib;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class NativeTargetSamplerGateTestSuite {
    @Test
    void rejectsNegativeSizes() {
        assertThrows(IllegalArgumentException.class, () -> NativeTargetSamplerGate.shouldUseNative(-1, 0));
        assertThrows(IllegalArgumentException.class, () -> NativeTargetSamplerGate.shouldUseNative(0, -1));
    }

    @Test
    void defaultGateDoesNotForceNative() {
        if (Integer.getInteger("lattice.nativeTargetSampler.minWork", Integer.MAX_VALUE) == Integer.MAX_VALUE) {
            assertFalse(NativeTargetSamplerGate.shouldUseNative(256, 256));
            assertFalse(NativeTargetSamplerGate.shouldUseNative(256));
        } else {
            assertTrue(NativeTargetSamplerGate.shouldUseNative(0, 0)
                    == (Integer.getInteger("lattice.nativeTargetSampler.minWork") <= 0));
        }
    }
}
