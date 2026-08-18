package com.latticemc.lattice.nativelib;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import org.junit.jupiter.api.Test;

class NativeMaterialRulesSeedTestSuite {
    @Test
    void extractsXoroshiroFactoryKindAndSeeds() {
        PositionalRandomFactory factory =
            new XoroshiroRandomSource.XoroshiroPositionalRandomFactory(11L, 29L);
        assertArrayEquals(new long[] {0L, 11L, 29L}, NativeMaterialRules.positionalSeeds(factory));
    }

    @Test
    void extractsLegacyFactoryKindAndSeed() {
        assertArrayEquals(new long[] {1L, 17L, 0L},
            NativeMaterialRules.positionalSeeds(new LegacyRandomSource.LegacyPositionalRandomFactory(17L)));
    }
}
