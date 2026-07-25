package net.minecraft.world.level.levelgen;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import java.util.Arrays;
import java.util.Random;
import org.junit.jupiter.api.Test;

class AquiferCenterCacheParityTest {
    private static final int CENTER_COUNT = 12;
    private static final int CACHE_SLOTS = 8;
    private static final int CACHE_STRIDE = CENTER_COUNT * 4;

    @Test
    void cachedCenterSelectionMatchesVanillaTraversal() {
        Random random = new Random(0x41515549464552L);
        CenterSource source = new CenterSource(24, 41, 24);
        CachedCenters cached = new CachedCenters(source);

        int[] boundaries = {-49, -48, -33, -32, -17, -16, -1, 0, 1, 15, 16, 17, 31, 32, 47, 48};
        for (int x : boundaries) {
            for (int y : boundaries) {
                for (int z : boundaries) {
                    int[] expected = vanillaNearest(source, x, y, z);
                    assertArrayEquals(expected, cached.nearest(x, y, z));
                    assertArrayEquals(expected, cached.lazyFourthNearest(x, y, z));
                }
            }
        }

        for (int sample = 0; sample < 100_000; sample++) {
            int x = random.nextInt(193) - 96;
            int y = random.nextInt(385) - 192;
            int z = random.nextInt(193) - 96;
            int[] expected = vanillaNearest(source, x, y, z);
            assertArrayEquals(expected, cached.nearest(x, y, z));
            assertArrayEquals(expected, cached.lazyFourthNearest(x, y, z));
        }
    }

    @Test
    void directMappedSlotEvictionDoesNotChangeResults() {
        CenterSource source = new CenterSource(32, 48, 24);
        CachedCenters cached = new CachedCenters(source);

        for (int round = 0; round < 2_000; round++) {
            int baseGridX = round % 24 - 12;
            int baseGridY = round % 37 - 18;
            int baseGridZ = round % 20 - 10;
            int x = (baseGridX << 4) + 5;
            int y = baseGridY * 12 - 1;
            int z = (baseGridZ << 4) + 5;
            int[] expected = vanillaNearest(source, x, y, z);
            assertArrayEquals(expected, cached.nearest(x, y, z));
            assertArrayEquals(expected, cached.lazyFourthNearest(x, y, z));
        }
    }

