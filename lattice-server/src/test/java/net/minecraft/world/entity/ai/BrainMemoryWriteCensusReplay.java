package net.minecraft.world.entity.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import com.google.common.collect.ImmutableList;
import com.latticemc.lattice.diagnostics.BrainMemoryWriteCensus;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;

/** Run in an isolated JVM with lattice.brainMemoryWriteCensus set before class initialization. */
public final class BrainMemoryWriteCensusReplay {
    public static void main(String[] args) throws Exception {
        if (!BrainMemoryWriteCensus.isEnabled()) throw new AssertionError("census must be enabled for this replay");
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        Field field = BrainMemoryWriteCensus.class.getDeclaredField("COLLECTOR");
        field.setAccessible(true);
        BrainMemoryWriteCensus.Collector collector = (BrainMemoryWriteCensus.Collector) field.get(null);
        collector.clear();
        Brain<LivingEntity> brain = new Brain<>(List.of(MemoryModuleType.NEAREST_PLAYERS,
                MemoryModuleType.IS_PANICKING), List.of(), ImmutableList.of(), () -> null);
        ServerLevel level = mock(ServerLevel.class);
        LivingEntity entity = mock(LivingEntity.class);

        // Empty collection recursively erases: count both calls, not a version delta.
        BrainMemoryWriteCensus.beginTick(brain);
        brain.setMemory(MemoryModuleType.IS_PANICKING, true);
        brain.setMemory(MemoryModuleType.NEAREST_PLAYERS, List.of());
        brain.eraseMemory(MemoryModuleType.IS_PANICKING);
        brain.clearMemories();
        BrainMemoryWriteCensus.endTick(brain, false);
        assertEquals(Map.of(5, 1L), collector.writeCountDistribution(brain));

        brain.setMemoryWithExpiry(MemoryModuleType.IS_PANICKING, true, 0);
        brain.tick(level, entity);
        brain.tick(level, entity);
        assertEquals(Map.of(5, 1L, 1, 1L, 0, 1L), collector.writeCountDistribution(brain));
        assertEquals(Map.of(2, 1L), collector.streakDistribution(brain));

        Brain<LivingEntity> failing = new Brain<>(List.of(MemoryModuleType.IS_PANICKING),
                List.of(), ImmutableList.of(), () -> null);
        failing.setMemoryWithExpiry(MemoryModuleType.IS_PANICKING, true, 0);
        // A null level fails after TTL removal and must still close the census tick.
        assertThrows(NullPointerException.class, () -> failing.tick(null, entity));
        assertEquals(Map.of(1, 1L), collector.writeCountDistribution(failing));
        assertEquals(1L, collector.failedTicks(failing));
        System.out.println("BrainMemoryWriteCensusReplay: PASS");
    }
}
