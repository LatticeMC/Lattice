package com.latticemc.lattice.bridge;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.PathNavigationRegion;

/**
 * Experimental input for a native-driven pathfinder. Each descriptor represents
 * one immutable BlockState seen by a request; dynamic shapes stay on the Java
 * path until their position-dependent collision semantics are modeled.
 */
final class PathfinderStateSnapshot {
    private int[] cells = new int[0];
    private PathfinderTickStateCache cache;

    private int cellCount;
    private int descriptorCount;
    private int minX;
    private int minY;
    private int minZ;
    private int sizeX;
    private int sizeY;
    private int sizeZ;

    boolean fill(PathNavigationRegion region, PathfinderTickStateCache cache,
                 int minX, int minY, int minZ,
                 int sizeX, int sizeY, int sizeZ) {
        int required = Math.multiplyExact(Math.multiplyExact(sizeX, sizeY), sizeZ);
        if (this.cells.length < required) this.cells = new int[required];
        this.cellCount = required;
        this.cache = cache;
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;

        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int index = 0;
        for (int y = minY; y < minY + sizeY; ++y) {
            for (int z = minZ; z < minZ + sizeZ; ++z) {
                long sectionKey = 0;
                int localIndex = 0;
                for (int x = minX; x < minX + sizeX; ++x, ++index, ++localIndex) {
                    if (x == minX || (x & 15) == 0) {
                        sectionKey = SectionPos.asLong(x >> 4, y >> 4, z >> 4);
                        localIndex = (x & 15) | ((z & 15) << 4) | ((y & 15) << 8);
                    }
                    int descriptor = cache.descriptorAt(region, cursor.set(x, y, z), sectionKey, localIndex);
                    if (descriptor < 0) return false;
                    this.cells[index] = descriptor;
                }
            }
        }
        return true;
    }

    int cellCount() {
        return this.cellCount;
    }

    int descriptorCount() {
        return this.cache.descriptorCount();
    }

    int[] cells() {
        return this.cells;
    }

    byte[] rawPathTypes() {
        return this.cache.rawPathTypes();
    }

    float[] floorHeights() {
        return this.cache.floorHeights();
    }

    int minX() { return this.minX; }
    int minY() { return this.minY; }
    int minZ() { return this.minZ; }
    int sizeX() { return this.sizeX; }
    int sizeY() { return this.sizeY; }
    int sizeZ() { return this.sizeZ; }

}
