package com.latticemc.lattice.bridge;

import java.util.function.Predicate;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;

public interface HeightmapAccessor {
    Predicate<BlockState> lattice$isOpaque();

    void lattice$setRawData(ChunkAccess chunk, Heightmap.Types type, long[] data);
}
