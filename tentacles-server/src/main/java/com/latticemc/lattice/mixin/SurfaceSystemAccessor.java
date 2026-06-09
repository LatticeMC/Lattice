package com.latticemc.lattice.mixin;

import net.minecraft.world.level.levelgen.SurfaceSystem;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(SurfaceSystem.class)
public interface SurfaceSystemAccessor {
    @Accessor("seaLevel")
    int lattice$seaLevel();

    @Accessor("surfaceNoise")
    NormalNoise lattice$surfaceNoise();

    @Accessor("surfaceSecondaryNoise")
    NormalNoise lattice$surfaceSecondaryNoise();
}
