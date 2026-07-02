package com.latticemc.lattice.mixin;

import com.latticemc.lattice.nativelib.NativeDoublePerlinNoise;

public interface NativeNormalNoiseAccess {
    NativeDoublePerlinNoise lattice$getNativeDoublePerlinNoise();
}
