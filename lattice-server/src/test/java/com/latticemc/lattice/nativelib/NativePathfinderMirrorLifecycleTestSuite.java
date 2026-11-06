package com.latticemc.lattice.nativelib;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import net.minecraft.world.level.pathfinder.PathType;
import org.junit.jupiter.api.Test;

class NativePathfinderMirrorLifecycleTestSuite {
    @Test
    void rawEmptySearchRetainsNonNullResultContract() {
        assertTrue(NativePathfinder.isAvailable());
        NativePathfinder.PathResult result = NativePathfinder.findPath(new byte[] {0}, null,
                0, 0, 0, 1, 1, 1, 0, 0, 0,
                new int[] {0}, new int[] {0}, new int[] {0}, 1,
                16, 16, 0, 1, 1, 1, 3, NativePathfinder.pathfindingMalusFor(PathType.values()),
                1.125F, 0.6F, false, false, false, false, -64, new int[51]);
        assertNotNull(result);
        assertEquals(0, result.length());
        assertFalse(result.reachedTarget());
    }

    @Test
    void churnDoesNotResurrectOrTruncateGenerations() {
        assertTrue(NativePathfinder.isAvailable());
        long active = NativePathfinder.mirrorLifecycleDiagnostics()[0];
        long previous = 0;
        for (int i = 0; i < 200; i++) {
            long generation = NativePathfinder.createMirrorWorld();
            assertTrue(generation > previous);
            previous = generation;
            try {
                assertNotNull(upload(generation));
                assertTrue(covered(generation));
                long alias = generation + (1L << 32);
                assertFalse(covered(alias));
                assertNull(upload(alias));
                assertNull(search(alias));
                NativePathfinder.invalidateStateMirror(alias, 0, 0, 0);
                assertTrue(covered(generation));
            } finally {
                NativePathfinder.retireMirrorWorld(generation);
            }
            NativePathfinder.retireMirrorWorld(generation);
            assertFalse(covered(generation));
            assertNull(upload(generation));
            assertNull(search(generation));
            NativePathfinder.invalidateStateMirror(generation, 0, 0, 0);
            long[] diagnostics = NativePathfinder.mirrorLifecycleDiagnostics();
            assertEquals(active, diagnostics[0]);
            assertEquals(0, diagnostics[1]);
            assertEquals(0, diagnostics[2]);
            assertEquals(0, diagnostics[3]);
        }
    }

    @Test
    void workersReplayInvalidationAndReleaseOnNextAccess() throws Exception {
        long active = NativePathfinder.mirrorLifecycleDiagnostics()[0];
        long generation = NativePathfinder.createMirrorWorld();
        CountDownLatch uploaded = new CountDownLatch(2), invalidated = new CountDownLatch(1);
        CountDownLatch reuploaded = new CountDownLatch(2), retired = new CountDownLatch(1);
        FutureTask<Void>[] tasks = new FutureTask[2];
        for (int i = 0; i < tasks.length; i++) {
            tasks[i] = new FutureTask<>(() -> {
                assertNotNull(upload(generation));
                uploaded.countDown();
                invalidated.await();
                assertFalse(covered(generation));
                assertNotNull(upload(generation));
                assertTrue(NativePathfinder.mirrorLifecycleDiagnostics()[2] > 0);
                reuploaded.countDown();
                retired.await();
                assertFalse(covered(generation));
                long[] diagnostics = NativePathfinder.mirrorLifecycleDiagnostics();
                assertEquals(0, diagnostics[1]);
                assertEquals(0, diagnostics[2]);
                assertEquals(0, diagnostics[3]);
                return null;
            });
            new Thread(tasks[i], "mirror-lifecycle-" + i).start();
        }
        try {
            assertTrue(uploaded.await(10, java.util.concurrent.TimeUnit.SECONDS));
            NativePathfinder.invalidateStateMirror(generation, 0, 0, 0);
            invalidated.countDown();
            assertTrue(reuploaded.await(10, java.util.concurrent.TimeUnit.SECONDS));
        } finally {
            NativePathfinder.retireMirrorWorld(generation);
            invalidated.countDown();
            retired.countDown();
        }
        for (FutureTask<Void> task : tasks) task.get(10, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals(active, NativePathfinder.mirrorLifecycleDiagnostics()[0]);
    }

    @Test
    void disablingSearchStillAllowsRetirement() {
        long active = NativePathfinder.mirrorLifecycleDiagnostics()[0];
        long generation = NativePathfinder.createMirrorWorld();
        NativePathfinder.setEnabled(false);
        try { NativePathfinder.retireMirrorWorld(generation); }
        finally { NativePathfinder.setEnabled(true); }
        assertEquals(active, NativePathfinder.mirrorLifecycleDiagnostics()[0]);
    }

    private static boolean covered(long generation) {
        return NativePathfinder.stateMirrorCovers(generation, -1, -1, -1, 3, 3, 3);
    }

    private static NativePathfinder.PathResult upload(long generation) {
        int[] cells = new int[27];
        Arrays.fill(cells, 9, 18, 1);
        Arrays.fill(cells, 18, 27, 2);
        return NativePathfinder.findPathFromStateSnapshot(cells,
                new byte[] {0, 2, 1}, new float[] {1, 0, 0}, 3,
                -1, -1, -1, 3, 3, 3, 0, 0, 0, 1, 1, 1, 0, 0, 0,
                new int[] {0}, new int[] {0}, new int[] {0}, 1,
                16, 16, 0, 1, 1, 1, 3, NativePathfinder.pathfindingMalusFor(PathType.values()),
                1.125F, 0.6F, true, false, 0, 0, 0, false, false, false, false, -64,
                generation, new int[51]);
    }

    private static NativePathfinder.PathResult search(long generation) {
        return NativePathfinder.findPathFromStateMirror(generation, 0, 0, 0, 1, 1, 1, 0, 0, 0,
                new int[] {0}, new int[] {0}, new int[] {0}, 1,
                16, 16, 0, 1, 1, 1, 3, NativePathfinder.pathfindingMalusFor(PathType.values()),
                1.125F, 0.6F, true, false, 0, 0, 0, false, false, false, false, -64, new int[51]);
    }
}
