package com.latticemc.lattice.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import net.minecraft.util.AbortableIterationConsumer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.junit.jupiter.api.Test;

class EntityPushStateTestSuite {
    @Test
    void collectsOnlyTheFirstCollisionCapEntities() {
        final EntityPushState state = new EntityPushState();
        final LivingEntity source = sourceWithRoll(0);
        final Entity first = entity(false);
        final Entity second = entity(false);

        state.reset(source, 0, 2);
        assertEquals(AbortableIterationConsumer.Continuation.CONTINUE, state.accept(first));
        assertEquals(AbortableIterationConsumer.Continuation.ABORT, state.accept(second));
        assertEquals(List.of(first, second), state.pushableEntities);
        assertFalse(state.crammingDamage);
        state.release();
    }

    @Test
    void scansPastPassengerPrefixUntilCrammingHasEnoughNonPassengers() {
        final EntityPushState state = new EntityPushState();
        final LivingEntity source = sourceWithRoll(0);
        final Entity passengerOne = entity(true);
        final Entity passengerTwo = entity(true);
        final Entity nonPassengerOne = entity(false);
        final Entity nonPassengerTwo = entity(false);

        state.reset(source, 2, 1);
        assertEquals(AbortableIterationConsumer.Continuation.CONTINUE, state.accept(passengerOne));
        assertEquals(AbortableIterationConsumer.Continuation.CONTINUE, state.accept(passengerTwo));
        assertEquals(AbortableIterationConsumer.Continuation.CONTINUE, state.accept(nonPassengerOne));
        assertEquals(AbortableIterationConsumer.Continuation.ABORT, state.accept(nonPassengerTwo));
        assertEquals(List.of(passengerOne), state.pushableEntities);
        assertTrue(state.crammingDamage);
        state.release();
    }

    @Test
    void resolvesCrammingEarlyWhenRandomRollDoesNotSelectDamage() {
        final EntityPushState state = new EntityPushState();
        final LivingEntity source = sourceWithRoll(1);

        state.reset(source, 2, 1);
        assertEquals(AbortableIterationConsumer.Continuation.CONTINUE, state.accept(entity(true)));
        assertEquals(AbortableIterationConsumer.Continuation.ABORT, state.accept(entity(true)));
        assertFalse(state.crammingDamage);
        verify(source.getRandom(), times(1)).nextInt(4);
        state.release();
    }

    @Test
    void rejectsReentryUntilReleased() {
        final EntityPushState state = new EntityPushState();
        final LivingEntity source = sourceWithRoll(0);

        state.reset(source, 0, 1);
        assertThrows(IllegalStateException.class, () -> state.reset(source, 0, 1));
        state.release();
    }

    @Test
    void releaseClearsStateForReuse() {
        final EntityPushState state = new EntityPushState();
        final LivingEntity source = sourceWithRoll(0);

        state.reset(source, 0, 1);
        state.accept(entity(false));
        state.release();
        state.reset(source, 0, 2);

        assertFalse(state.foundAny);
        assertTrue(state.pushableEntities.isEmpty());
        assertEquals(AbortableIterationConsumer.Continuation.CONTINUE, state.accept(entity(false)));
        assertEquals(AbortableIterationConsumer.Continuation.ABORT, state.accept(entity(false)));
        state.release();
    }

    private static LivingEntity sourceWithRoll(final int roll) {
        final LivingEntity source = mock(LivingEntity.class);
        final RandomSource random = mock(RandomSource.class);
        when(source.getRandom()).thenReturn(random);
        when(random.nextInt(4)).thenReturn(roll);
        return source;
    }

    private static Entity entity(final boolean passenger) {
        final Entity entity = mock(Entity.class);
        when(entity.isPassenger()).thenReturn(passenger);
        return entity;
    }
}
