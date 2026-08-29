package net.minecraft.world.entity.ai.sensing;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class SensingBatchTestSuite {
    @Test
    void duplicateEntityIdsRetainCachedResultAndDoNotRepeatVanillaCheck() {
        Level level = Mockito.mock(Level.class);
        CountingMob mob = new CountingMob(level);
        Entity first = entity(level, 11);
        Entity second = entity(level, 12);

        Sensing sensing = new Sensing(mob);
        assertArrayEquals(new boolean[] {true, true, false}, sensing.hasLineOfSightBatch(java.util.List.of(first, first, second)));
        assertEquals(2, mob.vanillaCalls, "duplicate entity must be served from the per-tick cache");
    }

    private static Entity entity(Level level, int id) {
        Entity entity = Mockito.mock(Entity.class);
        Mockito.when(entity.level()).thenReturn(level);
        Mockito.when(entity.getId()).thenReturn(id);
        return entity;
    }

    private static final class CountingMob extends Mob {
        private int vanillaCalls;

        private CountingMob(Level level) {
            super(EntityType.PIG, level);
        }

        @Override
        public boolean hasLineOfSight(Entity entity) {
            ++this.vanillaCalls;
            return entity.getId() == 11;
        }
    }
}
