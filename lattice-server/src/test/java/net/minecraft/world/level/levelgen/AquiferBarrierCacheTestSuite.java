package net.minecraft.world.level.levelgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Random;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class AquiferBarrierCacheTestSuite {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void pressureMatchesLocalCacheIncludingNanAndSampleReuse() throws Exception {
        DensityFunction barrier = mock(DensityFunction.class);
        double[] noise = {0.25};
        int[] samples = {0};
        when(barrier.compute(any())).thenAnswer(invocation -> {
            samples[0]++;
            return noise[0];
        });
        NoiseRouter router = mock(NoiseRouter.class);
        when(router.barrierNoise()).thenReturn(barrier);
        Aquifer aquifer = Aquifer.create(mock(NoiseChunk.class), new ChunkPos(0, 0), router,
            new LegacyRandomSource(0L).forkPositional(), -64, 384,
            (x, y, z) -> new Aquifer.FluidStatus(63, Blocks.WATER.defaultBlockState()));
        Method pressure = aquifer.getClass().getDeclaredMethod("calculatePressure", DensityFunction.FunctionContext.class,
            Aquifer.FluidStatus.class, Aquifer.FluidStatus.class);
        pressure.setAccessible(true);
        Field cache = aquifer.getClass().getDeclaredField("barrierNoiseValue");
        cache.setAccessible(true);
        Random random = new Random(0xba77);
        for (double value : new double[] {0.0, -0.0, 0.25, -0.5, Double.NaN}) {
            noise[0] = value;
            for (int sample = 0; sample < 1000; sample++) {
                int y = random.nextInt(257) - 128;
                int first = random.nextInt(257) - 128;
                int second = random.nextInt(257) - 128;
                var context = new DensityFunction.SinglePointContext(-17, y, 64);
                var a = new Aquifer.FluidStatus(first, Blocks.WATER.defaultBlockState());
                var b = new Aquifer.FluidStatus(second, Blocks.WATER.defaultBlockState());
                cache.setDouble(aquifer, Double.NaN);
                double expected = originalPressure(y, first, second, value);
                double actual = (double)pressure.invoke(aquifer, context, a, b);
                assertEquals(Double.doubleToRawLongBits(expected), Double.doubleToRawLongBits(actual));
            }
        }
        noise[0] = 0.25;
        samples[0] = 0;
        cache.setDouble(aquifer, Double.NaN);
        var context = new DensityFunction.SinglePointContext(0, 10, 0);
        var first = new Aquifer.FluidStatus(10, Blocks.WATER.defaultBlockState());
        var second = new Aquifer.FluidStatus(12, Blocks.WATER.defaultBlockState());
        pressure.invoke(aquifer, context, first, second);
        pressure.invoke(aquifer, context, first, second);
        assertEquals(1, samples[0]);
        cache.setDouble(aquifer, Double.NaN);
        pressure.invoke(aquifer, context, first, second);
        assertEquals(2, samples[0]);
        noise[0] = Double.NaN;
        cache.setDouble(aquifer, Double.NaN);
        pressure.invoke(aquifer, context, first, second);
        pressure.invoke(aquifer, context, first, second);
        assertEquals(4, samples[0]);
    }

    @Test
    void consecutiveSubstanceEvaluationsResetBarrierCacheAutomatically() throws Exception {
        DensityFunction barrier = mock(DensityFunction.class);
        double[] noise = {0.25};
        int[] samples = {0};
        when(barrier.compute(any())).thenAnswer(invocation -> {
            samples[0]++;
            return noise[0];
        });
        NoiseRouter router = mock(NoiseRouter.class);
        when(router.barrierNoise()).thenReturn(barrier);
        Aquifer aquifer = Aquifer.create(mock(NoiseChunk.class), new ChunkPos(0, 0), router,
            new LegacyRandomSource(0L).forkPositional(), -64, 384,
            (x, y, z) -> new Aquifer.FluidStatus(63, Blocks.WATER.defaultBlockState()));
        Field statuses = aquifer.getClass().getDeclaredField("aquiferCache");
        statuses.setAccessible(true);
        var fluids = (Aquifer.FluidStatus[])statuses.get(aquifer);
        java.util.Arrays.setAll(fluids, index -> new Aquifer.FluidStatus(10 + 2 * (index & 1), Blocks.WATER.defaultBlockState()));
        Field cache = aquifer.getClass().getDeclaredField("barrierNoiseValue");
        cache.setAccessible(true);
        DensityFunction.FunctionContext sampled = null;
        for (int x = 0; x < 16 && sampled == null; x++) {
            for (int z = 0; z < 16 && sampled == null; z++) {
                var context = new DensityFunction.SinglePointContext(x, 10, z);
                int before = samples[0];
                aquifer.computeSubstance(context, -1000.0);
                if (samples[0] > before) {
                    assertEquals(before + 1, samples[0]);
                    sampled = context;
                }
            }
        }
        assertNotNull(sampled, "The deterministic fixture must reach the pressure sampling branch");
        int before = samples[0];
        noise[0] = 0.75;
        aquifer.computeSubstance(sampled, -1000.0);
        assertEquals(before + 1, samples[0]);
        assertEquals(0.75, cache.getDouble(aquifer));
        aquifer.computeSubstance(sampled, 1.0);
        assertEquals(before + 1, samples[0]);
        noise[0] = -0.5;
        aquifer.computeSubstance(sampled, -1000.0);
        assertEquals(before + 2, samples[0]);
        assertEquals(-0.5, cache.getDouble(aquifer));
    }

    private static double originalPressure(int y, int first, int second, double noise) {
        int difference = Math.abs(first - second);
        if (difference == 0) {
            return 0.0;
        }
        double midpoint = 0.5 * (first + second);
        double distance = y + 0.5 - midpoint;
        double edgeDistance = difference / 2.0 - Math.abs(distance);
        double pressure;
        if (distance > 0.0) {
            double shifted = 0.0 + edgeDistance;
            pressure = shifted > 0.0 ? shifted / 1.5 : shifted / 2.5;
        } else {
            double shifted = 3.0 + edgeDistance;
            pressure = shifted > 0.0 ? shifted / 3.0 : shifted / 10.0;
        }
        double barrier = !(pressure < -2.0) && !(pressure > 2.0) ? noise : 0.0;
        return 2.0 * (barrier + pressure);
    }
}