    @Test
    void equalDistancesKeepVanillaTraversalOrder() {
        int[] distances = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE};
        int[] indices = new int[4];
        for (int index = 0; index < 6; index++) {
            insertNearest(distances, indices, 25, index);
        }
        assertArrayEquals(new int[] {25, 5, 25, 4, 25, 3, 25, 2}, interleave(distances, indices));
    }

    private static int[] vanillaNearest(CenterSource source, int x, int y, int z) {
        int baseGridX = (x - 5) >> 4;
        int baseGridY = Math.floorDiv(y + 1, 12);
        int baseGridZ = (z - 5) >> 4;
        int[] nearest = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE};
        int[] indices = new int[4];

        for (int gridOffsetX = 0; gridOffsetX <= 1; gridOffsetX++) {
            for (int gridOffsetY = -1; gridOffsetY <= 1; gridOffsetY++) {
                for (int gridOffsetZ = 0; gridOffsetZ <= 1; gridOffsetZ++) {
                    int gridX = baseGridX + gridOffsetX;
                    int gridY = baseGridY + gridOffsetY;
                    int gridZ = baseGridZ + gridOffsetZ;
                    int index = source.index(gridX, gridY, gridZ);
                    int[] center = source.center(index, gridX, gridY, gridZ);
                    insertNearest(nearest, indices, squaredDistance(center, x, y, z), index);
                }
            }
        }
        return interleave(nearest, indices);
    }

    private static void insertNearest(int[] nearest, int[] indices, int distance, int index) {
        if (nearest[0] >= distance) {
            System.arraycopy(nearest, 0, nearest, 1, 3);
            System.arraycopy(indices, 0, indices, 1, 3);
            nearest[0] = distance;
            indices[0] = index;
        } else if (nearest[1] >= distance) {
            nearest[3] = nearest[2];
            nearest[2] = nearest[1];
            nearest[1] = distance;
            indices[3] = indices[2];
            indices[2] = indices[1];
            indices[1] = index;
        } else if (nearest[2] >= distance) {
            nearest[3] = nearest[2];
            nearest[2] = distance;
            indices[3] = indices[2];
            indices[2] = index;
        } else if (nearest[3] >= distance) {
            nearest[3] = distance;
            indices[3] = index;
        }
    }

    private static int squaredDistance(int[] center, int x, int y, int z) {
        int deltaX = center[0] - x;
        int deltaY = center[1] - y;
        int deltaZ = center[2] - z;
        return deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ;
    }

    private static int[] interleave(int[] distances, int[] indices) {
        int[] result = new int[8];
        for (int i = 0; i < 4; i++) {
            result[i * 2] = distances[i];
            result[i * 2 + 1] = indices[i];
        }
        return result;
    }

    private static final class CachedCenters {
        private final CenterSource source;
        private final int[] keys = new int[CACHE_SLOTS];
        private final int[] centers = new int[CACHE_SLOTS * CACHE_STRIDE];

        private CachedCenters(CenterSource source) {
            this.source = source;
            Arrays.fill(this.keys, -1);
        }

        private int[] nearest(int x, int y, int z) {
            int baseGridX = (x - 5) >> 4;
            int baseGridY = Math.floorDiv(y + 1, 12);
            int baseGridZ = (z - 5) >> 4;
            int offset = this.getCenters(baseGridX, baseGridY, baseGridZ);
            int[] nearest = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE};
            int[] indices = new int[4];

            for (int i = 0; i < CENTER_COUNT; i++) {
                int deltaX = this.centers[offset++] - x;
                int deltaY = this.centers[offset++] - y;
                int deltaZ = this.centers[offset++] - z;
                int index = this.centers[offset++];
                insertNearest(nearest, indices, deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ, index);
            }
            return interleave(nearest, indices);
        }

        private int[] lazyFourthNearest(int x, int y, int z) {
            int baseGridX = (x - 5) >> 4;
            int baseGridY = Math.floorDiv(y + 1, 12);
            int baseGridZ = (z - 5) >> 4;
            int offset = this.getCenters(baseGridX, baseGridY, baseGridZ);
            int centerOffset = offset;
            int[] nearest = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE};
            int[] indices = new int[3];

            for (int i = 0; i < CENTER_COUNT; i++) {
                int deltaX = this.centers[offset++] - x;
                int deltaY = this.centers[offset++] - y;
                int deltaZ = this.centers[offset++] - z;
                int index = this.centers[offset++];
                int distance = deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ;
                if (nearest[0] >= distance) {
                    nearest[2] = nearest[1];
                    nearest[1] = nearest[0];
                    nearest[0] = distance;
                    indices[2] = indices[1];
                    indices[1] = indices[0];
                    indices[0] = index;
                } else if (nearest[1] >= distance) {
                    nearest[2] = nearest[1];
                    nearest[1] = distance;
                    indices[2] = indices[1];
                    indices[1] = index;
                } else if (nearest[2] >= distance) {
                    nearest[2] = distance;
                    indices[2] = index;
                }
            }

            int fourthDistance = Integer.MAX_VALUE;
            int fourthIndex = 0;
            for (int i = 0; i < CENTER_COUNT; i++) {
                int deltaX = this.centers[centerOffset++] - x;
                int deltaY = this.centers[centerOffset++] - y;
                int deltaZ = this.centers[centerOffset++] - z;
                int index = this.centers[centerOffset++];
                if (index != indices[0] && index != indices[1] && index != indices[2]) {
                    int distance = deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ;
                    if (fourthDistance >= distance) {
                        fourthDistance = distance;
                        fourthIndex = index;
                    }
                }
            }

            return new int[] {
                nearest[0], indices[0], nearest[1], indices[1], nearest[2], indices[2], fourthDistance, fourthIndex
            };
        }

        private int getCenters(int baseGridX, int baseGridY, int baseGridZ) {
            int key = this.source.index(baseGridX, baseGridY, baseGridZ);
            int slot = key & (CACHE_SLOTS - 1);
            int offset = slot * CACHE_STRIDE;
            if (this.keys[slot] == key) {
                return offset;
            }

            int writeOffset = offset;
            for (int gridOffsetX = 0; gridOffsetX <= 1; gridOffsetX++) {
                for (int gridOffsetY = -1; gridOffsetY <= 1; gridOffsetY++) {
                    for (int gridOffsetZ = 0; gridOffsetZ <= 1; gridOffsetZ++) {
                        int gridX = baseGridX + gridOffsetX;
                        int gridY = baseGridY + gridOffsetY;
                        int gridZ = baseGridZ + gridOffsetZ;
                        int index = this.source.index(gridX, gridY, gridZ);
                        int[] center = this.source.center(index, gridX, gridY, gridZ);
                        this.centers[writeOffset++] = center[0];
                        this.centers[writeOffset++] = center[1];
                        this.centers[writeOffset++] = center[2];
                        this.centers[writeOffset++] = index;
                    }
                }
            }
            this.keys[slot] = key;
            return offset;
        }
    }

    private static final class CenterSource {
        private final int minGridX;
        private final int gridSizeX;
        private final int minGridY;
        private final int gridSizeY;
        private final int minGridZ;
        private final int gridSizeZ;
        private final int[][] centers;

        private CenterSource(int gridSizeX, int gridSizeY, int gridSizeZ) {
            this.minGridX = -gridSizeX / 2;
            this.gridSizeX = gridSizeX;
            this.minGridY = -gridSizeY / 2;
            this.gridSizeY = gridSizeY;
            this.minGridZ = -gridSizeZ / 2;
            this.gridSizeZ = gridSizeZ;
            this.centers = new int[gridSizeX * gridSizeY * gridSizeZ][];
        }

        private int index(int gridX, int gridY, int gridZ) {
            int localX = gridX - this.minGridX;
            int localY = gridY - this.minGridY;
            int localZ = gridZ - this.minGridZ;
            if (localX < 0 || localX >= this.gridSizeX || localY < 0 || localY >= this.gridSizeY || localZ < 0 || localZ >= this.gridSizeZ) {
                throw new AssertionError("grid coordinate outside modeled aquifer cache");
            }
            return (localY * this.gridSizeZ + localZ) * this.gridSizeX + localX;
        }

        private int[] center(int index, int gridX, int gridY, int gridZ) {
            int[] center = this.centers[index];
            if (center == null) {
                long seed = mix(gridX, gridY, gridZ);
                center = new int[] {
                    (gridX << 4) + (int)Math.floorMod(seed, 10),
                    gridY * 12 + (int)Math.floorMod(seed >>> 21, 9),
                    (gridZ << 4) + (int)Math.floorMod(seed >>> 42, 10)
                };
                this.centers[index] = center;
            }
            return center;
        }

        private static long mix(int x, int y, int z) {
            long value = x * 0x9E3779B97F4A7C15L ^ y * 0xC2B2AE3D27D4EB4FL ^ z * 0x165667B19E3779F9L;
            value ^= value >>> 30;
            value *= 0xBF58476D1CE4E5B9L;
            value ^= value >>> 27;
            value *= 0x94D049BB133111EBL;
            return value ^ value >>> 31;
        }
    }
}
