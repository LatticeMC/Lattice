package com.latticemc.lattice.util;

import static org.junit.jupiter.api.Assertions.*;

import ca.spottedleaf.concurrentutil.map.ConcurrentLong2ReferenceChainedHashTable;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ChunkCacheTestSuite {
    @Test
    void cachesOnlyDuringOwnerTickAndSupportsAllLongKeys() {
        var source = new ConcurrentLong2ReferenceChainedHashTable<Object>();
        AtomicInteger reads = new AtomicInteger();
        var cache = new ChunkCache<Object>(Thread.currentThread(), key -> { reads.incrementAndGet(); return source.get(key); });
        cache.beginTick();
        for (long key : new long[] {0, Long.MIN_VALUE, -1, Long.MAX_VALUE}) {
            Object value = new Object();
            source.put(key, value);
            assertSame(value, cache.get(key));
            assertSame(value, cache.get(key));
        }
        assertEquals(4, reads.get());
        cache.endTick();
        source.remove(0);
        assertNull(cache.get(0));
    }

    @Test
    void tickGenerationDropsRemovedAndRecreatedEntries() {
        var source = new ConcurrentLong2ReferenceChainedHashTable<Object>();
        var cache = new ChunkCache<Object>(Thread.currentThread(), source::get);
        Object first = new Object(), second = new Object();
        source.put(4, first);
        cache.beginTick();
        assertSame(first, cache.get(4));
        cache.endTick();
        source.remove(4);
        source.put(4, second);
        cache.beginTick();
        assertSame(second, cache.get(4));
        cache.endTick();
    }

    @Test
    void foreignReadersAlwaysUseAuthoritativeSource() throws Exception {
        var source = new ConcurrentLong2ReferenceChainedHashTable<Object>();
        AtomicInteger reads = new AtomicInteger();
        var cache = new ChunkCache<Object>(Thread.currentThread(), key -> { reads.incrementAndGet(); return source.get(key); });
        Object value = new Object();
        source.put(1, value);
        cache.beginTick();
        assertSame(value, cache.get(1));
        ChunkCacheTestSuite.runWorker(() -> {
            assertSame(value, cache.get(1));
            assertSame(value, cache.get(1));
        });
        assertEquals(3, reads.get());
        cache.endTick();
    }

    @Test
    void ownerMutationInvalidatesCurrentEntryAndForeignInvalidationDisablesCache() throws Exception {
        var source = new ConcurrentLong2ReferenceChainedHashTable<Object>();
        var cache = new ChunkCache<Object>(Thread.currentThread(), source::get);
        Object first = new Object(), second = new Object();
        source.put(1, first);
        cache.beginTick();
        assertSame(first, cache.get(1));
        cache.invalidate(1);
        source.put(1, second);
        assertSame(second, cache.get(1));
        ChunkCacheTestSuite.runWorker(() -> cache.invalidateAll());
        source.put(1, first);
        assertSame(first, cache.get(1));
        cache.endTick();
    }

    @Test
    void singleKeyInvalidationAdvancesGenerationToPreserveProbeChains() {
        var source = new ConcurrentLong2ReferenceChainedHashTable<Object>();
        AtomicInteger reads = new AtomicInteger();
        var cache = new ChunkCache<Object>(Thread.currentThread(), key -> {
            reads.incrementAndGet();
            return source.get(key);
        });
        source.put(1, new Object());
        Object second = new Object();
        source.put(2, second);
        cache.beginTick();
        assertNotNull(cache.get(1));
        assertSame(second, cache.get(2));
        assertEquals(2, reads.get());

        cache.invalidate(1);

        assertSame(second, cache.get(2));
        assertEquals(3, reads.get());
        cache.endTick();
    }

    static void runWorker(Runnable action) throws Exception {
        var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        Thread worker = new Thread(() -> { try { action.run(); } catch (Throwable t) { failure.set(t); } });
        worker.start();
        worker.join(5000);
        assertFalse(worker.isAlive());
        if (failure.get() != null) throw new AssertionError(failure.get());
    }

    @Test
    void foreignInvalidationCannotBeLostAcrossWindowBoundary() throws Exception {
        var source = new ConcurrentLong2ReferenceChainedHashTable<Object>();
        var cache = new ChunkCache<Object>(Thread.currentThread(), source::get);
        Object old = new Object();
        source.put(1, old);
        cache.beginTick();
        assertSame(old, cache.get(1));
        runWorker(() -> cache.invalidate(1));
        cache.endTick();
        cache.beginTick();
        assertSame(old, cache.get(1)); // Source mutation is still in flight.
        runWorker(() -> source.remove(1));
        assertNull(cache.get(1));
        cache.endTick();
    }

    @Test
    void endingWindowReleasesValuesFromAllGenerations() throws Exception {
        Object value = new Object();
        var cache = new ChunkCache<Object>(Thread.currentThread(), key -> value);
        cache.beginTick();
        for (int key = 0; key < 2048; key++) cache.get(key);
        cache.invalidateAll();
        cache.get(-1);
        cache.endTick();
        var field = ChunkCache.class.getDeclaredField("values");
        field.setAccessible(true);
        for (Object entry : (Object[]) field.get(cache)) assertNull(entry);
    }

    @Test
    void rehashAndGenerationWrapPreserveLookups() throws Exception {
        var source = new ConcurrentLong2ReferenceChainedHashTable<Object>();
        var cache = new ChunkCache<Object>(Thread.currentThread(), source::get);
        cache.beginTick();
        for (int key = 0; key < 4096; key++) source.put(key, new Object());
        for (int key = 0; key < 4096; key++) assertSame(source.get(key), cache.get(key));
        for (int key = 4095; key >= 0; key--) assertSame(source.get(key), cache.get(key));
        cache.endTick();
        var field = ChunkCache.class.getDeclaredField("generation");
        field.setAccessible(true);
        field.setInt(cache, -1);
        source.remove(0);
        cache.beginTick();
        assertNull(cache.get(0));
        assertSame(source.get(4095), cache.get(4095));
        cache.endTick();
    }
}
