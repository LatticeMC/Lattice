package com.latticemc.lattice.world;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import ca.spottedleaf.moonrise.patches.starlight.light.StarLightInterface;
import ca.spottedleaf.moonrise.patches.starlight.light.SWMRNibbleArray;
import io.papermc.paper.configuration.WorldConfiguration;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SnowyDirtBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.SpreadingSnowyDirtBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.material.FluidState;
import org.bukkit.craftbukkit.event.CraftEventFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class GrassLookupTestSuite {
    private static final BlockPos ORIGIN = new BlockPos(8, 3, 8);
    private static final BlockPos TARGET = new BlockPos(9, 3, 8);
    @BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

    @Test void knownChunkBrightnessMatchesLookupAcrossLightModesAndHeights() {
        for (boolean sky : new boolean[]{false, true}) for (boolean block : new boolean[]{false, true}) {
            LightFixture f = new LightFixture(sky, block);
            for (int amount : new int[]{0, 4, 11}) for (int y : new int[]{-81, -80, -64, 0, 319, 335, 336}) {
                BlockPos pos = new BlockPos(-17, y, 31);
                int expected = f.light.getRawBrightness(pos, amount);
                clearInvocations(f.light);
                assertEquals(expected, f.light.getRawBrightness(pos, amount, f.chunk));
                verify(f.light, never()).getAnyChunkNow(anyInt(), anyInt());
            }
            assertEquals(Math.max(sky ? 8 : -4, block ? 5 : 0), f.light.getRawBrightness(BlockPos.ZERO, 4, f.chunk));
            doReturn(null).when(f.light).getAnyChunkNow(anyInt(), anyInt());
            assertEquals(f.light.getRawBrightness(BlockPos.ZERO, 4), f.light.getRawBrightness(BlockPos.ZERO, 4, null));
        }
    }

    @Test void brightnessKeepsUnlitChunkAndNullNibbleRulesAndLiveUpdates() {
        LightFixture f = new LightFixture(true, true);
        when(f.chunk.isLightCorrect()).thenReturn(false);
        assertEquals(15, f.light.getRawBrightness(BlockPos.ZERO, 0, f.chunk));
        when(f.chunk.isLightCorrect()).thenReturn(true);
        when(f.chunk.getPersistedStatus()).thenReturn(ChunkStatus.EMPTY);
        assertEquals(15, f.light.getRawBrightness(BlockPos.ZERO, 0, f.chunk));
        when(f.chunk.getPersistedStatus()).thenReturn(ChunkStatus.FULL);
        f.sky[5] = new SWMRNibbleArray(null, true);
        assertEquals(f.light.getRawBrightness(BlockPos.ZERO, 0), f.light.getRawBrightness(BlockPos.ZERO, 0, f.chunk));
        f.sky[5] = nibble(0);
        assertEquals(5, f.light.getRawBrightness(BlockPos.ZERO, 0, f.chunk));
        f.block[5].set(0, 0, 0, 9); f.block[5].updateVisible();
        assertEquals(9, f.light.getRawBrightness(BlockPos.ZERO, 0, f.chunk));
        f.sky[5] = nibble(15);
        clearInvocations(f.chunk);
        assertEquals(15, f.light.getRawBrightness(BlockPos.ZERO, 0, f.chunk));
        verify(f.chunk, never()).starlight$getBlockNibbles();
    }

    @Test void grassBrightnessPreservesHorizontalWorldBounds() throws Exception {
        GrassFixture f = new GrassFixture();
        Method method = SpreadingSnowyDirtBlock.class.getDeclaredMethod("lattice$getBrightness", ServerLevel.class, BlockPos.class, LevelChunk.class);
        method.setAccessible(true);
        for (BlockPos pos : List.of(new BlockPos(-30_000_001, 0, 0), new BlockPos(30_000_000, 0, 0), new BlockPos(0, 0, 30_000_000))) {
            assertEquals(15, method.invoke(null, f.world, pos, f.chunk));
        }
        verifyNoInteractions(f.light);
        assertEquals(15, method.invoke(null, f.world, new BlockPos(-30_000_000, 0, 0), f.chunk));
        verify(f.light).getRawBrightness(new BlockPos(-30_000_000, 0, 0), 0, f.chunk);
    }

    @Test void unloadedGrassStopsWithoutRandomOrLightQueries() {
        GrassFixture f = new GrassFixture();
        when(f.source.getChunkAtIfLoadedImmediately(anyInt(), anyInt())).thenReturn(null);
        f.tick();
        verifyNoInteractions(f.random, f.light);
    }

    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void grassSpreadDisabledSkipsBothEntrypoints(boolean knownChunk) {
        GrassFixture f = new GrassFixture(knownChunk);
        f.world.paperConfig().tickRates.grassSpread = 0;
        f.tick();
        verifyNoInteractions(f.random, f.light);
        verify(f.source, never()).getChunkAtIfLoadedImmediately(anyInt(), anyInt());
    }

    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void crossChunkAttemptsStillUseLiveWorldLookup(boolean knownChunk) {
        GrassFixture f = new GrassFixture(knownChunk);
        BlockPos edge = new BlockPos(15, 3, 8), target = new BlockPos(16, 3, 8);
        LevelChunk neighbor = mock(LevelChunk.class);
        when(f.world.getChunkAt(target)).thenReturn(neighbor);
        when(neighbor.getBlockState(target)).thenReturn(Blocks.STONE.defaultBlockState());
        if (knownChunk) {
            ((LatticeTickingBlock) Blocks.GRASS_BLOCK).lattice$randomTick(Blocks.GRASS_BLOCK.defaultBlockState(),
                f.world, edge, f.random, f.chunk, null);
        } else {
            Blocks.GRASS_BLOCK.defaultBlockState().randomTick(f.world, edge, f.random);
        }
        verify(f.world, times(4)).getChunkAt(target);
        verify(neighbor, times(4)).getBlockState(target);
        verify(f.chunk, never()).getBlockState(target);
    }

    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true}) void nonDirtTargetsDoNotReadAboveAndPreserveRandomConsumption(boolean knownChunk) {
        GrassFixture f = new GrassFixture(knownChunk);
        when(f.chunk.getBlockState(TARGET)).thenReturn(Blocks.STONE.defaultBlockState());
        f.tick();
        verify(f.chunk, times(4)).getBlockState(TARGET);
        verify(f.chunk, never()).getBlockState(TARGET.above());
        verify(f.random, times(8)).nextInt(3);
        verify(f.random, times(4)).nextInt(5);
        verify(f.light).getRawBrightness(ORIGIN.above(), 0, f.chunk);
        verify(f.light, never()).getRawBrightness(any(), anyInt());
    }

    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true}) void propagationReadsAboveOncePerAttemptAndHonorsEventChanges(boolean knownChunk) {
        GrassFixture f = new GrassFixture(knownChunk);
        BlockState[] above = {Blocks.AIR.defaultBlockState()};
        // Bootstrap 不加载数据包标签；在此依赖边界提供已标记的雪状态，不改全局注册表。
        BlockState snow = mock(BlockState.class);
        when(snow.is(Blocks.SNOW)).thenReturn(true);
        when(snow.getValue(SnowLayerBlock.LAYERS)).thenReturn(1);
        when(snow.is(BlockTags.SNOW)).thenReturn(true);
        when(snow.getFluidState()).thenReturn(Fluids.EMPTY.defaultFluidState());
        when(f.chunk.getBlockState(TARGET.above())).thenAnswer(call -> above[0]);
        List<BlockState> proposed = new ArrayList<>();
        List<BlockPos> targets = new ArrayList<>();
        try (var events = mockStatic(CraftEventFactory.class)) {
            events.when(() -> CraftEventFactory.handleBlockSpreadEvent(eq(f.world), eq(ORIGIN), any(), any(), anyInt())).thenAnswer(call -> {
                targets.add(call.getArgument(2)); proposed.add(call.getArgument(3));
                above[0] = snow;
                return false; // 事件取消不能省略以后独立的尝试。
            });
            f.tick();
        }
        assertEquals(List.of(TARGET, TARGET, TARGET, TARGET), targets);
        assertEquals(List.of(false, true, true, true), proposed.stream().map(state -> state.getValue(SnowyDirtBlock.SNOWY)).toList());
        verify(f.chunk, times(4)).getBlockState(TARGET.above());
        verify(f.chunk, never()).getFluidState(TARGET.above());
        verify(f.random, times(8)).nextInt(3);
        verify(f.random, times(4)).nextInt(5);
    }

    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true}) void waterAndBlockedTargetsDoNotPropagate(boolean knownChunk) {
        for (BlockState above : List.of(Blocks.WATER.defaultBlockState(), Blocks.STONE.defaultBlockState())) {
            GrassFixture f = new GrassFixture(knownChunk); when(f.chunk.getBlockState(TARGET.above())).thenReturn(above);
            try (var events = mockStatic(CraftEventFactory.class)) { f.tick(); events.verifyNoInteractions(); }
        }
    }

    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true}) void flowingWaterTagStillPreventsPropagation(boolean knownChunk) {
        GrassFixture f = new GrassFixture(knownChunk);
        BlockState above = mock(BlockState.class); FluidState fluid = mock(FluidState.class);
        when(above.getFluidState()).thenReturn(fluid); when(fluid.getAmount()).thenReturn(1); when(fluid.is(FluidTags.WATER)).thenReturn(true);
        when(f.chunk.getBlockState(TARGET.above())).thenReturn(above);
        try (var events = mockStatic(CraftEventFactory.class)) { f.tick(); events.verifyNoInteractions(); }
        verify(f.chunk, times(4)).getBlockState(TARGET.above());
    }

    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true}) void propagationFailureEscapesImmediately(boolean knownChunk) {
        GrassFixture f = new GrassFixture(knownChunk); RuntimeException error = new RuntimeException("spread callback");
        try (var events = mockStatic(CraftEventFactory.class)) {
            events.when(() -> CraftEventFactory.handleBlockSpreadEvent(eq(f.world), eq(ORIGIN), eq(TARGET), any(), anyInt())).thenThrow(error);
            assertSame(error, assertThrows(RuntimeException.class, f::tick));
        }
        verify(f.chunk, times(1)).getBlockState(TARGET.above());
    }

    static SWMRNibbleArray nibble(int value) {
        byte[] data = new byte[2048]; Arrays.fill(data, (byte)(value | value << 4)); return new SWMRNibbleArray(data);
    }

    private static class LightFixture {
        final LevelChunk chunk = mock(LevelChunk.class);
        final SWMRNibbleArray[] sky = new SWMRNibbleArray[26], block = new SWMRNibbleArray[26];
        final StarLightInterface light;
        LightFixture(boolean hasSky, boolean hasBlock) {
            ServerLevel world = mock(ServerLevel.class); when(world.getMinSectionY()).thenReturn(-4); when(world.getMaxSectionY()).thenReturn(19);
            LightChunkGetter access = mock(LightChunkGetter.class); when(access.getLevel()).thenReturn(world);
            for (int i = 0; i < 26; i++) { sky[i] = nibble(12); block[i] = nibble(5); }
            when(chunk.isLightCorrect()).thenReturn(true); when(chunk.getPersistedStatus()).thenReturn(ChunkStatus.FULL);
            when(chunk.starlight$getSkyNibbles()).thenReturn(sky); when(chunk.starlight$getBlockNibbles()).thenReturn(block);
            when(chunk.starlight$getSkyEmptinessMap()).thenReturn(new boolean[24]);
            light = spy(new StarLightInterface(access, hasSky, hasBlock, LevelLightEngine.EMPTY));
            doReturn(chunk).when(light).getAnyChunkNow(anyInt(), anyInt());
        }
    }
    private static class GrassFixture {
        final ServerLevel world = mock(ServerLevel.class);
        final ServerChunkCache source = mock(ServerChunkCache.class);
        final LevelChunk chunk = mock(LevelChunk.class);
        final RandomSource random = mock(RandomSource.class);
        final StarLightInterface light = mock(StarLightInterface.class);
        final boolean knownChunk;
        GrassFixture() { this(false); }
        GrassFixture(boolean knownChunk) {
            this.knownChunk = knownChunk;
            WorldConfiguration config = RandomTickTestSupport.allocate(WorldConfiguration.class); config.tickRates = config.new TickRates();
            when(world.paperConfig()).thenReturn(config); when(world.getChunkSource()).thenReturn(source);
            when(source.getChunkAtIfLoadedImmediately(anyInt(), anyInt())).thenReturn(chunk);
            RandomTickTestSupport.field(chunk, ChunkAccess.class, "locX", 0); RandomTickTestSupport.field(chunk, ChunkAccess.class, "locZ", 0);
            when(chunk.getBlockState(any())).thenReturn(Blocks.AIR.defaultBlockState());
            when(chunk.getBlockState(anyInt(), anyInt(), anyInt())).thenAnswer(call ->
                chunk.getBlockState(new BlockPos(call.getArgument(0, Integer.class), call.getArgument(1, Integer.class), call.getArgument(2, Integer.class))));
            when(chunk.getBlockState(TARGET)).thenReturn(Blocks.DIRT.defaultBlockState());
            LevelLightEngine engine = mock(LevelLightEngine.class); when(world.getLightEngine()).thenReturn(engine);
            when(engine.starlight$getLightEngine()).thenReturn(light); when(light.getRawBrightness(any(), anyInt(), same(chunk))).thenReturn(15);
            when(random.nextInt(3)).thenReturn(2, 1, 2, 1, 2, 1, 2, 1); when(random.nextInt(5)).thenReturn(3);
        }
        void tick() {
            if (this.knownChunk) {
                ((LatticeTickingBlock) Blocks.GRASS_BLOCK).lattice$randomTick(Blocks.GRASS_BLOCK.defaultBlockState(), world, ORIGIN, random, chunk, null);
                verify(source, never()).getChunkAtIfLoadedImmediately(anyInt(), anyInt());
            } else {
                Blocks.GRASS_BLOCK.defaultBlockState().randomTick(world, ORIGIN, random);
            }
        }
    }
}
