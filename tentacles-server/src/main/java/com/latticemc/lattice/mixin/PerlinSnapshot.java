package com.latticemc.lattice.mixin;

public record PerlinSnapshot(double[] origins,
                      byte[] permutations,
                      double[] amplitudes,
                      double lacunarity,
                      double persistence) {}
