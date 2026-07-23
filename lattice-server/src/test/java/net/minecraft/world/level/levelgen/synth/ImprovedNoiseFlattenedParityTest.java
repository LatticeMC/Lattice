package net.minecraft.world.level.levelgen.synth;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Field;
import java.util.Random;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import org.junit.jupiter.api.Test;

class ImprovedNoiseFlattenedParityTest {
    @Test
    void flattenedSamplerMatchesVanillaOperationsBitForBit() throws ReflectiveOperationException {
        ImprovedNoise noise = new ImprovedNoise(new XoroshiroRandomSource(0x4c415454494345L));
        Field field = ImprovedNoise.class.getDeclaredField("p");
        field.setAccessible(true);
        byte[] permutation = (byte[])field.get(noise);
        Random random = new Random(0x4e4f495345L);

        for (int sample = 0; sample < 100_000; sample++) {
            double x = random.nextDouble(-30_000_000.0, 30_000_000.0);
            double y = random.nextDouble(-2048.0, 2048.0);
            double z = random.nextDouble(-30_000_000.0, 30_000_000.0);
            double yScale = sample % 3 == 0 ? 0.0 : random.nextDouble(0.01, 8.0);
            double yMax = sample % 5 == 0 ? 0.0 : random.nextDouble(-16.0, 16.0);
            double expected = vanillaNoise(noise, permutation, x, y, z, yScale, yMax);
            assertEquals(Double.doubleToRawLongBits(expected), Double.doubleToRawLongBits(noise.noise(x, y, z, yScale, yMax)));
        }
    }

    private static double vanillaNoise(ImprovedNoise noise, byte[] permutation, double x, double y, double z, double yScale, double yMax) {
        double shiftedX = x + noise.xo;
        double shiftedY = y + noise.yo;
        double shiftedZ = z + noise.zo;
        int gridX = Mth.floor(shiftedX);
        int gridY = Mth.floor(shiftedY);
        int gridZ = Mth.floor(shiftedZ);
        double deltaX = shiftedX - gridX;
        double deltaY = shiftedY - gridY;
        double deltaZ = shiftedZ - gridZ;
        double yFudge = 0.0;
        if (yScale != 0.0) {
            double yLimit = yMax >= 0.0 && yMax < deltaY ? yMax : deltaY;
            yFudge = Mth.floor(yLimit / yScale + 1.0E-7F) * yScale;
        }

        return vanillaSample(permutation, gridX, gridY, gridZ, deltaX, deltaY - yFudge, deltaZ, deltaY);
    }

    private static double vanillaSample(byte[] p, int x, int y, int z, double dx, double dy, double dz, double originalY) {
        int x0 = p(p, x);
        int x1 = p(p, x + 1);
        int xy00 = p(p, x0 + y);
        int xy01 = p(p, x0 + y + 1);
        int xy10 = p(p, x1 + y);
        int xy11 = p(p, x1 + y + 1);
        double d000 = grad(p(p, xy00 + z), dx, dy, dz);
        double d100 = grad(p(p, xy10 + z), dx - 1.0, dy, dz);
        double d010 = grad(p(p, xy01 + z), dx, dy - 1.0, dz);
        double d110 = grad(p(p, xy11 + z), dx - 1.0, dy - 1.0, dz);
        double d001 = grad(p(p, xy00 + z + 1), dx, dy, dz - 1.0);
        double d101 = grad(p(p, xy10 + z + 1), dx - 1.0, dy, dz - 1.0);
        double d011 = grad(p(p, xy01 + z + 1), dx, dy - 1.0, dz - 1.0);
        double d111 = grad(p(p, xy11 + z + 1), dx - 1.0, dy - 1.0, dz - 1.0);
        return Mth.lerp3(
            Mth.smoothstep(dx), Mth.smoothstep(originalY), Mth.smoothstep(dz), d000, d100, d010, d110, d001, d101, d011, d111
        );
    }

    private static int p(byte[] permutation, int index) {
        return permutation[index & 0xFF] & 0xFF;
    }

    private static double grad(int gradient, double x, double y, double z) {
        return SimplexNoise.dot(SimplexNoise.GRADIENT[gradient & 15], x, y, z);
    }
}
