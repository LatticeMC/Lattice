package com.latticemc.lattice.bridge;

import com.latticemc.lattice.nativelib.SurfaceSystemAccess;
import net.minecraft.core.Registry;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;

public class SurfaceSystemAccessImpl implements SurfaceSystemAccess {
    private final SurfaceSystemCallbacks callbacks;
    private final Registry<Biome> biomes;

    public SurfaceSystemAccessImpl(SurfaceSystemCallbacks callbacks, Registry<Biome> biomes) {
        this.callbacks = callbacks;
        this.biomes = biomes;
    }

    @Override
    public int seaLevel() {
        return callbacks.getSeaLevel();
    }

    @Override
    public int biomeId(Biome biome) {
        return biomes.getId(biome);
    }

    @Override
    public double surfaceNoiseValue(int x, int z) {
        return callbacks.getSurfaceNoiseValue(x, z);
    }

    @Override
    public double surfaceSecondaryValue(int x, int z) {
        return callbacks.getSurfaceSecondaryValue(x, z);
    }

    @Override
    public BlockState bandlands(int x, int y, int z) {
        return callbacks.getBandlands(x, y, z);
    }
}
