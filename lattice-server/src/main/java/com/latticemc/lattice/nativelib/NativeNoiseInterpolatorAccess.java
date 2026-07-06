package com.latticemc.lattice.nativelib;

import java.lang.foreign.MemorySegment;

public interface NativeNoiseInterpolatorAccess {
    void lattice$setNativeSlot(int slot);

    int lattice$nativeSlot();

    double[] lattice$flatSlice0();

    double[] lattice$flatSlice1();

    void lattice$copyFlatRow(boolean slice0, int zRow, double[] values, int yRows, int zRows);

    MemorySegment lattice$nativeFlatRow(boolean slice0, int zRow, int yRows, int zRows);

    boolean lattice$nativeFlatReadable();

    void lattice$markNativeFlatReadable();

    double[] lattice$sliceRow(boolean slice0, int zRow);

    void lattice$selectCellYZ(int y, int z);
}
