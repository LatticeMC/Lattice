package com.latticemc.lattice.util;

import ca.spottedleaf.concurrentutil.function.BiLong1Function;
import ca.spottedleaf.concurrentutil.map.ConcurrentLong2ReferenceChainedHashTable;
import java.util.function.BiFunction;
import java.util.function.LongFunction;
import java.util.function.Predicate;

/**
 * 保留原并发表的公开修改接口。升级 concurrentutil 时需重新检查修改入口覆盖测试。
 * 回调可重入查询，因此修改前后均失效；外线程写入永久停用 owner 缓存。
 */
public final class CachedChunkMap<V> extends ConcurrentLong2ReferenceChainedHashTable<V> {
    private final ChunkCache<V> cache;

    public CachedChunkMap(final Thread owner) {
        this.cache = new ChunkCache<>(owner, super::get);
    }

    @Override public V get(final long key) {
        return this.cache.get(key);
    }

    @Override public V put(long key, V value) {
        this.cache.invalidate(key);
        try { return super.put(key, value); }
        finally { this.cache.invalidate(key); }
    }

    @Override public V putIfAbsent(long key, V value) {
        this.cache.invalidate(key);
        try { return super.putIfAbsent(key, value); }
        finally { this.cache.invalidate(key); }
    }

    @Override public V replace(long key, V value) {
        this.cache.invalidate(key);
        try { return super.replace(key, value); }
        finally { this.cache.invalidate(key); }
    }

    @Override public V replace(long key, V expect, V update) {
        this.cache.invalidate(key);
        try { return super.replace(key, expect, update); }
        finally { this.cache.invalidate(key); }
    }

    @Override public V remove(long key) {
        this.cache.invalidate(key);
        try { return super.remove(key); }
        finally { this.cache.invalidate(key); }
    }

    @Override public V remove(long key, V expect) {
        this.cache.invalidate(key);
        try { return super.remove(key, expect); }
        finally { this.cache.invalidate(key); }
    }

    @Override public V removeIf(long key, Predicate<? super V> predicate) {
        this.cache.invalidate(key);
        try { return super.removeIf(key, predicate); }
        finally { this.cache.invalidate(key); }
    }

    @Override public V compute(long key, BiLong1Function<? super V, ? extends V> function) {
        this.cache.invalidate(key);
        try { return super.compute(key, function); }
        finally { this.cache.invalidate(key); }
    }

    @Override public V computeIfAbsent(long key, LongFunction<? extends V> function) {
        this.cache.invalidate(key);
        try { return super.computeIfAbsent(key, function); }
        finally { this.cache.invalidate(key); }
    }

    @Override public V computeIfPresent(long key, BiLong1Function<? super V, ? extends V> function) {
        this.cache.invalidate(key);
        try { return super.computeIfPresent(key, function); }
        finally { this.cache.invalidate(key); }
    }

    @Override public V merge(long key, V def, BiFunction<? super V, ? super V, ? extends V> function) {
        this.cache.invalidate(key);
        try { return super.merge(key, def, function); }
        finally { this.cache.invalidate(key); }
    }

    @Override public void clear() {
        this.cache.invalidateAll();
        try { super.clear(); }
        finally { this.cache.invalidateAll(); }
    }
}
