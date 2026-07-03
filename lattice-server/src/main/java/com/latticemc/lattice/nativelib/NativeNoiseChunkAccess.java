package com.latticemc.lattice.nativelib;

public interface NativeNoiseChunkAccess {
    int lattice$cellStartBlockX();

    int lattice$cellStartBlockY();

    int lattice$cellStartBlockZ();

    int lattice$cellWidth();

    int lattice$cellHeight();

    int lattice$cellNoiseMinY();

    int lattice$firstCellZ();
}
