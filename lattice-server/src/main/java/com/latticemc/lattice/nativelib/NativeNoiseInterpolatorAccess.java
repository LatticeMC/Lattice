package com.latticemc.lattice.nativelib;

public interface NativeNoiseInterpolatorAccess {
    void lattice$setNativeSlot(int slot);

    int lattice$nativeSlot();

    double[] lattice$flatSlice0();

    double[] lattice$flatSlice1();

    void lattice$copyFlatRow(boolean slice0, int zRow, double[] values, int yRows, int zRows);

    double[] lattice$sliceRow(boolean slice0, int zRow);

    void lattice$selectCellYZ(int y, int z);
}
