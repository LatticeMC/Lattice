package com.latticemc.lattice.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class EntityDistanceRadixSortTestSuite {
    private static final int SMALL_ARRAY_THRESHOLD = 6;
    private static final int[] REFERENCE_SIZES = {0, 1, 2, 6, 7, 8, 9, 16, 64, 256, 2048};
    private static final Vec3 ORIGIN = Vec3.ZERO;

    @BeforeAll
    static void bootstrapRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void leavesEmptySingleAndUnusedTailEntriesAlone() {
        EntityDistanceRadixSort sorter = new EntityDistanceRadixSort();
        Object[] empty = new Object[0];
        sorter.sort(empty, 0, ORIGIN);

        LivingEntity only = entityAtDistanceSquared(4.0);
        Object tail = new Object();
        Object[] one = {only, tail};
        sorter.sort(one, 1, ORIGIN);

        assertSame(only, one[0]);
        assertSame(tail, one[1]);
    }

    @Test
    void sortsAcrossInsertionThresholdWithoutChangingIdentitySet() {
        LivingEntity[] entities = {
            entityAtDistanceSquared(81.0),
            entityAtDistanceSquared(1.0),
            entityAtDistanceSquared(49.0),
            entityAtDistanceSquared(9.0),
            entityAtDistanceSquared(64.0),
            entityAtDistanceSquared(16.0),
            entityAtDistanceSquared(36.0),
            entityAtDistanceSquared(4.0),
            entityAtDistanceSquared(25.0)
        };
        Set<LivingEntity> identities = Collections.newSetFromMap(new IdentityHashMap<>());
        Collections.addAll(identities, entities);

        new EntityDistanceRadixSort().sort(entities, entities.length, ORIGIN);

        assertNonDecreasing(entities, entities.length);
        assertEquals(entities.length, identities.size());
        for (LivingEntity entity : entities) {
            assertTrue(identities.remove(entity));
        }
        assertTrue(identities.isEmpty());
    }

    @Test
    void handlesDuplicateDistancesAndDoesNotReadBackingArrayTail() {
        LivingEntity first = entityAtDistanceSquared(4.0);
        LivingEntity second = entityAtDistanceSquared(1.0);
        LivingEntity third = entityAtDistanceSquared(4.0);
        LivingEntity fourth = entityAtDistanceSquared(9.0);
        Object tail = new Object();
        Object[] entities = {first, second, third, fourth, tail, null};

        new EntityDistanceRadixSort().sort(entities, 4, ORIGIN);

        assertNonDecreasing(entities, 4);
        assertSame(tail, entities[4]);
        assertNull(entities[5]);
    }

    @Test
    void commonPrefixOptimizationMatchesFrozenPreChangeSorter() {
        EntityDistanceRadixSort sorter = new EntityDistanceRadixSort();
        for (int size : REFERENCE_SIZES) {
            assertMatchesFrozenReference(sorter, finiteCommonPrefix(size), size);
            assertMatchesFrozenReference(sorter, repeatedFiniteDistances(size), size);
            assertMatchesFrozenReference(sorter, allEqualDistances(size), size);
            assertMatchesFrozenReference(sorter, signedZeroDistances(size), size);
            assertMatchesFrozenReference(sorter, specialDistances(size), size);
        }
    }

    @Test
    void reusesKeyBufferAcrossAlternatingLargeAndSmallSorts() {
        EntityDistanceRadixSort sorter = new EntityDistanceRadixSort();
        int[] sizes = {2048, 2, 256, 7, 64, 0, 16, 1, 9, 2048, 6, 8};
        for (int size : sizes) {
            assertMatchesFrozenReference(sorter, finiteCommonPrefix(size), size);
            assertMatchesFrozenReference(sorter, specialDistances(size), size);
        }
    }

    private static LivingEntity entityAtDistanceSquared(double distanceSquared) {
        LivingEntity entity = mock(LivingEntity.class);
        when(entity.distanceToSqr(0.0, 0.0, 0.0)).thenReturn(distanceSquared);
        return entity;
    }

    private static Object[] finiteCommonPrefix(int size) {
        return entitiesFor(size, index -> Double.longBitsToDouble(0x40A0000000000000L + ((long) (size - index) << 12)));
    }

    private static Object[] repeatedFiniteDistances(int size) {
        double[] distances = {64.0, 1.0, 64.0, 9.0, 4.0, 9.0, 16.0};
        return entitiesFor(size, index -> distances[index % distances.length]);
    }

    private static Object[] allEqualDistances(int size) {
        return entitiesFor(size, index -> 16.0);
    }

    private static Object[] signedZeroDistances(int size) {
        return entitiesFor(size, index -> index % 2 == 0 ? 0.0 : -0.0);
    }

    private static Object[] specialDistances(int size) {
        double[] distances = {
            Double.POSITIVE_INFINITY,
            Double.NEGATIVE_INFINITY,
            Double.longBitsToDouble(0x7FF8000000000001L),
            Double.longBitsToDouble(0x7FF800000000003FL),
            Double.longBitsToDouble(0xFFF8000000000001L),
            1.0,
            -0.0,
            0.0
        };
        return entitiesFor(size, index -> distances[index % distances.length]);
    }

    private static Object[] entitiesFor(int size, DistanceFactory distances) {
        Object firstSentinel = new Object();
        Object secondSentinel = new Object();
        Object[] entities = new Object[size + 2];
        for (int i = 0; i < size; i++) {
            entities[i] = entityAtDistanceSquared(distances.distanceAt(i));
        }
        entities[size] = firstSentinel;
        entities[size + 1] = secondSentinel;
        return entities;
    }

    private static void assertMatchesFrozenReference(EntityDistanceRadixSort sorter, Object[] source, int size) {
        Object[] candidate = source.clone();
        Object[] reference = source.clone();

        sorter.sort(candidate, size, ORIGIN);
        frozenPreChangeSort(reference, size, ORIGIN);

        for (int i = 0; i < candidate.length; i++) {
            assertSame(reference[i], candidate[i], "identity order differs at index " + i + " for size " + size);
        }
    }

    // Frozen copy of EntityDistanceRadixSort before the common-prefix optimization.
    private static void frozenPreChangeSort(Object[] entities, int size, Vec3 target) {
        if (size <= 1) {
            return;
        }

        long[] keys = new long[size];
        double x = target.x();
        double y = target.y();
        double z = target.z();
        for (int i = 0; i < size; i++) {
            keys[i] = Double.doubleToRawLongBits(((Entity) entities[i]).distanceToSqr(x, y, z));
        }

        frozenPreChangeSort(entities, keys, 0, size - 1, 62);
    }

    private static void frozenPreChangeSort(Object[] entities, long[] keys, int low, int high, int bit) {
        if (bit < 0 || low >= high) {
            return;
        }
        if (high - low <= SMALL_ARRAY_THRESHOLD) {
            frozenPreChangeInsertionSort(entities, keys, low, high);
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
                frozenPreChangeSwap(entities, keys, left++, right--);
            }
        }

        if (low < right) {
            frozenPreChangeSort(entities, keys, low, right, bit - 1);
        }
        if (left < high) {
            frozenPreChangeSort(entities, keys, left, high, bit - 1);
        }
    }

    private static void frozenPreChangeInsertionSort(Object[] entities, long[] keys, int low, int high) {
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

    private static void frozenPreChangeSwap(Object[] entities, long[] keys, int first, int second) {
        Object entity = entities[first];
        entities[first] = entities[second];
        entities[second] = entity;

        long key = keys[first];
        keys[first] = keys[second];
        keys[second] = key;
    }

    @FunctionalInterface
    private interface DistanceFactory {
        double distanceAt(int index);
    }

    private static void assertNonDecreasing(Object[] entities, int size) {
        double previous = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < size; i++) {
            double current = ((LivingEntity) entities[i]).distanceToSqr(0.0, 0.0, 0.0);
            assertTrue(current >= previous, "distance at index " + i + " is out of order");
            previous = current;
        }
    }
}
