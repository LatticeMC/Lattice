package com.latticemc.lattice.util;

import java.util.Arrays;
import java.util.Objects;
import java.util.function.LongFunction;

/** A per-tick, owner-thread cache in front of an authoritative long-keyed source. */
public final class ChunkCache<V> {
    public static final boolean ENABLED = Boolean.getBoolean("lattice.chunkMapCache");
    private static final int INITIAL_CAPACITY = 1024;
    private static final float LOAD_FACTOR = 0.5f;

    private final Thread owner;
    private final LongFunction<V> source;
    private long[] keys = new long[INITIAL_CAPACITY];
    private Object[] values = new Object[INITIAL_CAPACITY];
    private int[] generations = new int[INITIAL_CAPACITY];
    private int mask = INITIAL_CAPACITY - 1;
    private int generation = 1;
    private int size;
    private boolean active;
    private volatile boolean disabled;

    public ChunkCache(final Thread owner, final LongFunction<V> source) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.source = Objects.requireNonNull(source, "source");
    }

    public V get(final long key) {
        if (Thread.currentThread() != this.owner || !this.active || this.disabled) {
            return this.source.apply(key);
        }
        final int slot = this.find(key);
        if (slot >= 0) {
            @SuppressWarnings("unchecked")
            V value = (V) this.values[slot];
            return value;
        }
        V value = this.source.apply(key);
        if (value != null) {
            this.insert(-slot - 1, key, value);
        }
        return value;
    }

    public void beginTick() {
        if (Thread.currentThread() != this.owner) {
            throw new IllegalStateException("Chunk cache owner mismatch");
        }
        if (++this.generation == 0) {
            Arrays.fill(this.generations, 0);
            this.generation = 1;
        }
        this.size = 0;
        this.active = true;
        this.disabled = false;
    }

    public void endTick() {
        if (Thread.currentThread() != this.owner) {
            throw new IllegalStateException("Chunk cache owner mismatch");
        }
        this.active = false;
    }

    public void invalidateAll() {
        if (Thread.currentThread() != this.owner) {
            this.disabled = true;
            return;
        }
        this.size = 0;
        this.generation++;
        if (this.generation == 0) {
            Arrays.fill(this.generations, 0);
            this.generation = 1;
        }
    }

    public void invalidate(final long key) {
        if (Thread.currentThread() != this.owner) {
            this.disabled = true;
            return;
        }
        final int slot = this.find(key);
        if (slot >= 0) {
            this.generations[slot] = 0;
            this.values[slot] = null;
            this.size--;
        }
    }

    private int find(final long key) {
        int slot = mix(key) & this.mask;
        while (this.generations[slot] == this.generation) {
            if (this.keys[slot] == key) return slot;
            slot = (slot + 1) & this.mask;
        }
        return -slot - 1;
    }

    private void insert(final int slot, final long key, final V value) {
        if (this.size + 1 > (int) (this.keys.length * LOAD_FACTOR)) {
            this.rehash(this.keys.length << 1);
            this.insert(-this.find(key) - 1, key, value);
            return;
        }
        this.size++;
        this.keys[slot] = key;
        this.values[slot] = value;
        this.generations[slot] = this.generation;
    }

    private void rehash(final int capacity) {
        long[] oldKeys = this.keys;
        Object[] oldValues = this.values;
        int[] oldGenerations = this.generations;
        this.keys = new long[capacity];
        this.values = new Object[capacity];
        this.generations = new int[capacity];
        this.mask = capacity - 1;
        int oldSize = this.size;
        this.size = 0;
        for (int i = 0; i < oldKeys.length; i++) {
            if (oldGenerations[i] != this.generation) continue;
            @SuppressWarnings("unchecked") V value = (V) oldValues[i];
            this.insert(-this.find(oldKeys[i]) - 1, oldKeys[i], value);
        }
        if (this.size != oldSize) throw new IllegalStateException("Chunk cache rehash lost entries");
    }

    private static int mix(final long value) {
        long z = value;
        z = (z ^ (z >>> 33)) * 0xff51afd7ed558ccdL;
        z = (z ^ (z >>> 33)) * 0xc4ceb9fe1a85ec53L;
        return (int) (z ^ (z >>> 32));
    }
}
