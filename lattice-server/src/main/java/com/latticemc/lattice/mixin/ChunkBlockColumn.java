package com.latticemc.lattice.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.BlockColumn;
import net.minecraft.world.level.chunk.ChunkAccess;

public class ChunkBlockColumn implements BlockColumn {
    private final ChunkAccess chunk;
    private final BlockPos.MutableBlockPos pos;

    public ChunkBlockColumn(ChunkAccess chunk, BlockPos.MutableBlockPos pos) {
        this.chunk = chunk;
        this.pos = pos;
    }

    @Override
    public BlockState getBlock(int y) {
        return chunk.getBlockState(pos.setY(y));
    }

    @Override
    public void setBlock(int y, BlockState state) {
        LevelHeightAccessor heightAccessor = chunk.getHeightAccessorForGeneration();
        if (heightAccessor.isInsideBuildHeight(y)) {
            chunk.setBlockState(pos.setY(y), state);
            if (!state.getFluidState().isEmpty()) chunk.markPosForPostprocessing(pos);
        }
    }
}
