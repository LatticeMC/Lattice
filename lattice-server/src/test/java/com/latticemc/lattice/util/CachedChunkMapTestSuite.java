package com.latticemc.lattice.util;

import static org.junit.jupiter.api.Assertions.*;
import ca.spottedleaf.concurrentutil.map.ConcurrentLong2ReferenceChainedHashTable;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class CachedChunkMapTestSuite {
    @Test void mutationsAndReturnsMatchOriginalIncludingResize() {
        var original = new ConcurrentLong2ReferenceChainedHashTable<Object>();
        var cached = new CachedChunkMap<Object>(Thread.currentThread());
        Object a = new Object(), b = new Object(), c = new Object();
        List<Function<ConcurrentLong2ReferenceChainedHashTable<Object>, Object>> operations = List.of(
            m -> m.put(1, a), m -> m.put(1, b), m -> m.putIfAbsent(1, c), m -> m.putIfAbsent(-1, a),
            m -> m.replace(1, c), m -> m.replace(1, a, b), m -> m.replace(1, c, b),
            m -> m.remove(1, a), m -> m.remove(1, b), m -> m.remove(-1),
            m -> m.computeIfAbsent(2, k -> a), m -> m.computeIfPresent(2, (k,v) -> b),
            m -> m.compute(2, (k,v) -> { assertSame(v, m.get(k)); return c; }),
            m -> m.merge(2, a, (x,y) -> { assertSame(x, m.get(2)); return b; }),
            m -> m.removeIf(2, v -> { assertSame(v, m.get(2)); return false; }),
            m -> m.removeIf(2, v -> { assertSame(v, m.get(2)); return true; }),
            m -> m.compute(3, (k,v) -> a), m -> m.compute(3, (k,v) -> null),
            m -> { m.clear(); return null; }
        );
        for (var operation : operations) {
            for (long k = -1; k <= 3; k++) assertSame(original.get(k), cached.get(k));
            assertSame(operation.apply(original), operation.apply(cached));
            for (long k = -1; k <= 3; k++) assertSame(original.get(k), cached.get(k));
            assertEquals(original.size(), cached.size());
        }
        for (int i = 0; i < 4000; i++) { Object v = new Object(); original.put(i, v); cached.put(i, v); }
        for (int i = 0; i < 4000; i++) assertSame(original.get(i), cached.get(i));
        for (int i = 0; i < 4000; i += 2) assertSame(original.remove(i), cached.remove(i));
        for (int i = 0; i < 4000; i++) assertSame(original.get(i), cached.get(i));
    }

    @Test void failingReentrantCallbackDoesNotLeaveStaleCache() {
        var map = new CachedChunkMap<Object>(Thread.currentThread()); Object a = new Object(); map.put(1, a); map.get(1);
        RuntimeException failure = new RuntimeException("expected callback failure");
        assertSame(failure, assertThrows(RuntimeException.class, () -> map.compute(1, (k,v) -> {
            assertSame(a, map.get(k)); throw failure;
        })));
        assertSame(a, map.get(1)); Object b = new Object(); map.replace(1, b); assertSame(b, map.get(1));
    }

    @Test void iteratorRemovalsInvalidateCachedEntries() {
        for (int kind = 0; kind < 3; kind++) {
            var map = new CachedChunkMap<Object>(Thread.currentThread()); Object value = new Object();
            map.put(0, value); assertSame(value, map.get(0));
            var iterator = kind == 0 ? map.iterator() : kind == 1 ? map.valueIterator() : map.keyIterator();
            iterator.next(); iterator.remove(); assertNull(map.get(0)); assertEquals(0, map.size());
        }
    }

    @Test void asynchronousMutationsRetainPublicMapContract() throws Exception {
        var map = new CachedChunkMap<Object>(Thread.currentThread()); Object a = new Object(), b = new Object();
        map.put(1, a); assertSame(a, map.get(1));
        ChunkCacheTestSuite.runWorker(() -> { assertSame(a, map.replace(1, b)); assertSame(b, map.get(1)); });
        assertSame(b, map.get(1));
        ChunkCacheTestSuite.runWorker(map::clear); assertNull(map.get(1));
    }

    @Test void dependencyMutationMethodsRemainOverridden() throws Exception {
        var mutators = List.of("put", "putIfAbsent", "replace", "remove", "removeIf", "compute", "computeIfAbsent", "computeIfPresent", "merge", "clear");
        int count = 0;
        for (var method : ConcurrentLong2ReferenceChainedHashTable.class.getDeclaredMethods()) {
            if (!Modifier.isPublic(method.getModifiers()) || !mutators.contains(method.getName())) continue;
            assertNotNull(CachedChunkMap.class.getDeclaredMethod(method.getName(), method.getParameterTypes())); count++;
        }
        assertEquals(12, count);
    }
}
