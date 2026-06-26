package com.latticemc.lattice.mixin;

import net.minecraft.world.level.block.state.BlockState;

public interface SurfaceSystemCallbacks {
    int getSeaLevel();
    double getSurfaceNoiseValue(int x, int z);
    double getSurfaceSecondaryValue(int x, int z);
    BlockState getBandlands(int x, int y, int z);
}
