package com.latticemc.lattice.bridge;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.IdentityHashMap;
import java.util.Arrays;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Server-thread cache for static shapes. Block writes invalidate individual
 * positions through {@link PathfinderStaticCache}.
 */
final class PathfinderTickStateCache {
    // A busy target can have several hundred nearby navigation regions in
    // flight. 64 sections saturated before the second wave of mobs ran; 512
    // retains an 8 MiB upper bound while covering that shared working set.
    private static final int MAX_CACHED_SECTIONS = 512;
    private static final IdentityHashMap<Object, PathfinderTickStateCache> ACTIVE_CACHES = new IdentityHashMap<>();

    private final Long2ObjectOpenHashMap<Section> sections = new Long2ObjectOpenHashMap<>();
    private final IdentityHashMap<BlockState, Integer> descriptors = new IdentityHashMap<>();
    private byte[] rawPathTypes = new byte[16];
    private float[] floorHeights = new float[16];
    private Object level;
    private int descriptorCount;
    private boolean cacheCells = true;
    private long hits;
    private long misses;
    private long lastSectionKey = Long.MIN_VALUE;
    private Section lastSection;

    PathfinderTickStateCache() {
    }

    void begin(Object level, long gameTime) {
        if (this.level == level) return;
        if (this.level != null) ACTIVE_CACHES.remove(this.level);
        this.level = level;
        ACTIVE_CACHES.put(level, this);
        this.sections.clear();
        this.lastSectionKey = Long.MIN_VALUE;
        this.lastSection = null;
        this.descriptors.clear();
        this.descriptorCount = 0;
        this.cacheCells = true;
    }

    static void invalidate(Object level, BlockPos pos) {
        PathfinderTickStateCache cache = ACTIVE_CACHES.get(level);
        if (cache != null) cache.invalidate(pos);
    }

    int descriptorAt(PathNavigationRegion region, BlockPos.MutableBlockPos pos) {
        Section section = this.sectionAt(pos);
        int index = sectionIndex(pos);
        int descriptor = section.descriptors[index];
        if (descriptor >= 0) {
            this.hits++;
            return descriptor;
        }
        this.misses++;
        BlockState state = region.getBlockStateIfLoaded(pos);
        if (state == null || state.getBlock().hasDynamicShape()) return -1;
        descriptor = this.descriptorFor(region, pos, state);
        if (this.cacheCells) section.descriptors[index] = descriptor;
        return descriptor;
    }

    byte[] rawPathTypes() {
        return this.rawPathTypes;
    }

    float[] floorHeights() {
        return this.floorHeights;
    }

    int descriptorCount() {
        return this.descriptorCount;
    }

    long hits() {
        return this.hits;
    }

    long misses() {
        return this.misses;
    }

    private void invalidate(BlockPos pos) {
        long key = SectionPos.asLong(pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4);
        Section section = this.sections.get(key);
        if (section != null) section.descriptors[sectionIndex(pos)] = -1;
    }

    private Section sectionAt(BlockPos pos) {
        long key = SectionPos.asLong(pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4);
        if (this.lastSectionKey == key) return this.lastSection;
        Section section = this.sections.get(key);
        if (section == null) {
            section = new Section();
            if (this.cacheCells && this.sections.size() < MAX_CACHED_SECTIONS) {
                this.sections.put(key, section);
            } else {
                this.cacheCells = false;
            }
        }
        this.lastSectionKey = key;
        this.lastSection = section;
        return section;
    }

    private static int sectionIndex(BlockPos pos) {
        return (pos.getX() & 15) | ((pos.getZ() & 15) << 4) | ((pos.getY() & 15) << 8);
    }

    private static final class Section {
        private final int[] descriptors = new int[4096];

        private Section() {
            Arrays.fill(this.descriptors, -1);
        }
    }

    private int descriptorFor(PathNavigationRegion region, BlockPos pos, BlockState state) {
        Integer existing = this.descriptors.get(state);
        if (existing != null) return existing;
        int index = this.descriptorCount++;
        if (index == this.rawPathTypes.length) {
            int next = this.rawPathTypes.length * 2;
            this.rawPathTypes = java.util.Arrays.copyOf(this.rawPathTypes, next);
            this.floorHeights = java.util.Arrays.copyOf(this.floorHeights, next);
        }
        this.rawPathTypes[index] = (byte)WalkNodeEvaluator.getPathTypeFromState(state).ordinal();
        VoxelShape shape = state.getCollisionShape(region, pos);
        this.floorHeights[index] = shape.isEmpty() ? 0.0F : (float)shape.max(Direction.Axis.Y);
        this.descriptors.put(state, index);
        return index;
    }
}
