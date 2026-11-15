package net.minecraft.world.level.levelgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.latticemc.lattice.worldgen.SeedProtectionRandomSource;
import java.lang.reflect.Method;
import java.util.Random;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadType;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ThreadConfinedWorldgenRandomTestSuite {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void mixedRandomApisReseedingAndForksMatchLegacy() {
        for (long seed : new long[] {0L, 1L, -1L, Long.MIN_VALUE, Long.MAX_VALUE, 0x123456789abcL}) {
            compare(new LegacyRandomSource(seed), new SingleThreadedRandomSource(seed));
            WorldgenRandom expected = new WorldgenRandom(new LegacyRandomSource(seed));
            WorldgenRandom actual = new WorldgenRandom(new SingleThreadedRandomSource(seed));
            compare(expected, actual);
            assertEquals(expected.getCount(), actual.getCount());
        }
    }

    @Test
    void worldgenSeedApisAndNoiseInitializationMatchLegacy() {
        WorldgenRandom expected = new WorldgenRandom(new LegacyRandomSource(0L));
        WorldgenRandom actual = new WorldgenRandom(new SingleThreadedRandomSource(0L));
        Random inputs = new Random(0x1c6);
        for (int sample = 0; sample < 100; sample++) {
            long seed = inputs.nextLong();
            int x = inputs.nextInt();
            int z = inputs.nextInt();
            assertEquals(expected.setDecorationSeed(seed, x, z), actual.setDecorationSeed(seed, x, z));
            expected.setFeatureSeed(seed, x, z);
            actual.setFeatureSeed(seed, x, z);
            assertEquals(expected.nextLong(), actual.nextLong());
            expected.setLargeFeatureSeed(seed, x, z);
            actual.setLargeFeatureSeed(seed, x, z);
            assertEquals(expected.nextLong(), actual.nextLong());
            expected.setLargeFeatureWithSalt(seed, x, z, 12345);
            actual.setLargeFeatureWithSalt(seed, x, z, 12345);
            assertEquals(expected.nextLong(), actual.nextLong());
        }
        NormalNoise oldNoise = NormalNoise.create(new WorldgenRandom(new LegacyRandomSource(42L)), -4, 1.0);
        NormalNoise newNoise = NormalNoise.create(new WorldgenRandom(new SingleThreadedRandomSource(42L)), -4, 1.0);
        for (int index = 0; index < 1000; index++) {
            double x = inputs.nextInt(60000001) - 30000000;
            double y = inputs.nextInt(4096) - 2048;
            double z = inputs.nextInt(60000001) - 30000000;
            assertDoubleBits(oldNoise.getValue(x, y, z), newNoise.getValue(x, y, z));
        }
    }

    @Test
    void actualStructurePlacementMatchesUnmodifiedFormula() {
        Random inputs = new Random(0x57a);
        for (RandomSpreadType spread : RandomSpreadType.values()) {
            for (int spacing : new int[] {2, 16, 31, 4096}) {
                int separation = spacing / 2;
                int salt = inputs.nextInt();
                var placement = new RandomSpreadStructurePlacement(spacing, separation, spread, salt);
                for (int sample = 0; sample < 1000; sample++) {
                    long seed = inputs.nextLong();
                    int x = inputs.nextInt(3750001) - 1875000;
                    int z = inputs.nextInt(3750001) - 1875000;
                    int regionX = Math.floorDiv(x, spacing);
                    int regionZ = Math.floorDiv(z, spacing);
                    WorldgenRandom old = new WorldgenRandom(new LegacyRandomSource(0L));
                    old.setLargeFeatureWithSalt(seed, regionX, regionZ, salt);
                    int offsetX = spread.evaluate(old, spacing - separation);
                    int offsetZ = spread.evaluate(old, spacing - separation);
                    assertEquals(new ChunkPos(regionX * spacing + offsetX, regionZ * spacing + offsetZ),
                        placement.getPotentialStructureChunk(seed, x, z));
                }
            }
        }
    }

    @Test
    void powerOfTwoFloorDivMatchesArithmeticShiftAtBoundaries() {
        Random random = new Random(0x64);
        int[] boundaries = {Integer.MIN_VALUE, Integer.MIN_VALUE + 1, -65, -64, -63, -17, -16, -15,
            -1, 0, 1, 15, 16, 17, 63, 64, 65, Integer.MAX_VALUE - 1, Integer.MAX_VALUE};
        for (int value : boundaries) {
            checkDivision(value);
        }
        for (int sample = 0; sample < 100000; sample++) {
            checkDivision(random.nextInt());
        }
    }

    @Test
    void protectedStructurePlacementKeepsAesStreamAndNeverReseedsIt() throws Exception {
        Method method = RandomSpreadStructurePlacement.class.getDeclaredMethod("lattice$getPotentialStructureChunk",
            long.class, int.class, int.class, RandomSource.class);
        method.setAccessible(true);
        Random inputs = new Random(0xae5);
        for (RandomSpreadType spread : RandomSpreadType.values()) {
            var placement = new RandomSpreadStructurePlacement(31, 15, spread, 12345);
            for (int sample = 0; sample < 100; sample++) {
                byte[] key = new byte[32];
                inputs.nextBytes(key);
                RandomSource expectedSource = new SeedProtectionRandomSource(key);
                RandomSource actualSource = new SeedProtectionRandomSource(key);
                WorldgenRandom expected = new WorldgenRandom(expectedSource);
                int x = inputs.nextInt(3750001) - 1875000;
                int z = inputs.nextInt(3750001) - 1875000;
                int offsetX = spread.evaluate(expected, 16);
                int offsetZ = spread.evaluate(expected, 16);
                assertEquals(new ChunkPos(Math.floorDiv(x, 31) * 31 + offsetX, Math.floorDiv(z, 31) * 31 + offsetZ),
                    method.invoke(placement, inputs.nextLong(), x, z, actualSource));
                assertEquals(expectedSource.nextLong(), actualSource.nextLong());
            }
        }
    }

    private static void checkDivision(int value) {
        assertEquals(Math.floorDiv(value, 64), value >> 6);
        assertEquals(Math.floorDiv(value, 16), value >> 4);
    }

    private static void compare(RandomSource expected, RandomSource actual) {
        for (int round = 0; round < 3; round++) {
            for (int draw = 0; draw < 1000; draw++) {
                assertEquals(expected.nextInt(), actual.nextInt());
                assertEquals(expected.nextInt(1), actual.nextInt(1));
                assertEquals(expected.nextInt(1073741825), actual.nextInt(1073741825));
                assertEquals(expected.nextLong(), actual.nextLong());
                assertEquals(expected.nextBoolean(), actual.nextBoolean());
                assertEquals(Float.floatToRawIntBits(expected.nextFloat()), Float.floatToRawIntBits(actual.nextFloat()));
                assertDoubleBits(expected.nextDouble(), actual.nextDouble());
                assertDoubleBits(expected.nextGaussian(), actual.nextGaussian());
            }
            expected.nextGaussian();
            actual.nextGaussian();
            expected.setSeed(round - 123456789L);
            actual.setSeed(round - 123456789L);
            assertDoubleBits(expected.nextGaussian(), actual.nextGaussian());
            compareDraws(expected.fork(), actual.fork());
            PositionalRandomFactory oldFactory = expected.forkPositional();
            PositionalRandomFactory newFactory = actual.forkPositional();
            compareDraws(oldFactory.at(-123, 45, 678), newFactory.at(-123, 45, 678));
            compareDraws(oldFactory.fromHashOf("lattice:parity"), newFactory.fromHashOf("lattice:parity"));
        }
        assertThrows(IllegalArgumentException.class, () -> expected.nextInt(0));
        assertThrows(IllegalArgumentException.class, () -> actual.nextInt(0));
    }

    private static void compareDraws(RandomSource expected, RandomSource actual) {
        for (int index = 0; index < 100; index++) {
            assertEquals(expected.nextLong(), actual.nextLong());
        }
    }

    private static void assertDoubleBits(double expected, double actual) {
        assertEquals(Double.doubleToRawLongBits(expected), Double.doubleToRawLongBits(actual));
    }
}
