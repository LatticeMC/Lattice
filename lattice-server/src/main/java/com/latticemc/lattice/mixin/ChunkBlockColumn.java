package com.latticemc.lattice.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.BlockColumn;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.levelgen.Heightmap;

public class ChunkBlockColumn implements BlockColumn {
    private final ChunkAccess chunk;
    private final BlockPos.MutableBlockPos pos;
    private final LevelHeightAccessor heightAccessor;
    private final ProtoChunk protoChunk;
    private final Heightmap[] surfaceHeightmaps;
    private int blockX;
    private int blockZ;
    private int localX;
    private int localZ;
    private int cachedSectionIndex = Integer.MIN_VALUE;
    private LevelChunkSection cachedSection;

    public ChunkBlockColumn(ChunkAccess chunk, BlockPos.MutableBlockPos pos) {
        this.chunk = chunk;
        this.pos = pos;
        this.heightAccessor = chunk.getHeightAccessorForGeneration();
        this.protoChunk = chunk instanceof ProtoChunk proto ? proto : null;
        this.surfaceHeightmaps = this.protoChunk == null ? null : this.protoChunk.lattice$surfaceHeightmaps();
    }

    public void setColumn(int blockX, int blockZ) {
        this.blockX = blockX;
        this.blockZ = blockZ;
        this.localX = blockX & 15;
        this.localZ = blockZ & 15;
        this.pos.setX(blockX).setZ(blockZ);
        this.cachedSectionIndex = Integer.MIN_VALUE;
        this.cachedSection = null;
    }

    @Override
    public BlockState getBlock(int y) {
        if (this.chunk.isOutsideBuildHeight(y)) {
            return Blocks.VOID_AIR.defaultBlockState();
        }
        LevelChunkSection section = this.sectionFor(y);
        return section.hasOnlyAir() ? Blocks.AIR.defaultBlockState() : section.getBlockState(this.localX, y & 15, this.localZ);
    }

    @Override
    public void setBlock(int y, BlockState state) {
        if (!this.heightAccessor.isInsideBuildHeight(y) || this.chunk.isOutsideBuildHeight(y)) {
            return;
        }
        if (this.surfaceHeightmaps != null) {
            LevelChunkSection section = this.sectionFor(y);
            if (section.hasOnlyAir() && state.is(Blocks.AIR)) {
                return;
            }
            section.setBlockState(this.localX, y & 15, this.localZ, state);
            for (Heightmap heightmap : this.surfaceHeightmaps) {
                heightmap.update(this.localX, y, this.localZ, state);
            }
            if (!state.getFluidState().isEmpty()) {
                this.protoChunk.lattice$markPosForPostprocessing(this.blockX, y, this.blockZ);
            }
            return;
        }

        this.chunk.setBlockState(this.pos.set(this.blockX, y, this.blockZ), state);
        if (!state.getFluidState().isEmpty()) {
            this.chunk.markPosForPostprocessing(this.pos);
        }
    }

    private LevelChunkSection sectionFor(int y) {
        int sectionIndex = this.chunk.getSectionIndex(y);
        if (sectionIndex != this.cachedSectionIndex) {
            this.cachedSectionIndex = sectionIndex;
            this.cachedSection = this.chunk.getSection(sectionIndex);
        }
        return this.cachedSection;
    }
}
