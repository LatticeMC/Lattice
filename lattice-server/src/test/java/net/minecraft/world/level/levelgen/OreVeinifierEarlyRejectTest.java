package net.minecraft.world.level.levelgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class OreVeinifierEarlyRejectTest {
    @BeforeAll
    static void bootstrapRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void positionsOutsideBothVeinBandsSkipToggleDensity() {
        TrackingFunction toggle = new TrackingFunction(-1.0);
        NoiseChunk.BlockStateFiller filler = OreVeinifier.create(
            toggle,
            new TrackingFunction(1.0),
            new TrackingFunction(1.0),
            new XoroshiroRandomSource(1234L).forkPositional()
        );

        for (int y : new int[] {-64, -61, -7, -1, 51, 128, 319}) {
            BlockState result = filler.calculate(new DensityFunction.SinglePointContext(12, y, -34));
            assertNull(result);
        }
        assertEquals(0, toggle.computeCalls);

        for (int y : new int[] {-60, -8, 0, 50}) {
            filler.calculate(new DensityFunction.SinglePointContext(12, y, -34));
        }
        assertEquals(4, toggle.computeCalls);
    }

    private static final class TrackingFunction implements DensityFunction.SimpleFunction {
        private final double value;
        private int computeCalls;

        private TrackingFunction(double value) {
            this.value = value;
        }

        @Override
        public double compute(DensityFunction.FunctionContext context) {
            this.computeCalls++;
            return this.value;
        }

        @Override
        public double minValue() {
            return this.value;
        }

        @Override
        public double maxValue() {
            return this.value;
        }

        @Override
        public KeyDispatchDataCodec<? extends DensityFunction> codec() {
            throw new UnsupportedOperationException();
        }
    }
}
