package com.latticemc.lattice.bridge;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkSource;
import org.junit.jupiter.api.Test;

class PathfinderMirrorWorldTestSuite {
    @Test
    void lazyRegistrationAndIdempotentClose() {
        AtomicInteger creates = new AtomicInteger(), retires = new AtomicInteger();
        PathfinderMirrorWorld world = owner(creates, retires);
        assertEquals(0, world.existingGeneration());
        assertEquals(0, creates.get());
        assertEquals(1L << 40, world.getOrCreate());
        assertEquals(1L << 40, world.getOrCreate());
        world.close();
        world.close();
        assertEquals(0, world.getOrCreate());
        assertEquals(0, world.existingGeneration());
        assertEquals(1, creates.get());
        assertEquals(1, retires.get());
    }

    @Test
    void closingUnusedOwnerDoesNotRegister() {
        AtomicInteger creates = new AtomicInteger(), retires = new AtomicInteger();
        PathfinderMirrorWorld world = owner(creates, retires);
        world.close();
        assertEquals(0, world.getOrCreate());
        assertEquals(0, creates.get());
        assertEquals(0, retires.get());
    }

    @Test
    void createAndCloseRaceCannotLeakOrReopen() throws Exception {
        AtomicInteger creates = new AtomicInteger(), retires = new AtomicInteger();
        PathfinderMirrorWorld world = owner(creates, retires);
        CountDownLatch start = new CountDownLatch(1);
        Thread[] threads = new Thread[16];
        for (int i = 0; i < threads.length; i++) {
            boolean close = i % 2 == 0;
            threads[i] = new Thread(() -> {
                try { start.await(); } catch (InterruptedException e) { throw new AssertionError(e); }
                if (close) world.close(); else world.getOrCreate();
            });
            threads[i].start();
        }
        start.countDown();
        for (Thread thread : threads) thread.join();
        assertTrue(creates.get() <= 1);
        assertEquals(creates.get(), retires.get());
        assertEquals(0, world.getOrCreate());
    }

    @Test
    void retirementFailureStillClosesOwner() {
        PathfinderMirrorWorld world = new PathfinderMirrorWorld(new PathfinderMirrorWorld.Backend() {
            public long create() { return 9; }
            public void retire(long generation) { throw new IllegalStateException("retire failed"); }
        });
        world.getOrCreate();
        assertEquals("retire failed", assertThrows(IllegalStateException.class, world::close).getMessage());
        world.close();
        assertEquals(0, world.getOrCreate());
    }

    @Test
    void levelCloseRetiresEvenWhenChunkCloseFails() throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        AtomicInteger creates = new AtomicInteger(), retires = new AtomicInteger();
        PathfinderMirrorWorld owner = owner(creates, retires);
        owner.getOrCreate();
        Level level = mock(Level.class);
        ChunkSource chunks = mock(ChunkSource.class);
        when(level.getChunkSource()).thenReturn(chunks);
        doThrow(new IOException("chunk close failed")).when(chunks).close();
        doCallRealMethod().when(level).close();
        Field field = Level.class.getDeclaredField("lattice$pathfinderMirrorWorld");
        field.setAccessible(true);
        field.set(level, owner);
        assertEquals("chunk close failed", assertThrows(IOException.class, level::close).getMessage());
        assertEquals(1, retires.get());
        assertEquals(0, owner.getOrCreate());
    }

    private static PathfinderMirrorWorld owner(AtomicInteger creates, AtomicInteger retires) {
        return new PathfinderMirrorWorld(new PathfinderMirrorWorld.Backend() {
            public long create() { creates.incrementAndGet(); return 1L << 40; }
            public void retire(long generation) {
                assertEquals(1L << 40, generation);
                retires.incrementAndGet();
            }
        });
    }
}
