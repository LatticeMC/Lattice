package net.minecraft.world.level.levelgen;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Random;
import net.minecraft.util.Mth;
import org.junit.jupiter.api.Test;

class NoiseChunkSoaInterpolationTestSuite {
    private static final double[] BOUNDARY_DELTAS = {
        -0.0D, 0.0D, Double.MIN_VALUE, Math.nextDown(1.0D), 1.0D
    };

    @Test
    void stateOfArraysMatchesScalarInterpolatorBitsForSlicesAndAllInterpolationStages() {
        Random random = new Random(0x4c415454494345L);
        for (int interpolatorCount : new int[] {1, 2, 3, 8, 17}) {
            for (int sample = 0; sample < 64; sample++) {
                int yRows = 2 + random.nextInt(6);
                int zRows = 2 + random.nextInt(6);
                double[][][] startSlices = randomSlices(random, interpolatorCount, zRows, yRows);
                double[][][] endSlices = randomSlices(random, interpolatorCount, zRows, yRows);
                NoiseChunk.InterpolationState actual = new NoiseChunk.InterpolationState(interpolatorCount);

                int y = random.nextInt(yRows - 1);
                int z = random.nextInt(zRows - 1);
                double[][] expectedCorners = new double[8][interpolatorCount];
                for (int index = 0; index < interpolatorCount; index++) {
                    selectScalar(expectedCorners, index, startSlices[index], endSlices[index], y, z);
                    actual.selectCellYZ(
                        index,
                        startSlices[index][z][y],
                        startSlices[index][z + 1][y],
                        endSlices[index][z][y],
                        endSlices[index][z + 1][y],
                        startSlices[index][z][y + 1],
                        startSlices[index][z + 1][y + 1],
                        endSlices[index][z][y + 1],
                        endSlices[index][z + 1][y + 1]
                    );
                }
                assertCorners(expectedCorners, actual, interpolatorCount);

                for (double yDelta : deltas(random)) {
                    actual.updateForY(yDelta);
                    double[][] expectedXZ = scalarUpdateForY(expectedCorners, yDelta, interpolatorCount);
                    assertArrayBits(expectedXZ[0], actual.valueXZ00);
                    assertArrayBits(expectedXZ[1], actual.valueXZ10);
                    assertArrayBits(expectedXZ[2], actual.valueXZ01);
                    assertArrayBits(expectedXZ[3], actual.valueXZ11);

                    for (double xDelta : deltas(random)) {
                        actual.updateForX(xDelta);
                        double[][] expectedZ = scalarUpdateForX(expectedXZ, xDelta, interpolatorCount);
                        assertArrayBits(expectedZ[0], actual.valueZ0);
                        assertArrayBits(expectedZ[1], actual.valueZ1);

                        for (double zDelta : deltas(random)) {
                            actual.updateForZ(zDelta);
                            for (int index = 0; index < interpolatorCount; index++) {
                                double expectedPoint = Mth.lerp(zDelta, expectedZ[0][index], expectedZ[1][index]);
                                assertRawBits(expectedPoint, actual.value[index]);

                                double expectedFilling = Mth.lerp3(
                                    xDelta,
                                    yDelta,
                                    zDelta,
                                    expectedCorners[0][index],
                                    expectedCorners[2][index],
                                    expectedCorners[4][index],
                                    expectedCorners[6][index],
                                    expectedCorners[1][index],
                                    expectedCorners[3][index],
                                    expectedCorners[5][index],
                                    expectedCorners[7][index]
                                );
                                assertRawBits(expectedFilling, actual.computeFillingCell(index, xDelta, yDelta, zDelta));
                            }
                        }
                    }
                }
            }
        }
    }

    private static double[][][] randomSlices(Random random, int interpolatorCount, int zRows, int yRows) {
        double[][][] slices = new double[interpolatorCount][zRows][yRows];
        for (int index = 0; index < interpolatorCount; index++) {
            for (int z = 0; z < zRows; z++) {
                for (int y = 0; y < yRows; y++) {
                    slices[index][z][y] = random.nextDouble(-10_000.0D, 10_000.0D);
                }
            }
        }
        return slices;
    }

    private static double[] deltas(Random random) {
        return new double[] {
            BOUNDARY_DELTAS[0], BOUNDARY_DELTAS[1], BOUNDARY_DELTAS[2], BOUNDARY_DELTAS[3], BOUNDARY_DELTAS[4], random.nextDouble()
        };
    }

    private static void selectScalar(double[][] corners, int index, double[][] start, double[][] end, int y, int z) {
        corners[0][index] = start[z][y];
        corners[1][index] = start[z + 1][y];
        corners[2][index] = end[z][y];
        corners[3][index] = end[z + 1][y];
        corners[4][index] = start[z][y + 1];
        corners[5][index] = start[z + 1][y + 1];
        corners[6][index] = end[z][y + 1];
        corners[7][index] = end[z + 1][y + 1];
    }

    private static double[][] scalarUpdateForY(double[][] corners, double y, int interpolatorCount) {
        double[][] values = new double[4][interpolatorCount];
        for (int index = 0; index < interpolatorCount; index++) {
            values[0][index] = Mth.lerp(y, corners[0][index], corners[4][index]);
            values[1][index] = Mth.lerp(y, corners[2][index], corners[6][index]);
            values[2][index] = Mth.lerp(y, corners[1][index], corners[5][index]);
            values[3][index] = Mth.lerp(y, corners[3][index], corners[7][index]);
        }
        return values;
    }

    private static double[][] scalarUpdateForX(double[][] xz, double x, int interpolatorCount) {
        double[][] values = new double[2][interpolatorCount];
        for (int index = 0; index < interpolatorCount; index++) {
            values[0][index] = Mth.lerp(x, xz[0][index], xz[1][index]);
            values[1][index] = Mth.lerp(x, xz[2][index], xz[3][index]);
        }
        return values;
    }

    private static void assertCorners(double[][] expected, NoiseChunk.InterpolationState actual, int interpolatorCount) {
        for (int index = 0; index < interpolatorCount; index++) {
            assertRawBits(expected[0][index], actual.noise000[index]);
            assertRawBits(expected[1][index], actual.noise001[index]);
            assertRawBits(expected[2][index], actual.noise100[index]);
            assertRawBits(expected[3][index], actual.noise101[index]);
            assertRawBits(expected[4][index], actual.noise010[index]);
            assertRawBits(expected[5][index], actual.noise011[index]);
            assertRawBits(expected[6][index], actual.noise110[index]);
            assertRawBits(expected[7][index], actual.noise111[index]);
        }
    }

    private static void assertArrayBits(double[] expected, double[] actual) {
        assertEquals(expected.length, actual.length);
        for (int index = 0; index < expected.length; index++) {
            assertRawBits(expected[index], actual[index]);
        }
    }

    private static void assertRawBits(double expected, double actual) {
        assertEquals(Double.doubleToRawLongBits(expected), Double.doubleToRawLongBits(actual));
    }
}
