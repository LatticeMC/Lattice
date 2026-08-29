package net.minecraft.world.level.levelgen;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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

    @Test
    void combinedUpdateMatchesSequentialUpdatesForColumnTransitions() {
        Fixture combinedFixture = newFixture();
        Fixture sequentialFixture = newFixture();
        Heightmap[] combined = newHeightmaps(combinedFixture.chunk);
        Heightmap[] sequential = newHeightmaps(sequentialFixture.chunk);
        BlockState stone = Blocks.STONE.defaultBlockState();
        BlockState leaves = Blocks.SHORT_GRASS.defaultBlockState();

        apply(combinedFixture.column, combined, true, 0, stone);
        apply(sequentialFixture.column, sequential, false, 0, stone);
        apply(combinedFixture.column, combined, true, 8, leaves);
        apply(sequentialFixture.column, sequential, false, 8, leaves);
        apply(combinedFixture.column, combined, true, 16, stone);
        apply(sequentialFixture.column, sequential, false, 16, stone);

        // Opaque placement below the current maximum must not change any map.
        apply(combinedFixture.column, combined, true, 10, stone);
        apply(sequentialFixture.column, sequential, false, 10, stone);
        assertParity(combined, sequential);
        assertEquals(17, combined[0].getFirstAvailable(0, 0));
        assertEquals(17, combined[3].getFirstAvailable(0, 0));

        // Removing that non-top block is also a no-op for all four maps.
        apply(combinedFixture.column, combined, true, 10, Blocks.AIR.defaultBlockState());
        apply(sequentialFixture.column, sequential, false, 10, Blocks.AIR.defaultBlockState());
        assertParity(combined, sequential);
        assertEquals(17, combined[0].getFirstAvailable(0, 0));

        // Removing the maximum exposes different lower matches for each predicate.
        apply(combinedFixture.column, combined, true, 16, Blocks.AIR.defaultBlockState());
        apply(sequentialFixture.column, sequential, false, 16, Blocks.AIR.defaultBlockState());
        assertParity(combined, sequential);
        assertEquals(1, combined[0].getFirstAvailable(0, 0));
        assertEquals(1, combined[1].getFirstAvailable(0, 0));
        assertEquals(1, combined[2].getFirstAvailable(0, 0));
        assertEquals(9, combined[3].getFirstAvailable(0, 0));

        // Removing the grass exposes the bottom stone for WORLD_SURFACE.
        apply(combinedFixture.column, combined, true, 8, Blocks.AIR.defaultBlockState());
        apply(sequentialFixture.column, sequential, false, 8, Blocks.AIR.defaultBlockState());
        assertParity(combined, sequential);
        assertEquals(1, combined[3].getFirstAvailable(0, 0));

        // Removing the final opaque block reaches the minimum build height.
        apply(combinedFixture.column, combined, true, 0, Blocks.AIR.defaultBlockState());
        apply(sequentialFixture.column, sequential, false, 0, Blocks.AIR.defaultBlockState());
        assertParity(combined, sequential);
        for (Heightmap map : combined) {
            assertEquals(-64, map.getFirstAvailable(0, 0));
        }
    }

    private static Fixture newFixture() {
        ChunkAccess chunk = mock(ChunkAccess.class);
        when(chunk.getHeight()).thenReturn(384);
        when(chunk.getMinY()).thenReturn(-64);
        Map<Integer, BlockState> column = new HashMap<>();
        when(chunk.getBlockState(any(BlockPos.class))).thenAnswer(invocation ->
            column.getOrDefault(invocation.<BlockPos>getArgument(0).getY(), Blocks.AIR.defaultBlockState())
        );
        return new Fixture(chunk, column);
    }

    private static Heightmap[] newHeightmaps(ChunkAccess chunk) {
        return new Heightmap[] {
            new Heightmap(chunk, Heightmap.Types.MOTION_BLOCKING),
            new Heightmap(chunk, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES),
            new Heightmap(chunk, Heightmap.Types.OCEAN_FLOOR),
            new Heightmap(chunk, Heightmap.Types.WORLD_SURFACE)
        };
    }

    private static void apply(Map<Integer, BlockState> column, Heightmap[] maps, boolean combined, int y, BlockState state) {
        if (state.isAir()) {
            column.remove(y);
        } else {
            column.put(y, state);
        }
        if (combined) {
            Heightmap.updateAll(maps[0], maps[1], maps[2], maps[3], 0, y, 0, state);
        } else {
            for (Heightmap map : maps) {
                map.update(0, y, 0, state);
            }
        }
    }

    private static void assertParity(Heightmap[] combined, Heightmap[] sequential) {
        for (int i = 0; i < combined.length; i++) {
            assertArrayEquals(sequential[i].getRawData(), combined[i].getRawData(), "heightmap index " + i);
        }
    }

    private static final class Fixture {
        private final ChunkAccess chunk;
        private final Map<Integer, BlockState> column;

        private Fixture(ChunkAccess chunk, Map<Integer, BlockState> column) {
            this.chunk = chunk;
            this.column = column;
        }
    }
}
