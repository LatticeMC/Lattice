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
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class EntityDistanceRadixSortTestSuite {
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

    private static LivingEntity entityAtDistanceSquared(double distanceSquared) {
        LivingEntity entity = mock(LivingEntity.class);
        when(entity.distanceToSqr(0.0, 0.0, 0.0)).thenReturn(distanceSquared);
        return entity;
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
