package com.latticemc.lattice.mixin;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import net.minecraft.core.BlockPos;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ChunkBlockColumnTestSuite {
    @BeforeAll
    static void bootstrapRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void protoChunkFastPathCachesSectionAndUpdatesSideEffects() {
        ProtoChunk chunk = mock(ProtoChunk.class);
        LevelHeightAccessor heightAccessor = mock(LevelHeightAccessor.class);
        LevelChunkSection section = mock(LevelChunkSection.class);
        Heightmap heightmap = mock(Heightmap.class);
        BlockState state = mock(BlockState.class);
        FluidState fluid = mock(FluidState.class);

        when(chunk.getHeightAccessorForGeneration()).thenReturn(heightAccessor);
        when(chunk.lattice$surfaceHeightmaps()).thenReturn(new Heightmap[]{heightmap});
        when(chunk.isOutsideBuildHeight(65)).thenReturn(false);
        when(chunk.isOutsideBuildHeight(66)).thenReturn(false);
        when(chunk.getSectionIndex(65)).thenReturn(3);
        when(chunk.getSectionIndex(66)).thenReturn(3);
        when(chunk.getSection(3)).thenReturn(section);
        when(heightAccessor.isInsideBuildHeight(65)).thenReturn(true);
        when(section.hasOnlyAir()).thenReturn(false);
        when(section.getBlockState(2, 2, 15)).thenReturn(state);
        when(state.is(Blocks.AIR)).thenReturn(false);
        when(state.getFluidState()).thenReturn(fluid);
        when(fluid.isEmpty()).thenReturn(false);

        ChunkBlockColumn column = new ChunkBlockColumn(chunk, new BlockPos.MutableBlockPos());
        column.setColumn(34, -17);
        column.setBlock(65, state);
        assertSame(state, column.getBlock(66));

        verify(chunk).getSection(3);
        verify(section).setBlockState(2, 1, 15, state);
        verify(heightmap).update(2, 65, 15, state);
        verify(chunk).lattice$markPosForPostprocessing(34, 65, -17);
        verify(chunk, never()).setBlockState(any(BlockPos.class), any(BlockState.class));
    }

    @Test
    void unavailableFastPathFallsBackToChunkApi() {
        ProtoChunk chunk = mock(ProtoChunk.class);
        LevelHeightAccessor heightAccessor = mock(LevelHeightAccessor.class);
        BlockState state = mock(BlockState.class);
        FluidState fluid = mock(FluidState.class);

        when(chunk.getHeightAccessorForGeneration()).thenReturn(heightAccessor);
        when(chunk.lattice$surfaceHeightmaps()).thenReturn(null);
        when(chunk.isOutsideBuildHeight(70)).thenReturn(false);
        when(heightAccessor.isInsideBuildHeight(70)).thenReturn(true);
        when(state.getFluidState()).thenReturn(fluid);
        when(fluid.isEmpty()).thenReturn(true);

        ChunkBlockColumn column = new ChunkBlockColumn(chunk, new BlockPos.MutableBlockPos());
        column.setColumn(-33, 48);
        column.setBlock(70, state);

        ArgumentCaptor<BlockPos> position = ArgumentCaptor.forClass(BlockPos.class);
        verify(chunk).setBlockState(position.capture(), org.mockito.ArgumentMatchers.same(state));
        BlockPos written = position.getValue();
        org.junit.jupiter.api.Assertions.assertEquals(-33, written.getX());
        org.junit.jupiter.api.Assertions.assertEquals(70, written.getY());
        org.junit.jupiter.api.Assertions.assertEquals(48, written.getZ());
        verify(chunk, never()).lattice$markPosForPostprocessing(-33, 70, 48);
    }
}
