package com.latticemc.lattice.nativelib;

public interface NativeNoiseInterpolatorAccess {
    void lattice$setNativeSlot(int slot);

    int lattice$nativeSlot();

    double[][] lattice$slice0();

    double[][] lattice$slice1();

    double[] lattice$flatSlice0();

    double[] lattice$flatSlice1();

    void lattice$copyFlatRow(boolean slice0, int zRow, double[] values, int yRows);

    void lattice$selectCellYZ(int y, int z);
}
