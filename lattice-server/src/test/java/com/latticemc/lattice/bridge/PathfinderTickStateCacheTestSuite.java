package com.latticemc.lattice.bridge;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class PathfinderTickStateCacheTestSuite {
    private final PathfinderCacheTestSupport fixture = new PathfinderCacheTestSupport();
    private final PathfinderTickStateCache cache = new PathfinderTickStateCache();
    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

    PathfinderTickStateCacheTestSuite() { cache.begin(fixture.level, 0); }

    @AfterEach void releaseSections() { cache.begin(null, 0); }

    @Test void saturationRetainsExactlyTheFirst512Sections() {
        fixture.saturate(cache);
        long reads = fixture.chunk.reads;
        assertEquals(0, cache.descriptorAt(fixture.region, pos.set(0, 0, 0)));
        assertEquals(0, cache.descriptorAt(fixture.region, pos.set(511 << 4, 0, 0)));
        assertEquals(reads, fixture.chunk.reads);
        for (int i = 0; i < 4; i++) {
            assertEquals(0, cache.descriptorAt(fixture.region, pos.set((512 + (i & 1)) << 4, 0, 0)));
        }
        assertEquals(reads + 4, fixture.chunk.reads);
        assertEquals(1, cache.descriptorCount());
        assertEquals(2, cache.hits());
        assertEquals(516, cache.misses());
    }

    @Test void saturatedColdReadsDoNotAllocateSectionArrays() {
        fixture.saturate(cache);
        ThreadMXBean allocation = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        assertTrue(allocation.isThreadAllocatedMemorySupported());
        allocation.setThreadAllocatedMemoryEnabled(true);
        for (int i = 0; i < 2000; i++) cache.descriptorAt(fixture.region, pos.set((512 + (i & 1)) << 4, 0, 0));
        long thread = Thread.currentThread().threadId();
        long before = allocation.getThreadAllocatedBytes(thread);
        for (int i = 0; i < 512; i++) cache.descriptorAt(fixture.region, pos.set((512 + (i & 1)) << 4, 0, 0));
        long bytes = allocation.getThreadAllocatedBytes(thread) - before;
        // 旧实现每次约16 KiB，合计超过8 MiB；给测量/JIT杂项保留宽裕余量。
        assertTrue(bytes < 256 * 1024, "cold-read allocated bytes=" + bytes);
    }

    @Test void retainedSectionsCanFillAndRefillAfterSaturation() {
        fixture.saturate(cache);
        cache.descriptorAt(fixture.region, pos.set(512 << 4, 0, 0));
        pos.set(1, 0, 0); // 已保留section中的尚未读取cell。
        int stone = cache.descriptorAt(fixture.region, pos);
        long reads = fixture.chunk.reads;
        assertEquals(stone, cache.descriptorAt(fixture.region, pos));
        assertEquals(reads, fixture.chunk.reads);
        fixture.chunk.state = Blocks.AIR.defaultBlockState();
        PathfinderTickStateCache.invalidate(fixture.level, pos);
        int air = cache.descriptorAt(fixture.region, pos);
        assertNotEquals(stone, air);
        assertEquals(air, cache.descriptorAt(fixture.region, pos));
        assertEquals(reads + 1, fixture.chunk.reads);
    }

    @Test void coldSectionsReadCurrentStateAndRejectDynamicOrUnloadedCells() {
        fixture.saturate(cache);
        pos.set(512 << 4, 0, 0);
        int stone = cache.descriptorAt(fixture.region, pos);
        fixture.chunk.state = Blocks.AIR.defaultBlockState();
        int air = cache.descriptorAt(fixture.region, pos);
        assertNotEquals(stone, air);
        fixture.chunk.state = Blocks.MOVING_PISTON.defaultBlockState();
        assertTrue(fixture.chunk.state.getBlock().hasDynamicShape());
        assertEquals(-1, cache.descriptorAt(fixture.region, pos));
        assertEquals(-1, cache.descriptorAt(fixture.region, pos.set(40000, 0, 0)));
        assertEquals(2, cache.descriptorCount());
    }

    @Test void sameLevelRetainsCellsAcrossGameTimesAndNewLevelResetsThem() {
        fixture.saturate(cache);
        cache.descriptorAt(fixture.region, pos.set(512 << 4, 0, 0)); // 缓存lastSection=null。
        cache.begin(fixture.level, 1);
        long reads = fixture.chunk.reads;
        cache.descriptorAt(fixture.region, pos.set(0, 0, 0));
        assertEquals(reads, fixture.chunk.reads);
        Object nextLevel = new Object();
        cache.begin(nextLevel, 2);
        fixture.chunk.state = Blocks.AIR.defaultBlockState();
        pos.set(512 << 4, 0, 0);
        assertEquals(0, cache.descriptorAt(fixture.region, pos));
        reads = fixture.chunk.reads;
        assertEquals(0, cache.descriptorAt(fixture.region, pos));
        assertEquals(reads, fixture.chunk.reads);
        PathfinderTickStateCache.invalidate(fixture.level, pos); // 旧level已解除关联。
        assertEquals(0, cache.descriptorAt(fixture.region, pos));
        assertEquals(reads, fixture.chunk.reads);
        PathfinderTickStateCache.invalidate(nextLevel, pos);
        cache.descriptorAt(fixture.region, pos);
        assertEquals(reads + 1, fixture.chunk.reads);
    }

    @Test void invalidationOfNegativeCoordinatesOnlyClearsTheTargetCell() {
        int first = cache.descriptorAt(fixture.region, pos.set(-17, -1, -17));
        cache.descriptorAt(fixture.region, pos.set(-18, -1, -17));
        fixture.chunk.state = Blocks.STONE_SLAB.defaultBlockState();
        PathfinderTickStateCache.invalidate(fixture.level, pos.set(-17, -1, -17));
        assertNotEquals(first, cache.descriptorAt(fixture.region, pos));
        long reads = fixture.chunk.reads;
        assertEquals(first, cache.descriptorAt(fixture.region, pos.set(-18, -1, -17)));
        assertEquals(reads, fixture.chunk.reads);
    }

    @Test void coldSnapshotMatchesDirectBlockStateSemanticsAndReusesDescriptors() {
        fixture.saturate(cache);
        fixture.chunk.patterned = true;
        PathfinderStateSnapshot snapshot = new PathfinderStateSnapshot();
        assertTrue(snapshot.fill(fixture.region, cache, 8192, -1, -1, 33, 3, 3));
        int cell = 0;
        for (int y = -1; y < 2; y++) for (int z = -1; z < 2; z++) for (int x = 8192; x < 8225; x++) {
            pos.set(x, y, z);
            var state = fixture.region.getBlockStateIfLoaded(pos);
            int descriptor = snapshot.cells()[cell++];
            assertEquals(WalkNodeEvaluator.getPathTypeFromState(state).ordinal(), snapshot.rawPathTypes()[descriptor]);
            var shape = state.getCollisionShape(fixture.region, pos);
            assertEquals(shape.isEmpty() ? 0.0F : (float) shape.max(Direction.Axis.Y), snapshot.floorHeights()[descriptor]);
        }
        assertEquals(4, snapshot.descriptorCount());
        fixture.chunk.patterned = false;
        fixture.chunk.state = Blocks.MOVING_PISTON.defaultBlockState();
        assertFalse(snapshot.fill(fixture.region, cache, 8192, 0, 0, 1, 1, 1));
    }
}
