package com.latticemc.lattice.world;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;

/** 已否决生产接入的实验参考；仅供测试及独立高度微基准使用，原方法仍是 oracle。 */
public final class FluidSectionHeight {

    public static float getHeight(FluidState state, Level world, BlockPos pos,
                                  LevelChunkSection[] sections, int minSection) {
        Fluid type = state.getType();
        if (type != Fluids.WATER && type != Fluids.FLOWING_WATER
            && type != Fluids.LAVA && type != Fluids.FLOWING_LAVA) {
            return state.getHeight(world, pos);
        }
        BlockPos above = pos.above();
        int index = (above.getY() >> 4) - minSection;
        if (!world.isInValidBounds(above) || index < 0 || index >= sections.length) {
            return state.getHeight(world, pos);
        }
        LevelChunkSection section = sections[index];
        FluidState upper = section.hasOnlyAir() ? Fluids.EMPTY.defaultFluidState()
            : section.states.get((above.getY() & 15) << 8 | (above.getZ() & 15) << 4 | above.getX() & 15).getFluidState();
        return type.isSame(upper.getType()) ? 1.0F : state.getOwnHeight();
    }

    private FluidSectionHeight() { }
}
