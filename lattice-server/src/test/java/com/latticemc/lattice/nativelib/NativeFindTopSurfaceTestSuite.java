package com.latticemc.lattice.nativelib;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class NativeFindTopSurfaceTestSuite {
    @BeforeAll
    static void loadNativeLibrary() {
        LatticeNative.load();
        assertTrue(LatticeNative.isLoaded(), LatticeNative.failureReason());
    }

    @Test
    void compilesAndEvaluatesFindTopSurfaceGrid() {
        DensityFunction density = DensityFunctions.yClampedGradient(0, 100, 1.0, -1.0);
        DensityFunction function = DensityFunctions.findTopSurface(density, DensityFunctions.constant(97.0), -64, 8);
        double[] nativeValues = new double[12];

        assertTrue(NativeDensityFunction.tryFillGrid(
                nativeValues,
                function,
                -12.0,
                0.0,
                20.0,
                4.0,
                1.0,
                4.0,
                -3,
                5,
                3,
                1,
                4));

        int index = 0;
        for (int z = 20; z <= 32; z += 4) {
            for (int x = -12; x <= -4; x += 4) {
                double expected = function.compute(new DensityFunction.SinglePointContext(x, 0, z));
                assertEquals(expected, nativeValues[index++], 0.0);
            }
        }
    }
}
