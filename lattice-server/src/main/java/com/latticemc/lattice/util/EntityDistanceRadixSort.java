package com.latticemc.lattice.util;

import net.minecraft.core.Position;
import net.minecraft.world.entity.Entity;

/**
 * Sorts entities by squared distance while reusing the key buffer across sensor ticks.
 *
 * <p>Adapted from Leaf's fast bit radix sort.</p>
 *
 * <p>Original author: hayanesuru &lt;hayanesuru@outlook.jp&gt;<br>
 * Co-author: Taiyou06 &lt;kaandindar21@gmail.com&gt;<br>
 * Original license: MIT<br>
 * Source: https://github.com/Winds-Studio/Leaf/blob/178bcb4/leaf-server/minecraft-patches/features/0268-fast-bit-radix-sort.patch</p>
 */
public final class EntityDistanceRadixSort {
    private static final int SMALL_ARRAY_THRESHOLD = 6;
    private static final long[] EMPTY_KEYS = new long[0];

    private long[] keys = EMPTY_KEYS;

    public void sort(Object[] entities, int size, Position target) {
        if (size <= 1) {
            return;
        }
        if (this.keys.length < size) {
            this.keys = new long[size];
        }

        double x = target.x();
        double y = target.y();
        double z = target.z();
        for (int i = 0; i < size; i++) {
            this.keys[i] = Double.doubleToRawLongBits(((Entity) entities[i]).distanceToSqr(x, y, z));
        }

        sort(entities, this.keys, 0, size - 1, 62);
    }

    private static void sort(Object[] entities, long[] keys, int low, int high, int bit) {
        if (bit < 0 || low >= high) {
            return;
        }
        if (high - low <= SMALL_ARRAY_THRESHOLD) {
            insertionSort(entities, keys, low, high);
            return;
        }

        int left = low;
        int right = high;
        long mask = 1L << bit;
        while (left <= right) {
            while (left <= right && (keys[left] & mask) == 0L) {
                left++;
            }
            while (left <= right && (keys[right] & mask) != 0L) {
                right--;
            }
            if (left < right) {
                swap(entities, keys, left++, right--);
            }
        }

        if (low < right) {
            sort(entities, keys, low, right, bit - 1);
        }
        if (left < high) {
            sort(entities, keys, left, high, bit - 1);
        }
    }

    private static void insertionSort(Object[] entities, long[] keys, int low, int high) {
        for (int i = low + 1; i <= high; i++) {
            Object entity = entities[i];
            long key = keys[i];
            int insertAt = i;
            while (insertAt > low && keys[insertAt - 1] > key) {
                entities[insertAt] = entities[insertAt - 1];
                keys[insertAt] = keys[insertAt - 1];
                insertAt--;
            }
            entities[insertAt] = entity;
            keys[insertAt] = key;
        }
    }

    private static void swap(Object[] entities, long[] keys, int first, int second) {
        Object entity = entities[first];
        entities[first] = entities[second];
        entities[second] = entity;

        long key = keys[first];
        keys[first] = keys[second];
        keys[second] = key;
    }
}
