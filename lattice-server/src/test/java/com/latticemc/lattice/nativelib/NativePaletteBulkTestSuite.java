package com.latticemc.lattice.nativelib;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.Arrays;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class NativePaletteBulkTestSuite {
    @BeforeAll
    static void load() {
        LatticeNative.ensureLoaded();
        assertTrue(LatticeNative.isLoaded(), LatticeNative.failureReason());
        assertTrue(Boolean.parseBoolean(System.getProperty("lattice.nativePaletteOps", "true")));
        assertEquals(0, Integer.getInteger("lattice.nativePaletteOpsMinCount", 64),
                "Use palette-bulk.init.gradle with paletteAuditThreshold=0 for full JNI coverage");
    }

    @Test
    void unpackAndFrozenJavaMatchIndependentValuesForEveryBitWidth() {
        for (int bits = 1; bits <= 32; bits++) {
            for (int size : new int[] {0, 1, 31, 32, 63, 64, 65, 128, 255, 256, 4096}) {
                var fixture = new PaletteBulkFixture(bits, size);
                long[] original = fixture.storage.getRaw().clone();
                int[] output = PaletteBulkFixture.output(size + 3);
                fixture.storage.unpack(output);
                fixture.assertWindow(output, 0, 0, size);
                Arrays.fill(output, PaletteBulkFixture.SENTINEL);
                fixture.javaUnpack(output);
                fixture.assertWindow(output, 0, 0, size);
                assertArrayEquals(original, fixture.storage.getRaw());
            }
        }
    }

    @Test
    void unalignedWindowsOverwriteOnlyTheRequestedOutputRange() {
        for (int bits = 1; bits <= 32; bits++) {
            var fixture = new PaletteBulkFixture(bits, 4096);
            long[] original = fixture.storage.getRaw().clone();
            for (int count : new int[] {4090, 256, 65, 32, 1, 0}) {
                int[] output = PaletteBulkFixture.output(4096);
                assertTrue(NativePaletteOps.tryBulkGet(fixture.storage.getRaw(), bits, 3, output, 2, count));
                fixture.assertWindow(output, 3, 2, count);
                assertArrayEquals(original, fixture.storage.getRaw());
            }
        }
    }

    @Test
    void invalidNativeWindowsReturnFalseWithoutChangingCallerArrays() {
        var fixture = new PaletteBulkFixture(5, 128);
        long[] original = fixture.storage.getRaw().clone();
        int[] output = PaletteBulkFixture.output(128);
        assertFalse(NativePaletteOps.tryBulkGet(null, 5, 0, output, 0, 1));
        assertFalse(NativePaletteOps.tryBulkGet(original, 5, 0, null, 0, 1));
        assertFalse(NativePaletteOps.tryBulkGet(original, 0, 0, output, 0, 1));
        assertFalse(NativePaletteOps.tryBulkGet(original, 33, 0, output, 0, 1));
        assertFalse(NativePaletteOps.tryBulkGet(original, 5, -1, output, 0, 1));
        assertFalse(NativePaletteOps.tryBulkGet(original, 5, Long.MAX_VALUE, output, 0, 1));
        assertFalse(NativePaletteOps.tryBulkGet(original, 5, 0, output, -1, 1));
        assertFalse(NativePaletteOps.tryBulkGet(original, 5, 0, output, Integer.MAX_VALUE, 1));
        assertFalse(NativePaletteOps.tryBulkGet(original, 5, 0, output, 127, 2));
        assertFalse(NativePaletteOps.tryBulkGet(new long[0], 5, 0, output, 0, 1));
        fixture.assertWindow(output, 0, 0, 0);
        assertArrayEquals(original, fixture.storage.getRaw());
        assertThrows(IllegalArgumentException.class,
                () -> NativePaletteOps.bulkGet(original, 5, 0, output, 127, 2));
    }

    @Test
    void unavailableAndFailedNativeDispatchReplayTheRealJavaFallback() throws Exception {
        var fixture = new PaletteBulkFixture(5, 4096);
        int[] output = PaletteBulkFixture.output(4099);
        try (var library = mockStatic(LatticeNative.class)) {
            library.when(LatticeNative::isLoaded).thenReturn(false);
            assertFalse(NativePaletteOps.tryBulkGet(fixture.storage.getRaw(), 5, 0, output, 0, 4096));
            fixture.assertWindow(output, 0, 0, 0);
            fixture.storage.unpack(output);
            fixture.assertWindow(output, 0, 0, 4096);
        }
        // The child owns no loaded JNI library, so the real native call raises LinkageError.
        try (var child = new URLClassLoader(new URL[] {
                NativePaletteOps.class.getProtectionDomain().getCodeSource().getLocation()},
                NativePaletteOps.class.getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (!name.equals(NativePaletteOps.class.getName())) return super.loadClass(name, resolve);
                synchronized (getClassLoadingLock(name)) {
                    Class<?> result = findLoadedClass(name);
                    if (result == null) result = findClass(name);
                    if (resolve) resolveClass(result);
                    return result;
                }
            }
        }) {
            var isolated = Class.forName(NativePaletteOps.class.getName(), true, child);
            var tryBulk = isolated.getMethod("tryBulkGet",
                    long[].class, int.class, long.class, int[].class, int.class, int.class);
            Arrays.fill(output, PaletteBulkFixture.SENTINEL);
            assertEquals(false, tryBulk.invoke(null, fixture.storage.getRaw(), 5, 0L, output, 0, 4096));
            fixture.assertWindow(output, 0, 0, 0);
        }
        Arrays.fill(output, PaletteBulkFixture.SENTINEL);
        try (var palette = mockStatic(NativePaletteOps.class)) {
            palette.when(() -> NativePaletteOps.tryBulkGet(any(), anyInt(), anyLong(), any(), anyInt(), anyInt()))
                    .thenReturn(false);
            fixture.storage.unpack(output);
            fixture.assertWindow(output, 0, 0, 4096);
            palette.verify(() -> NativePaletteOps.tryBulkGet(fixture.storage.getRaw(), 5, 0L, output, 0, 4096));
        }
    }

    @Test
    void simultaneousWindowsRemainCallerOwnedAcrossThreads() throws Exception {
        try (var pool = Executors.newFixedThreadPool(4)) {
            var calls = new java.util.ArrayList<Callable<Void>>();
            for (int worker = 0; worker < 4; worker++) {
                final int bits = new int[] {1, 5, 16, 32}[worker];
                calls.add(() -> {
                    var fixture = new PaletteBulkFixture(bits, 4096);
                    long[] original = fixture.storage.getRaw().clone();
                    int[] output = new int[4100];
                    for (int round = 0; round < 100; round++) {
                        int count = round % 2 == 0 ? 4093 : 1;
                        Arrays.fill(output, PaletteBulkFixture.SENTINEL);
                        assertTrue(NativePaletteOps.tryBulkGet(original, bits, 3, output, 2, count));
                        fixture.assertWindow(output, 3, 2, count);
                    }
                    assertArrayEquals(fixture.storage.getRaw(), original);
                    return null;
                });
            }
            for (var future : pool.invokeAll(calls)) future.get();
        }
    }
}
