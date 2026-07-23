package com.latticemc.lattice.nativelib;

import java.lang.reflect.Field;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;

public final class NativeOreVeinBlockStateFiller implements NoiseChunk.BlockStateFiller {
    private static final boolean ENABLED = Boolean.getBoolean("lattice.nativeOreVeinBlockStateFiller");
    private static final BlockState[] STATES = {
            null,
            Blocks.COPPER_ORE.defaultBlockState(),
            Blocks.DEEPSLATE_IRON_ORE.defaultBlockState(),
            Blocks.RAW_COPPER_BLOCK.defaultBlockState(),
            Blocks.RAW_IRON_BLOCK.defaultBlockState(),
            Blocks.GRANITE.defaultBlockState(),
            Blocks.TUFF.defaultBlockState(),
    };

    private final DensityFunction veinToggle;
    private final DensityFunction veinRidged;
    private final DensityFunction veinGap;
    private final long splitterSeedLo;
    private final long splitterSeedHi;

    private NativeOreVeinBlockStateFiller(DensityFunction veinToggle,
                                          DensityFunction veinRidged,
                                          DensityFunction veinGap,
                                          long splitterSeedLo,
                                          long splitterSeedHi) {
        this.veinToggle = veinToggle;
        this.veinRidged = veinRidged;
        this.veinGap = veinGap;
        this.splitterSeedLo = splitterSeedLo;
        this.splitterSeedHi = splitterSeedHi;
    }

    public static NoiseChunk.BlockStateFiller tryCreate(DensityFunction veinToggle,
                                                        DensityFunction veinRidged,
                                                        DensityFunction veinGap,
                                                        PositionalRandomFactory random) {
        if (!ENABLED || !NativeOreVeinSampler.isAvailable()) return null;
        final long[] seeds = tryExtractXoroshiroSeeds(random);
        if (seeds == null) return null;
        return new NativeOreVeinBlockStateFiller(veinToggle, veinRidged, veinGap, seeds[0], seeds[1]);
    }

    @Override
    public BlockState calculate(DensityFunction.FunctionContext context) {
        try {
            final double vt = veinToggle.compute(context);
            final double vr = veinRidged.compute(context);
            final double vg = veinGap.compute(context);
            final NativeOreVeinSampler.Result result = NativeOreVeinSampler.sample(
                    vt, vr, vg,
                    splitterSeedLo, splitterSeedHi,
                    context.blockX(), context.blockY(), context.blockZ());
            return STATES[result.ordinal()];
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static long[] tryExtractXoroshiroSeeds(PositionalRandomFactory random) {
        if (!(random instanceof XoroshiroRandomSource.XoroshiroPositionalRandomFactory)) return null;
        try {
            final Field lo = random.getClass().getDeclaredField("seedLo");
            final Field hi = random.getClass().getDeclaredField("seedHi");
            lo.setAccessible(true);
            hi.setAccessible(true);
            return new long[] { lo.getLong(random), hi.getLong(random) };
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }
}
