package com.latticemc.lattice.nativelib;

public interface NativeNoiseChunkAccess {
    int lattice$cellStartBlockX();

    int lattice$cellStartBlockY();

    int lattice$cellStartBlockZ();

    int lattice$cellWidth();

    int lattice$cellHeight();

    int lattice$cellNoiseMinY();

    int lattice$firstCellZ();

    int lattice$cellCountY();

    int lattice$cellCountXZ();

    int lattice$inCellX();

    int lattice$inCellY();

    int lattice$inCellZ();

    boolean lattice$interpolating();

    boolean lattice$fillingCell();
}
