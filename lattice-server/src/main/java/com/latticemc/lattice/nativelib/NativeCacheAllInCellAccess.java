package com.latticemc.lattice.nativelib;

import net.minecraft.world.level.levelgen.DensityFunction;

public interface NativeCacheAllInCellAccess {
    DensityFunction lattice$noiseFiller();

    double[] lattice$values();

    double[] lattice$columnValues();

    void lattice$setColumnValues(double[] values);

    int lattice$columnCellX();

    void lattice$setColumnCellX(int cellX);
}
