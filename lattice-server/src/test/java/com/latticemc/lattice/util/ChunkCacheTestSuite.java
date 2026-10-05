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

    static void runWorker(Runnable action) throws Exception {
        var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        Thread worker = new Thread(() -> { try { action.run(); } catch (Throwable t) { failure.set(t); } });
        worker.start();
        worker.join(5000);
        assertFalse(worker.isAlive());
        if (failure.get() != null) throw new AssertionError(failure.get());
    }
}
