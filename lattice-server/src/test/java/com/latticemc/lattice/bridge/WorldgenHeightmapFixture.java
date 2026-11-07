package com.latticemc.lattice.bridge;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.Strategy;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.ticks.ProtoChunkTicks;

final class WorldgenHeightmapFixture {
    enum Pattern { EMPTY, UNIFORM, MIXED, MIXED_WITH_FLOOR, GLOBAL }

    final ProtoChunk chunk;
    final Set<Heightmap.Types> types;
    final EnumMap<Heightmap.Types, int[]> expected = new EnumMap<>(Heightmap.Types.class);

    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    WorldgenHeightmapFixture(int minY, int sectionCount, Pattern pattern, Set<Heightmap.Types> types) {
        this.types = Set.copyOf(types);
        LevelChunkSection[] sections = new LevelChunkSection[sectionCount];
        for (int index = 0; index < sectionCount; index++) {
            BlockState initial = pattern == Pattern.UNIFORM ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState();
            sections[index] = new LevelChunkSection(
                    new PalettedContainer<>(initial, Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY)), null);
        }
        // Every section is supplied; the constructor never consults the biome/container factory.
        chunk = new ProtoChunk(new ChunkPos(minY, sectionCount), UpgradeData.EMPTY, sections,
                new ProtoChunkTicks<>(), new ProtoChunkTicks<>(),
                LevelHeightAccessor.create(minY, sectionCount * 16), null, null);
        if (pattern == Pattern.MIXED_WITH_FLOOR) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) set(x, 0, z, Blocks.STONE.defaultBlockState());
            }
        }
        if (pattern == Pattern.MIXED || pattern == Pattern.MIXED_WITH_FLOOR || pattern == Pattern.GLOBAL) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    int column = x + z * 16;
                    if (column % 7 == 0) continue;
                    int base = Math.floorMod(column * 31, sectionCount * 16 - 5);
                    set(x, base, z, Blocks.STONE.defaultBlockState());
                    set(x, base + 1, z, Blocks.WATER.defaultBlockState());
                    set(x, base + 2, z, Blocks.OAK_LEAVES.defaultBlockState());
                    set(x, base + 3, z, Blocks.SHORT_GRASS.defaultBlockState());
                }
            }
        }
        if (pattern == Pattern.GLOBAL) {
            ArrayList<BlockState> states = new ArrayList<>();
            for (BlockState state : Block.BLOCK_STATE_REGISTRY) {
                if (!state.isAir()) states.add(state);
                if (states.size() == 300) break;
            }
            if (states.size() < 300) throw new AssertionError("Insufficient registry states");
            for (int index = 0; index < states.size(); index++) {
                sections[sectionCount - 1].setBlockState(index & 15, index >>> 8, (index >>> 4) & 15, states.get(index));
            }
            if (PalettedContainerAccess.dataOf(sections[sectionCount - 1].getStates()).storage().getBits() <= 8) {
                throw new AssertionError("Fixture did not reach global palette");
            }
        }
        for (Heightmap.Types type : types) {
            chunk.getOrCreateHeightmapUnprimed(type);
            expected.put(type, scan(type));
        }
    }

    private void set(int x, int relativeY, int z, BlockState state) {
        chunk.getSections()[relativeY >>> 4].setBlockState(x, relativeY & 15, z, state);
    }

    // Independent oracle: no native masks, packing or heightmap population helper.
    private int[] scan(Heightmap.Types type) {
        int[] heights = new int[256];
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int firstAvailable = chunk.getMinY();
                for (int y = chunk.getMinY() + chunk.getHeight() - 1; y >= chunk.getMinY(); y--) {
                    BlockState state = chunk.getSections()[chunk.getSectionIndex(y)].getBlockState(x, y & 15, z);
                    if (type.isOpaque().test(state)) {
                        firstAvailable = y + 1;
                        break;
                    }
                }
                heights[x + z * 16] = firstAvailable;
            }
        }
        return heights;
    }

    void assertParity() {
        for (Heightmap.Types type : types) {
            Heightmap map = chunk.getOrCreateHeightmapUnprimed(type);
            for (int column = 0; column < 256; column++) {
                int actual = map.getFirstAvailable(column & 15, column >>> 4);
                if (actual != expected.get(type)[column]) {
                    throw new AssertionError(type + " column=" + column + " minY=" + chunk.getMinY()
                            + " expected=" + expected.get(type)[column] + " actual=" + actual);
                }
            }
        }
    }

    void resetHeightmaps() {
        for (Heightmap.Types type : types) {
            java.util.Arrays.fill(chunk.getOrCreateHeightmapUnprimed(type).getRawData(), 0L);
        }
    }

    long checksum() {
        long sum = 0;
        for (Heightmap.Types type : types) {
            sum += chunk.getOrCreateHeightmapUnprimed(type).getFirstAvailable(13, 7);
        }
        return sum;
    }
}
