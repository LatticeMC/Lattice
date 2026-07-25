package com.latticemc.lattice.bridge;

import net.minecraft.world.level.levelgen.synth.NormalNoise;

public interface SurfaceSystemAccessor {
    int lattice$seaLevel();

    NormalNoise lattice$surfaceNoise();

    NormalNoise lattice$surfaceSecondaryNoise();
}
