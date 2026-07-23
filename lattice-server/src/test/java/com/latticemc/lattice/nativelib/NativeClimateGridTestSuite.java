package com.latticemc.lattice.nativelib;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class NativeClimateGridTestSuite {
    @BeforeAll
    static void bootstrapRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void readsRootMajorXzyLayoutAndFallsBackExactly() {
        NativeClimateGrid.Buffer buffer = new NativeClimateGrid.Buffer();
        buffer.values = new double[2 * 3 * 4];
        int generation = buffer.nextGeneration();
        for (int x = 0; x < 2; x++) {
            for (int z = 0; z < 4; z++) {
                for (int y = 0; y < 3; y++) {
                    buffer.values[(x * 4 + z) * 3 + y] = 100.0 * x + 10.0 * z + y;
                }
            }
        }

        DensityFunction fallback = new DensityFunction.SimpleFunction() {
            @Override
            public double compute(DensityFunction.FunctionContext context) {
                return -99.0;
            }

            @Override
            public double minValue() {
                return -99.0;
            }

            @Override
            public double maxValue() {
                return -99.0;
            }

            @Override
            public KeyDispatchDataCodec<? extends DensityFunction> codec() {
                return null;
            }
        };
        NativeClimateGrid grid = new NativeClimateGrid(
                fallback, buffer, generation, 0,
                -8, -16, 20,
                2, 3, 4);

        assertEquals(112.0, grid.compute(new DensityFunction.SinglePointContext(-28, -56, 84)));
        assertEquals(-99.0, grid.compute(new DensityFunction.SinglePointContext(-27, -56, 84)));
        assertEquals(-99.0, grid.compute(new DensityFunction.SinglePointContext(-24, -52, 100)));

        buffer.nextGeneration();
        assertEquals(-99.0, grid.compute(new DensityFunction.SinglePointContext(-28, -56, 84)));
    }
}
