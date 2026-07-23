package com.latticemc.lattice.mixin;

import net.minecraft.core.Registry;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.SurfaceRules;
import net.minecraft.world.level.levelgen.WorldGenerationContext;

public interface SurfaceSystemCallbacks {
    boolean lattice$tryBuildSurface(
            RandomState randomState,
            BiomeManager biomeManager,
            Registry<Biome> biomes,
            boolean useLegacyRandomSource,
            WorldGenerationContext context,
            ChunkAccess chunk,
            NoiseChunk noiseChunk,
            SurfaceRules.RuleSource ruleSource);

    int getSeaLevel();
    double getSurfaceNoiseValue(int x, int z);
    double getSurfaceSecondaryValue(int x, int z);
    BlockState getBandlands(int x, int y, int z);
}
