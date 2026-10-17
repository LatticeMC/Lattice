package net.minecraft.world.entity.ai.sensing;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class SensingBatchTestSuite {
    @BeforeAll
    static void bootstrapRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void duplicateEntityIdsRetainCachedResultAndDoNotRepeatVanillaCheck() {
        Level level = Mockito.mock(Level.class);
        Mob mob = Mockito.mock(Mob.class);
        Mockito.when(mob.level()).thenReturn(level);
        Entity first = entity(level, 11);
        Entity second = entity(level, 12);
        Mockito.when(mob.hasLineOfSight(Mockito.any(Entity.class))).thenAnswer(invocation -> {
            Entity target = invocation.getArgument(0);
            return target.getId() == 11;
        });

        Sensing sensing = new Sensing(mob);
        assertArrayEquals(new boolean[] {true, true, false}, sensing.hasLineOfSightBatch(java.util.List.of(first, first, second)));
        Mockito.verify(mob, Mockito.times(2)).hasLineOfSight(Mockito.any(Entity.class));
    }

    @Test
    void tickAdvancesGenerationWithoutResizingAndPreservesMemoization() {
        Level level = Mockito.mock(Level.class);
        Mob mob = Mockito.mock(Mob.class);
        Mockito.when(mob.level()).thenReturn(level);
        Entity target = entity(level, 7);
        Mockito.when(mob.hasLineOfSight(Mockito.any(Entity.class))).thenReturn(true);

        Sensing sensing = new Sensing(mob);
        assertEquals(true, sensing.hasLineOfSight(target));
        Mockito.verify(mob, Mockito.times(1)).hasLineOfSight(target);
        sensing.tick();
        assertEquals(true, sensing.hasLineOfSight(target));
        Mockito.verify(mob, Mockito.times(2)).hasLineOfSight(target);
    }

    private static Entity entity(Level level, int id) {
        Entity entity = Mockito.mock(Entity.class);
        Mockito.when(entity.level()).thenReturn(level);
        Mockito.when(entity.getId()).thenReturn(id);
        return entity;
    }
}
