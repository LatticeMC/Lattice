package com.latticemc.lattice.mixin;

import net.minecraft.world.level.levelgen.synth.ImprovedNoise;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ImprovedNoise.class)
public interface ImprovedNoiseAccessor {
    @Accessor("p")
    byte[] lattice$getPermutation();
}
