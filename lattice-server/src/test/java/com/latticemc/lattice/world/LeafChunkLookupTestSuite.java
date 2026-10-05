package com.latticemc.lattice.world;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.HashMap;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.EmptyLevelChunk;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.bukkit.craftbukkit.block.CraftBlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class LeafChunkLookupTestSuite {
    @BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

    @Test void normalAndNegativeCoordinatesReadRealChunkPalettes() {
        LevelChunk chunk = RandomTickTestSupport.chunk(-1, -2, 1, 0);
        RandomTickTestSupport.field(chunk, ChunkAccess.class, "levelHeightAccessor", LevelHeightAccessor.create(0, 16));
        chunk.getSection(0).setBlockState(15, 3, 14, Blocks.STONE.defaultBlockState());
        ServerLevel world = world(chunk);
        BlockPos pos = new BlockPos(-1, 3, -18);
        assertSame(Blocks.STONE.defaultBlockState(), world.getBlockState(pos));
        assertSame(Blocks.AIR.defaultBlockState(), world.getBlockState(new BlockPos(-2, 3, -18)));
        verify(world.getChunkSource(), times(2)).getChunk(-1, -2, ChunkStatus.FULL, true);
    }

    @Test void emptyChunkKeepsVoidAirOverride() {
        EmptyLevelChunk chunk = RandomTickTestSupport.allocate(EmptyLevelChunk.class);
        assertSame(Blocks.VOID_AIR.defaultBlockState(), world(chunk).getBlockState(new BlockPos(2, 4, 6)));
    }

    @Test void invalidBoundsDoNotLookUpChunks() {
        ServerLevel world = world(null);
        BlockPos pos = new BlockPos(40_000_000, -500, 0);
        when(world.isInValidBounds(pos)).thenReturn(false);
        assertSame(Blocks.VOID_AIR.defaultBlockState(), world.getBlockState(pos));
        verifyNoInteractions(world.getChunkSource());
    }

    @Test void capturedTreeStatePrecedesBoundsAndChunkLookup() {
        ServerLevel world = world(null);
        BlockPos pos = new BlockPos(-3, 64, 8);
        CraftBlockState captured = mock(CraftBlockState.class);
        when(captured.getHandle()).thenReturn(Blocks.OAK_LOG.defaultBlockState());
        RandomTickTestSupport.field(world, Level.class, "capturedBlockStates", new HashMap<>(java.util.Map.of(pos, captured)));
        world.captureTreeGeneration = true;
        assertSame(Blocks.OAK_LOG.defaultBlockState(), world.getBlockState(pos));
        verify(world, never()).isInValidBounds(any());
        verifyNoInteractions(world.getChunkSource());
    }

    @Test void missingFullChunkKeepsOriginalFailure() {
        ServerLevel world = world(null);
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> world.getBlockState(BlockPos.ZERO));
        assertEquals("Should always be able to create a chunk!", error.getMessage());
    }

    private static ServerLevel world(ChunkAccess chunk) {
        ServerLevel world = mock(ServerLevel.class);
        ServerChunkCache source = mock(ServerChunkCache.class);
        when(world.getChunkSource()).thenReturn(source);
        when(source.getChunk(anyInt(), anyInt(), eq(ChunkStatus.FULL), eq(true))).thenReturn(chunk);
        when(world.isInValidBounds(any())).thenReturn(true);
        doCallRealMethod().when(world).getChunk(anyInt(), anyInt(), any(), anyBoolean());
        doCallRealMethod().when(world).getBlockState(any());
        return world;
    }
}
