package com.latticemc.lattice.mixin;

import com.latticemc.lattice.nativelib.NativeCacheAllInCellAccess;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

@Mixin(targets = "net.minecraft.world.level.levelgen.NoiseChunk$CacheAllInCell")
public abstract class CacheAllInCellMixin implements NativeCacheAllInCellAccess {
    @Shadow @Final DensityFunction noiseFiller;
    @Shadow @Final double[] values;
    @Unique private double[] lattice$columnValues;
    @Unique private int lattice$columnCellX = Integer.MIN_VALUE;

    @Override
    public DensityFunction lattice$noiseFiller() {
        return this.noiseFiller;
    }

    @Override
    public double[] lattice$values() {
        return this.values;
    }

    @Override
    public double[] lattice$columnValues() {
        return this.lattice$columnValues;
    }

    @Override
    public void lattice$setColumnValues(double[] values) {
        this.lattice$columnValues = values;
    }

    @Override
    public int lattice$columnCellX() {
        return this.lattice$columnCellX;
    }

    @Override
    public void lattice$setColumnCellX(int cellX) {
        this.lattice$columnCellX = cellX;
    }
}
