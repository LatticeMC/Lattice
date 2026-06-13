package com.latticemc.lattice.mixin;

import it.unimi.dsi.fastutil.doubles.DoubleList;
import net.minecraft.world.level.levelgen.synth.ImprovedNoise;
import net.minecraft.world.level.levelgen.synth.PerlinNoise;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(PerlinNoise.class)
public interface PerlinNoiseAccessor {
    @Accessor("noiseLevels")
    ImprovedNoise[] lattice$getNoiseLevels();

    @Accessor("amplitudes")
    DoubleList lattice$getAmplitudes();

    @Accessor("lowestFreqInputFactor")
    double lattice$getLowestFreqInputFactor();

    @Accessor("lowestFreqValueFactor")
    double lattice$getLowestFreqValueFactor();
}
