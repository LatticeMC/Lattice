package net.minecraft.world.level.biome;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.minecraft.core.BlockPos;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.Test;

class BiomeSurfaceTemperatureParityTest {
    @Test
    void columnTemperatureExpansionMatchesBiomeLookup() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        Biome biome = new Biome(
                new Biome.ClimateSettings(true, 0.0F, Biome.TemperatureModifier.FROZEN, 0.5F),
                null,
                null,
                null,
                null
        );

        int seaLevel = 63;
        for (int x = -97; x <= 97; x += 17) {
            for (int z = -83; z <= 83; z += 19) {
                float modifiedBaseTemperature = biome.lattice$modifiedBaseTemperature(new BlockPos(x, seaLevel + 17, z));
                float heightTemperatureNoise = Biome.lattice$temperatureHeightNoise(x, z);
                for (int y = -64; y <= 320; y += 11) {
                    BlockPos pos = new BlockPos(x, y, z);
                    boolean expected = !(Biome.lattice$heightAdjustedTemperature(
                            modifiedBaseTemperature, heightTemperatureNoise, y, seaLevel
                    ) >= 0.15F);
                    assertEquals(expected, biome.coldEnoughToSnow(pos, seaLevel), "temperature mismatch at " + pos);
                }
            }
        }
    }
}
