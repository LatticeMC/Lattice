package com.latticemc.lattice.nativelib;

public interface NativeNoiseInterpolatorAccess {
    void lattice$setNativeSlot(int slot);

    int lattice$nativeSlot();

    double[][] lattice$slice0();

    double[][] lattice$slice1();

    void lattice$selectCellYZ(int y, int z);
}
