package com.latticemc.lattice.world;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

/** Optional random tick entry point for blocks that can reuse the selected chunk and section. */
public interface LatticeTickingBlock {
    void lattice$randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random,
                             LevelChunk chunk, LevelChunkSection section);
}
