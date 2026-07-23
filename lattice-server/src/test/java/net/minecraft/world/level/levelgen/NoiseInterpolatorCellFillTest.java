package net.minecraft.world.level.levelgen;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import java.util.Random;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.Mth;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class NoiseInterpolatorCellFillTest {
    @BeforeAll
    static void bootstrapRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void directCellFillMatchesScalarLerp3BitForBit() {
        Random random = new Random(0x4c415454494345L);
        for (int cellWidth : new int[] {1, 2, 4, 8}) {
            for (int cellHeight : new int[] {1, 2, 4, 8}) {
                for (int sample = 0; sample < 128; sample++) {
                    double[] noise = new double[8];
                    for (int i = 0; i < noise.length; i++) {
                        noise[i] = random.nextDouble(-2.0, 2.0);
                    }

                    double[] expected = scalarFill(cellWidth, cellHeight, noise);
                    double[] actual = new double[expected.length];
                    NoiseChunk.NoiseInterpolator.lattice$fillCellArray(
                        actual,
                        cellWidth,
                        cellHeight,
                        noise[0],
                        noise[1],
                        noise[2],
                        noise[3],
                        noise[4],
                        noise[5],
                        noise[6],
                        noise[7]
                    );

                    assertArrayEquals(expected, actual);
                }
            }
        }
    }

    private static double[] scalarFill(int cellWidth, int cellHeight, double[] noise) {
        double[] values = new double[cellWidth * cellWidth * cellHeight];
        int index = 0;
        for (int y = cellHeight - 1; y >= 0; y--) {
            for (int x = 0; x < cellWidth; x++) {
                for (int z = 0; z < cellWidth; z++) {
                    values[index++] = Mth.lerp3(
                        (double)x / cellWidth,
                        (double)y / cellHeight,
                        (double)z / cellWidth,
                        noise[0],
                        noise[1],
                        noise[2],
                        noise[3],
                        noise[4],
                        noise[5],
                        noise[6],
                        noise[7]
                    );
                }
            }
        }
        return values;
    }
}
