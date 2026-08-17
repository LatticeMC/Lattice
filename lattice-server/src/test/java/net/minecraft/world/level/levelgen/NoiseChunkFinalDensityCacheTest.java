package net.minecraft.world.level.levelgen;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.blending.Blender;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class NoiseChunkFinalDensityCacheTest {
    private static HolderLookup.Provider registries;
    private static NoiseGeneratorSettings settings;

    @BeforeAll
    static void bootstrapRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        registries = VanillaRegistries.createLookup();
        settings = registries.lookupOrThrow(Registries.NOISE_SETTINGS)
            .getOrThrow(NoiseGeneratorSettings.OVERWORLD).value();
    }

    @Test
    void currentCellReadMatchesCacheComputeBitForBit() {
        NoiseChunk chunk = newChunk();
        NoiseChunk.CacheAllInCell cache = chunk.new CacheAllInCell(DensityFunctions.constant(91.0D));
        chunk.interpolating = true;

        for (int y = 0; y < chunk.cellHeight; y++) {
            for (int x = 0; x < chunk.cellWidth; x++) {
                for (int z = 0; z < chunk.cellWidth; z++) {
                    chunk.inCellX = x;
                    chunk.inCellY = y;
                    chunk.inCellZ = z;
                    double value = Double.longBitsToDouble(0x3ff0000000000000L + ((long)y << 12) + (x << 6) + z);
                    cache.values[((chunk.cellHeight - 1 - y) * chunk.cellWidth + x) * chunk.cellWidth + z] = value;
                    assertRawBits(cache.compute(chunk), cache.computeCurrentCell());
                }
            }
        }
    }

    @Test
    void currentCellReadPreservesBoundsAndInterpolationFallbacks() {
        NoiseChunk chunk = newChunk();
        NoiseChunk.CacheAllInCell cache = chunk.new CacheAllInCell(DensityFunctions.constant(37.5D));
        chunk.interpolating = true;

        for (int[] coordinates : new int[][] {
            {-1, 0, 0}, {chunk.cellWidth, 0, 0}, {0, -1, 0}, {0, chunk.cellHeight, 0}, {0, 0, -1}, {0, 0, chunk.cellWidth}
        }) {
            chunk.inCellX = coordinates[0];
            chunk.inCellY = coordinates[1];
            chunk.inCellZ = coordinates[2];
            assertRawBits(cache.compute(chunk), cache.computeCurrentCell());
        }

        chunk.interpolating = false;
        assertThrows(IllegalStateException.class, cache::computeCurrentCell);
        assertThrows(IllegalStateException.class, () -> cache.compute(chunk));
    }

    @Test
    void nonNoiseChunkContextStillUsesUnderlyingDensityFunction() {
        NoiseChunk chunk = newChunk();
        NoiseChunk.CacheAllInCell cache = chunk.new CacheAllInCell(DensityFunctions.constant(-12.25D));
        DensityFunction.FunctionContext context = new DensityFunction.SinglePointContext(17, 23, -5);

        assertRawBits(cache.compute(context), -12.25D);
        assertDoesNotThrow(() -> cache.compute(context));
    }

    @Test
    void selectCellYZPopulatesCacheBeforeCurrentCellRead() {
        NoiseChunk chunk = newChunk();
        NoiseChunk.CacheAllInCell cache = chunk.new CacheAllInCell(DensityFunctions.constant(6.25D));

        chunk.initializeForFirstCellX();
        chunk.advanceCellX(0);
        chunk.selectCellYZ(0, 0);
        chunk.inCellX = 0;
        chunk.inCellY = 0;
        chunk.inCellZ = 0;

        assertRawBits(cache.compute(chunk), cache.computeCurrentCell());
        assertRawBits(6.25D, cache.computeCurrentCell());
        chunk.stopInterpolation();
    }

    private static NoiseChunk newChunk() {
        return new NoiseChunk(
            1,
            RandomState.create(registries, NoiseGeneratorSettings.OVERWORLD, 0x4c415454494345L),
            0,
            0,
            settings.noiseSettings(),
            Beardifier.EMPTY,
            settings,
            fluidPicker(),
            Blender.empty()
        );
    }

    private static Aquifer.FluidPicker fluidPicker() {
        final Aquifer.FluidStatus lava = new Aquifer.FluidStatus(-54, Blocks.LAVA.defaultBlockState());
        final Aquifer.FluidStatus water = new Aquifer.FluidStatus(settings.seaLevel(), settings.defaultFluid());
        final Aquifer.FluidStatus air = new Aquifer.FluidStatus(DimensionType.MIN_Y * 2, Blocks.AIR.defaultBlockState());
        return (x, y, z) -> y < Math.min(-54, settings.seaLevel()) ? lava : (SharedConstants.DEBUG_DISABLE_FLUID_GENERATION ? air : water);
    }

    private static void assertRawBits(double expected, double actual) {
        assertEquals(Double.doubleToRawLongBits(expected), Double.doubleToRawLongBits(actual));
    }
}
