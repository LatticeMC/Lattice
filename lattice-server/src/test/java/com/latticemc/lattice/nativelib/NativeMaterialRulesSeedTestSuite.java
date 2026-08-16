package com.latticemc.lattice.nativelib;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import org.junit.jupiter.api.Test;

class NativeMaterialRulesSeedTestSuite {
    @Test
    void extractsOnlyTheExactXoroshiroFactory() {
        PositionalRandomFactory factory =
            new XoroshiroRandomSource.XoroshiroPositionalRandomFactory(11L, 29L);
        assertArrayEquals(new long[] {11L, 29L}, NativeMaterialRules.positionalSeeds(factory));
    }

    @Test
    void unsupportedLegacyFactorySafelyFallsBack() {
        assertNull(NativeMaterialRules.positionalSeeds(new LegacyRandomSource.LegacyPositionalRandomFactory(17L)));
    }
}
