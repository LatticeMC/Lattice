package net.minecraft.world.level.levelgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class HeightmapCombinedUpdateTestSuite {
    @BeforeAll
    static void bootstrapRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void combinedUpdateKeepsPredicatesIndependentWhenRemovingColumnTop() {
        ChunkAccess chunk = mock(ChunkAccess.class);
        when(chunk.getHeight()).thenReturn(384);
        when(chunk.getMinY()).thenReturn(-64);

        Map<Integer, BlockState> column = new HashMap<>();
        AtomicInteger blockReads = new AtomicInteger();
        when(chunk.getBlockState(any(BlockPos.class))).thenAnswer(invocation -> {
            blockReads.incrementAndGet();
            return column.getOrDefault(invocation.<BlockPos>getArgument(0).getY(), Blocks.AIR.defaultBlockState());
        });

        Heightmap motionBlocking = new Heightmap(chunk, Heightmap.Types.MOTION_BLOCKING);
        Heightmap motionBlockingNoLeaves = new Heightmap(chunk, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES);
        Heightmap oceanFloor = new Heightmap(chunk, Heightmap.Types.OCEAN_FLOOR);
        Heightmap worldSurface = new Heightmap(chunk, Heightmap.Types.WORLD_SURFACE);
        BlockState stone = Blocks.STONE.defaultBlockState();

        Heightmap.updateAll(motionBlocking, motionBlockingNoLeaves, oceanFloor, worldSurface, 0, 20, 0, stone);
        assertEquals(21, motionBlocking.getFirstAvailable(0, 0));
        assertEquals(21, motionBlockingNoLeaves.getFirstAvailable(0, 0));
        assertEquals(21, oceanFloor.getFirstAvailable(0, 0));
        assertEquals(21, worldSurface.getFirstAvailable(0, 0));

        column.put(19, Blocks.SHORT_GRASS.defaultBlockState());
        column.put(20, Blocks.AIR.defaultBlockState());
        blockReads.set(0);
        Heightmap.updateAll(
            motionBlocking,
            motionBlockingNoLeaves,
            oceanFloor,
            worldSurface,
            0,
            20,
            0,
            Blocks.AIR.defaultBlockState()
        );

        assertEquals(-64, motionBlocking.getFirstAvailable(0, 0));
        assertEquals(-64, motionBlockingNoLeaves.getFirstAvailable(0, 0));
        assertEquals(-64, oceanFloor.getFirstAvailable(0, 0));
        assertEquals(20, worldSurface.getFirstAvailable(0, 0));
        assertEquals(84, blockReads.get(), "one shared scan should inspect each lower block once");
    }

    @Test
    void combinedUpdateDoesNotScanForNonTopOpaquePlacement() {
        ChunkAccess chunk = mock(ChunkAccess.class);
        when(chunk.getHeight()).thenReturn(384);
        when(chunk.getMinY()).thenReturn(-64);
        AtomicInteger blockReads = new AtomicInteger();
        when(chunk.getBlockState(any(BlockPos.class))).thenAnswer(invocation -> {
            blockReads.incrementAndGet();
            return Blocks.AIR.defaultBlockState();
        });

        Heightmap motionBlocking = new Heightmap(chunk, Heightmap.Types.MOTION_BLOCKING);
        Heightmap motionBlockingNoLeaves = new Heightmap(chunk, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES);
        Heightmap oceanFloor = new Heightmap(chunk, Heightmap.Types.OCEAN_FLOOR);
        Heightmap worldSurface = new Heightmap(chunk, Heightmap.Types.WORLD_SURFACE);
        Heightmap.updateAll(motionBlocking, motionBlockingNoLeaves, oceanFloor, worldSurface, 0, 20, 0, Blocks.STONE.defaultBlockState());

        blockReads.set(0);
        Heightmap.updateAll(motionBlocking, motionBlockingNoLeaves, oceanFloor, worldSurface, 0, 10, 0, Blocks.STONE.defaultBlockState());

        assertEquals(21, motionBlocking.getFirstAvailable(0, 0));
        assertEquals(21, motionBlockingNoLeaves.getFirstAvailable(0, 0));
        assertEquals(21, oceanFloor.getFirstAvailable(0, 0));
        assertEquals(21, worldSurface.getFirstAvailable(0, 0));
        assertEquals(0, blockReads.get());
    }
}
