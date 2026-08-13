package com.latticemc.lattice.nativelib;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class NativeIoCapabilityTestSuite {
    @Test
    void selectsOnlyTheNativeBackendForEachSupportedPlatform() {
        assertState("Windows 11", NativeIoCapability.Backend.IOCP, true,
                NativeIoCapability.resolve("Windows 11", true, true, true));
        assertState("Linux", NativeIoCapability.Backend.IO_URING, true,
                NativeIoCapability.resolve("Linux", true, true, true));
        assertState("Darwin", NativeIoCapability.Backend.KQUEUE, true,
                NativeIoCapability.resolve("Darwin", true, true, true));
    }

    @Test
    void ignoresARequestedBackendThatDoesNotMatchThePlatform() {
        assertState("Linux", NativeIoCapability.Backend.IO_URING, false,
                NativeIoCapability.resolve("Linux", true, false, true));
        assertState("Plan 9", NativeIoCapability.Backend.NONE, false,
                NativeIoCapability.resolve("Plan 9", true, true, true));
    }

    @Test
    void everyCurrentBackendFallsBackUntilItsNativeImplementationExists() {
        NativeIoCapability.State state = NativeIoCapability.resolve("Windows", true, false, false);

        assertFalse(state.available());
        assertTrue(state.fallsBackToJava());
    }

    private static void assertState(String osName, NativeIoCapability.Backend backend, boolean requested,
                                    NativeIoCapability.State actual) {
        assertEquals(backend, actual.backend(), osName);
        assertEquals(requested, actual.requested(), osName);
        assertFalse(actual.available(), osName);
        assertTrue(actual.fallsBackToJava(), osName);
    }
}
