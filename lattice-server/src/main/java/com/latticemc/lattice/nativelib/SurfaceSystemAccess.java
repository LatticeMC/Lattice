package com.latticemc.lattice.nativelib;

import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;

public interface SurfaceSystemAccess {
    int seaLevel();
    int biomeId(Biome biome);
    double surfaceNoiseValue(int x, int z);
    double surfaceSecondaryValue(int x, int z);
    BlockState bandlands(int x, int y, int z);
}
