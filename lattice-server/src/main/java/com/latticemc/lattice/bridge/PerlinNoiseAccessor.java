package com.latticemc.lattice.bridge;

import it.unimi.dsi.fastutil.doubles.DoubleList;
import net.minecraft.world.level.levelgen.synth.ImprovedNoise;

public interface PerlinNoiseAccessor {
    ImprovedNoise[] lattice$getNoiseLevels();

    DoubleList lattice$getAmplitudes();

    double[] lattice$getAmplitudesArray();

    double lattice$getLowestFreqInputFactor();

    double lattice$getLowestFreqValueFactor();
}
