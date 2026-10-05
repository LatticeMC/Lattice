package com.latticemc.lattice.util;

import java.util.function.LongFunction;
import java.util.Objects;

/**
 * 固定 owner 的单项正值查询缓存。源表的新建项不得覆盖仍在缓存中的旧实例；
 * 删除或替换必须先 invalidate。空值不缓存，异线程查询始终读取源表。
 */
public final class ChunkCache<V> {
    public static final boolean ENABLED = Boolean.getBoolean("lattice.chunkMapCache");
    private final Thread owner;
    private final LongFunction<V> source;
    private volatile boolean enabled = true;
    private long lastKey;
    private V lastValue;

    public ChunkCache(final Thread owner, final LongFunction<V> source) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.source = Objects.requireNonNull(source, "source");
    }

    public V get(final long key) {
        if (Thread.currentThread() != this.owner || !this.enabled) {
            return this.source.apply(key);
        }
        if (this.lastValue != null && this.lastKey == key) {
            return this.lastValue;
        }
        V value = this.source.apply(key);
        if (value != null) {
            this.lastKey = key;
            this.lastValue = value;
        }
        return value;
    }

    public void invalidateAll() {
        if (Thread.currentThread() != this.owner) {
            this.enabled = false;
            return;
        }
        this.lastValue = null;
    }

    /** 源表修改前调用；若修改包含可重入回调，finally 中也必须再调用。 */
    public void invalidate(final long key) {
        if (Thread.currentThread() != this.owner) {
            // 停服等异线程路径只能发布停用，不能访问 owner 的缓存值。
            // 已通过 get 门控的读取可返回旧值；之后发起的读取直接查源表。
            this.enabled = false;
            return;
        }
        if (this.lastValue != null && this.lastKey == key) {
            this.lastValue = null;
        }
    }
}
