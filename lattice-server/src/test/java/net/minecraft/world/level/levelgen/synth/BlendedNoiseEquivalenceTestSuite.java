package net.minecraft.world.level.levelgen.synth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import java.lang.reflect.Field;
import java.util.Random;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class BlendedNoiseEquivalenceTestSuite {
    @BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

    @Test void originalOperationTreeMatchesRawBits() throws Exception {
        Random random = new Random(48235);
        for (double scale : new double[]{0.25, 1.0, 12.0, -0.0, Double.NaN, Double.POSITIVE_INFINITY}) {
            BlendedNoise actual = new BlendedNoise(new XoroshiroRandomSource(88912), scale, scale, 80.0, 160.0, 8.0);
            Original expected = new Original(actual);
            for (int i = 0; i < 200; i++) {
                var context = new DensityFunction.SinglePointContext(random.nextInt(), random.nextInt(-64, 320), random.nextInt());
                assertEquals(Double.doubleToRawLongBits(expected.compute(context)), Double.doubleToRawLongBits(actual.compute(context)));
            }
        }
    }

    // 冻结本轮修改前的运算树；共享噪声对象，仅比较此次表达式变换。
    private static final class Original {
        private final PerlinNoise minLimitNoise;
        private final PerlinNoise maxLimitNoise;
        private final PerlinNoise mainNoise;
        private final double xzMultiplier;
        private final double yMultiplier;
        private final double xzFactor;
        private final double yFactor;
        private final double smearScaleMultiplier;
        Original(BlendedNoise value) throws Exception {
            this.minLimitNoise = (PerlinNoise) read(value, "minLimitNoise");
            this.maxLimitNoise = (PerlinNoise) read(value, "maxLimitNoise");
            this.mainNoise = (PerlinNoise) read(value, "mainNoise");
            this.xzMultiplier = (double) read(value, "xzMultiplier");
            this.yMultiplier = (double) read(value, "yMultiplier");
            this.xzFactor = (double) read(value, "xzFactor");
            this.yFactor = (double) read(value, "yFactor");
            this.smearScaleMultiplier = (double) read(value, "smearScaleMultiplier");
        }
        private static Object read(BlendedNoise value, String name) throws Exception {
            Field field = BlendedNoise.class.getDeclaredField(name); field.setAccessible(true); return field.get(value);
        }
        double compute(DensityFunction.FunctionContext context) {
        double d = context.blockX() * this.xzMultiplier;
        double d1 = context.blockY() * this.yMultiplier;
        double d2 = context.blockZ() * this.xzMultiplier;
        double d3 = d / this.xzFactor;
        double d4 = d1 / this.yFactor;
        double d5 = d2 / this.xzFactor;
        double d6 = this.yMultiplier * this.smearScaleMultiplier;
        double d7 = d6 / this.yFactor;
        double d8 = 0.0;
        double d9 = 0.0;
        double d10 = 0.0;
        boolean flag = true;
        double d11 = 1.0;

        for (int i = 0; i < 8; i++) {
            ImprovedNoise octaveNoise = this.mainNoise.getOctaveNoise(i);
            if (octaveNoise != null) {
                d10 += octaveNoise.noise(PerlinNoise.wrap(d3 * d11), PerlinNoise.wrap(d4 * d11), PerlinNoise.wrap(d5 * d11), d7 * d11, d4 * d11) / d11;
            }

            d11 /= 2.0;
        }

        double d12 = (d10 / 10.0 + 1.0) / 2.0;
        boolean flag1 = d12 >= 1.0;
        boolean flag2 = d12 <= 0.0;
        d11 = 1.0;

        for (int i1 = 0; i1 < 16; i1++) {
            double d13 = PerlinNoise.wrap(d * d11);
            double d14 = PerlinNoise.wrap(d1 * d11);
            double d15 = PerlinNoise.wrap(d2 * d11);
            double d16 = d6 * d11;
            if (!flag1) {
                ImprovedNoise octaveNoise1 = this.minLimitNoise.getOctaveNoise(i1);
                if (octaveNoise1 != null) {
                    d8 += octaveNoise1.noise(d13, d14, d15, d16, d1 * d11) / d11;
                }
            }

            if (!flag2) {
                ImprovedNoise octaveNoise1 = this.maxLimitNoise.getOctaveNoise(i1);
                if (octaveNoise1 != null) {
                    d9 += octaveNoise1.noise(d13, d14, d15, d16, d1 * d11) / d11;
                }
            }

            d11 /= 2.0;
        }

        return Mth.clampedLerp(d12, d8 / 512.0, d9 / 512.0) / 128.0;
    }

    }
}
