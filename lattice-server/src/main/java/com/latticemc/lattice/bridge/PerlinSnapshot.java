package com.latticemc.lattice.bridge;

public record PerlinSnapshot(double[] origins,
                      byte[] permutations,
                      double[] amplitudes,
                      double lacunarity,
                      double persistence) {}
