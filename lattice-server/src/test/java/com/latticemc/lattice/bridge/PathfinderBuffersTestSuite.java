package com.latticemc.lattice.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.minecraft.world.level.pathfinder.PathType;
import org.junit.jupiter.api.Test;

class PathfinderBuffersTestSuite {
    @Test
    void rawPathTypeCacheUsesGenerationStampsAndFallsBackOutsideBounds() {
        PathfinderBuffers buffers = new PathfinderBuffers();
        PathfinderBuffers.RawPathTypeCache cache = buffers.rawPathTypes(10, 20, 30, 4, 3, 2);

        assertEquals(-1, cache.get(11, 21, 31));
        cache.put(11, 21, 31, PathType.WALKABLE.ordinal());
        assertEquals(PathType.WALKABLE.ordinal(), cache.get(11, 21, 31));
        assertEquals(-1, cache.get(9, 21, 31));
        assertEquals(1, cache.hits());
        assertEquals(1, cache.misses());
        assertEquals(1, cache.outside());

        cache = buffers.rawPathTypes(10, 20, 30, 4, 3, 2);
        assertEquals(-1, cache.get(11, 21, 31));
        assertEquals(0, cache.hits());
        assertEquals(1, cache.misses());
        assertEquals(0, cache.outside());
    }
}
