package com.latticemc.lattice.util;

import static org.junit.jupiter.api.Assertions.*;
import ca.spottedleaf.concurrentutil.map.ConcurrentLong2ReferenceChainedHashTable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ChunkCacheTestSuite {
    @Test void cachesOnlyPositiveValuesAndHandlesAllLongKeys() {
        var source = new ConcurrentLong2ReferenceChainedHashTable<Object>();
        AtomicInteger reads = new AtomicInteger();
        var cache = new ChunkCache<Object>(Thread.currentThread(), key -> { reads.incrementAndGet(); return source.get(key); });
        for (long key : new long[] {0, Long.MIN_VALUE, -1, Long.MAX_VALUE}) {
            assertNull(cache.get(key));
            Object value = new Object(); source.put(key, value);
            assertSame(value, cache.get(key)); assertSame(value, cache.get(key));
        }
        assertEquals(8, reads.get());
    }

    @Test void ownerRemovalAndRecreationDoNotReturnOldHolder() {
        var source = new ConcurrentLong2ReferenceChainedHashTable<Object>();
        var cache = new ChunkCache<Object>(Thread.currentThread(), source::get);
        Object first = new Object(), second = new Object(); source.put(4, first);
        assertSame(first, cache.get(4));
        cache.invalidate(4); source.remove(4);
        assertNull(cache.get(4)); source.put(4, second);
        assertSame(second, cache.get(4));
        cache.invalidateAll(); source.clear(); assertNull(cache.get(4));
    }

    @Test void asynchronousInsertionIsVisibleAfterAMissAndReadersDoNotFillCache() throws Exception {
        var source = new ConcurrentLong2ReferenceChainedHashTable<Object>();
        AtomicInteger reads = new AtomicInteger();
        var cache = new ChunkCache<Object>(Thread.currentThread(), k -> { reads.incrementAndGet(); return source.get(k); });
        assertNull(cache.get(1)); Object value = new Object();
        runWorker(() -> { source.put(1, value); assertSame(value, cache.get(1)); assertSame(value, cache.get(1)); });
        assertSame(value, cache.get(1)); assertSame(value, cache.get(1)); assertEquals(4, reads.get());
    }

    @Test void foreignInvalidationPermanentlyDisablesAllEntries() throws Exception {
        var source = new ConcurrentLong2ReferenceChainedHashTable<Object>();
        var cache = new ChunkCache<Object>(Thread.currentThread(), source::get);
        Object first = new Object(), second = new Object(); source.put(1, first); source.put(2, first);
        assertSame(first, cache.get(1)); assertSame(first, cache.get(2));
        runWorker(() -> { cache.invalidate(1); source.remove(1); });
        assertNull(cache.get(1));
        source.put(2, second); assertSame(second, cache.get(2));
        cache.invalidateAll(); source.put(2, first); assertSame(first, cache.get(2));
    }

    @Test void racingFillCannotReactivateDisabledCache() throws Exception {
        var source = new ConcurrentLong2ReferenceChainedHashTable<Object>();
        Object first = new Object(); source.put(1, first);
        CountDownLatch captured = new CountDownLatch(1), resume = new CountDownLatch(1);
        AtomicReference<ChunkCache<Object>> published = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread owner = new Thread(() -> {
            try {
                var cache = new ChunkCache<Object>(Thread.currentThread(), key -> {
                    Object value = source.get(key);
                    if (value != null) { captured.countDown(); await(resume); }
                    return value;
                });
                published.set(cache);
                assertSame(first, cache.get(1)); // 与删除重叠，允许返回旧实例。
                assertNull(cache.get(1));
            } catch (Throwable ex) { failure.set(ex); }
        });
        owner.start();
        try { assertTrue(captured.await(5, TimeUnit.SECONDS)); published.get().invalidate(1); source.remove(1); }
        finally { resume.countDown(); owner.join(5000); }
        assertFalse(owner.isAlive()); if (failure.get() != null) throw new AssertionError(failure.get());
    }

    static void runWorker(Runnable action) throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> { try { action.run(); } catch (Throwable t) { failure.set(t); } });
        worker.start(); worker.join(5000); assertFalse(worker.isAlive());
        if (failure.get() != null) throw new AssertionError(failure.get());
    }
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("Latch timeout"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }
}
