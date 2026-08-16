package net.minecraft.world.level.levelgen;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Random;
import net.minecraft.util.RandomSource;
import org.junit.jupiter.api.Test;

class XoroshiroAquiferOffsetsTest {
    @Test
    void packedOffsetsMatchVanillaRandomStream() {
        Random random = new Random(0x4c415454494345L);
        for (int factoryIndex = 0; factoryIndex < 32; factoryIndex++) {
            XoroshiroRandomSource.XoroshiroPositionalRandomFactory factory =
                new XoroshiroRandomSource.XoroshiroPositionalRandomFactory(random.nextLong(), random.nextLong());

            for (int sample = 0; sample < 10_000; sample++) {
                int x = random.nextInt();
                int y = random.nextInt();
                int z = random.nextInt();
                int packed = factory.lattice$sampleAquiferOffsets(x, y, z);
                RandomSource expected = factory.at(x, y, z);
                assertEquals(expected.nextInt(10), packed & 15);
                assertEquals(expected.nextInt(9), packed >> 4 & 15);
                assertEquals(expected.nextInt(10), packed >> 8 & 15);
            }
        }
    }

    @Test
    void nativeSeedAccessorsPreservePositionalRandomOracle() {
        XoroshiroRandomSource.XoroshiroPositionalRandomFactory factory =
            new XoroshiroRandomSource.XoroshiroPositionalRandomFactory(0x0123456789ABCDEFL, 0xFEDCBA9876543210L);

        assertEquals(0x0123456789ABCDEFL, factory.lattice$seedLo());
        assertEquals(0xFEDCBA9876543210L, factory.lattice$seedHi());

        int[][] coordinates = {{0, 0, 0}, {17, -31, 9}, {-2048, 73, 4096}};
        for (int[] coordinate : coordinates) {
            RandomSource expected = factory.at(coordinate[0], coordinate[1], coordinate[2]);
            RandomSource reconstructed = new XoroshiroRandomSource(
                net.minecraft.util.Mth.getSeed(coordinate[0], coordinate[1], coordinate[2]) ^ factory.lattice$seedLo(),
                factory.lattice$seedHi());
            for (int sample = 0; sample < 16; sample++) {
                assertEquals(expected.nextLong(), reconstructed.nextLong());
            }
        }
    }
}
