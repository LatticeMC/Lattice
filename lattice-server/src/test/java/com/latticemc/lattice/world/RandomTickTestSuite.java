package com.latticemc.lattice.world;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import ca.spottedleaf.moonrise.common.list.ReferenceList;
import io.papermc.paper.configuration.WorldConfiguration;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.BitRandomSource;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.material.FluidState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class RandomTickTestSuite {
    @BeforeAll static void bootstrap() { assertNotNull(RandomTickTestSupport.PROBE); }

    @Test
    void sectionSamplingMatchesOriginalExpectationWithoutChunkGate() {
        LevelChunk[] chunks = {RandomTickTestSupport.chunk(0, 0, 24, 4096)};
        RandomTickSystem system = new RandomTickSystem(new XoroshiroRandomSource(230));
        int ticks = 100_000;
        long calls = 0;
        for (int i = 0; i < ticks; i++) calls += system.selectSections(chunks, 1, 3).size();
        double expected = ticks * 3.0;
        double sigma = Math.sqrt(ticks * 3.0 * (1.0 - 1.0 / 4096.0));
        assertEquals(expected, calls, 8.0 * sigma);
    }

    @Test
    void sparseSectionsRemainIndependentAcrossChunks() {
        LevelChunk[] chunks = {
            RandomTickTestSupport.chunk(0, 0, 1, 1),
            RandomTickTestSupport.chunk(1, 0, 1, 1)
        };
        RandomTickSystem system = new RandomTickSystem(new XoroshiroRandomSource(17));
        int ticks = 120_000;
        long first = 0, second = 0, both = 0;
        for (int i = 0; i < ticks; i++) {
            var selected = system.selectSections(chunks, 2, 3);
            boolean a = false, b = false;
            for (int j = 0; j < selected.size(); j++) {
                a |= selected.getLong(j) >>> 16 == 0;
                b |= selected.getLong(j) >>> 16 == 1;
            }
            if (a) first++;
            if (b) second++;
            if (a && b) both++;
        }
        assertTrue(first > 0 && second > 0);
        assertEquals((double) first * second / ticks, both, 8.0 * Math.sqrt(ticks));
    }

    @Test
    void emptySectionsAreSkippedAndSpeedZeroDoesNotReadSelectionRandom() {
        LevelChunk chunk = RandomTickTestSupport.chunk(0, 0, 24, 0);
        RandomSource random = mock(RandomSource.class);
        RandomTickSystem system = new RandomTickSystem(random);
        assertTrue(system.selectSections(new LevelChunk[]{chunk}, 1, 3).isEmpty());
        assertTrue(system.selectSections(new LevelChunk[]{chunk}, 1, 0).isEmpty());
        verifyNoInteractions(random);
    }

    @Test
    void weatherTrialCountAndMidTickCadenceArePreserved() {
        Fixture fixture = new Fixture(17, 3, false);
        when(fixture.rng.nextInt(48)).thenReturn(0);
        new RandomTickSystem().tick(fixture.world);
        verify(fixture.rng, times(51)).nextInt(48);
        verify(fixture.world, times(51)).tickPrecipitation(any());
        verify(fixture.server, times(3)).moonrise$executeMidTickTasks();
    }

    @Test
    void zeroSpeedStillRunsMidTickTasksButNoRandomCalls() {
        Fixture fixture = new Fixture(17, 0, true);
        new RandomTickSystem().tick(fixture.world);
        verify(fixture.server, times(3)).moonrise$executeMidTickTasks();
        verifyNoInteractions(fixture.rng);
        verify(fixture.world, never()).tickPrecipitation(any());
    }

    @Test
    void blockCallbackKeepsImmutablePositionAndFluidOrder() {
        ServerLevel world = mock(ServerLevel.class);
        LevelChunk chunk = mock(LevelChunk.class);
        LevelChunkSection section = mock(LevelChunkSection.class);
        var list = new ca.spottedleaf.moonrise.common.list.ShortList();
        list.add((short) 0xFED);
        when(section.moonrise$getTickingBlockList()).thenReturn(list);
        when(chunk.getSection(1)).thenReturn(section);
        when(chunk.getPos()).thenReturn(new ChunkPos(-2, -3));
        @SuppressWarnings("unchecked") PalettedContainer<BlockState> states = mock(PalettedContainer.class);
        when(section.getStates()).thenReturn(states);
        BlockState state = mock(BlockState.class);
        when(states.get(0xFED)).thenReturn(state);
        FluidState fluid = mock(FluidState.class);
        when(state.getFluidState()).thenReturn(fluid);
        when(fluid.isRandomlyTicking()).thenReturn(true);
        BitRandomSource random = mock(BitRandomSource.class);
        when(random.nextInt(anyInt())).thenReturn(0);
        List<BlockPos> positions = new ArrayList<>();
        doAnswer(call -> { positions.add(call.getArgument(1)); return null; }).when(state).randomTick(eq(world), any(), eq(random));
        RandomTickSystem.tickBlock(world, chunk, 1, -4, random, true);
        assertEquals(new BlockPos(-19, -33, -34), positions.get(0));
        verify(state).randomTick(eq(world), same(positions.get(0)), eq(random));
        verify(fluid).randomTick(eq(world), same(positions.get(0)), eq(random));
    }

    private static final class Fixture {
        final ServerLevel world = mock(ServerLevel.class);
        final MinecraftServer server = mock(MinecraftServer.class);
        final BitRandomSource rng = mock(BitRandomSource.class);
        final LevelChunk[] chunks;

        Fixture(int count, int speed, boolean disableWeather) {
            this.chunks = new LevelChunk[count];
            ReferenceList<LevelChunk> ticking = new ReferenceList<>(this.chunks);
            for (int i = 0; i < count; i++) {
                LevelChunk chunk = mock(LevelChunk.class);
                when(chunk.getPos()).thenReturn(new ChunkPos(i, 0));
                when(chunk.getSections()).thenReturn(new LevelChunkSection[0]);
                ticking.add(chunk);
                this.chunks[i] = chunk;
            }
            when(world.moonrise$getEntityTickingChunks()).thenReturn(ticking);
            GameRules rules = mock(GameRules.class);
            when(rules.get(GameRules.RANDOM_TICK_SPEED)).thenReturn(speed);
            when(world.getGameRules()).thenReturn(rules);
            WorldConfiguration config = mock(WorldConfiguration.class);
            config.environment = config.new Environment();
            config.environment.disableIceAndSnow = disableWeather;
            when(world.paperConfig()).thenReturn(config);
            when(world.getServer()).thenReturn(server);
            when(world.lattice$getRandomTickRandom()).thenReturn(rng);
            when(world.getBlockRandomPos(anyInt(), anyInt(), anyInt(), anyInt())).thenReturn(BlockPos.ZERO);
        }
    }
}
