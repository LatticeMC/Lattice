package com.latticemc.lattice.world;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import ca.spottedleaf.moonrise.common.list.ReferenceList;
import io.papermc.paper.configuration.WorldConfiguration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
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
        int sections = 24;
        LevelChunk[] chunks = {RandomTickTestSupport.chunk(0, 0, sections, 4096)};
        RandomTickSystem system = new RandomTickSystem(new XoroshiroRandomSource(230));
        int ticks = 10_000;
        var callbackRandom = new ca.spottedleaf.moonrise.common.util.SimpleThreadUnsafeRandom(21);
        for (int speed : new int[]{1, 3, 5, 6, 16}) {
            long before = RandomTickTestSupport.callbacks;
            for (int i = 0; i < ticks; i++) system.tickSections(null, chunks, 1, speed, -4, callbackRandom, false);
            assertEquals((long) ticks * sections * speed, RandomTickTestSupport.callbacks - before, "speed=" + speed);
        }
    }

    @Test
    void sparseSectionsRemainIndependentAcrossChunks() {
        LevelChunk[] chunks = {
            RandomTickTestSupport.chunk(0, 0, 1, 512),
            RandomTickTestSupport.chunk(1, 0, 1, 512)
        };
        RandomTickSystem system = new RandomTickSystem(new XoroshiroRandomSource(17));
        int ticks = 100_000;
        var callbackRandom = new ca.spottedleaf.moonrise.common.util.SimpleThreadUnsafeRandom(21);
        long first = 0, second = 0, both = 0;
        long[][] histogram = new long[2][4];
        for (int i = 0; i < ticks; i++) {
            long before = RandomTickTestSupport.callbacks;
            system.tickChunkSections(null, chunks[0], 3, 0, callbackRandom, false);
            int aCount = Math.toIntExact(RandomTickTestSupport.callbacks - before);
            before = RandomTickTestSupport.callbacks;
            system.tickChunkSections(null, chunks[1], 3, 0, callbackRandom, false);
            int bCount = Math.toIntExact(RandomTickTestSupport.callbacks - before);
            assertTrue(aCount <= 3 && bCount <= 3);
            histogram[0][aCount]++;
            histogram[1][bCount]++;
            boolean a = aCount != 0, b = bCount != 0;
            if (a) first++;
            if (b) second++;
            if (a && b) both++;
        }
        double p = 512.0 / 4096.0, any = 1.0 - Math.pow(1.0 - p, 3);
        for (long count : new long[]{first, second}) assertBinomialCount(ticks, any, count);
        assertBinomialCount(ticks, any * any, both);
        for (long[] counts : histogram) {
            for (int k = 0; k <= 3; ++k) {
                double probability = (k == 0 || k == 3 ? 1 : 3) * Math.pow(p, k) * Math.pow(1 - p, 3 - k);
                assertBinomialCount(ticks, probability, counts[k]);
            }
        }
    }

    private static void assertBinomialCount(int trials, double probability, long actual) {
        assertEquals(trials * probability, actual, 8.0 * Math.sqrt(trials * probability * (1 - probability)));
    }

    @Test
    void sectionsInSameChunkDoNotShareTheOldChunkGate() {
        LevelChunk chunk = RandomTickTestSupport.chunk(0, 0, 2, 512);
        RandomTickSystem system = new RandomTickSystem(new XoroshiroRandomSource(713));
        var callback = new ca.spottedleaf.moonrise.common.util.SimpleThreadUnsafeRandom(81);
        long[] counts = new long[3];
        int ticks = 100_000;
        for (int tick = 0; tick < ticks; tick++) {
            long before = RandomTickTestSupport.callbacks;
            system.tickChunkSections(null, chunk, 1, 0, callback, false);
            int selected = Math.toIntExact(RandomTickTestSupport.callbacks - before);
            assertTrue(selected <= 2);
            counts[selected]++;
        }
        double p = 512.0 / 4096.0;
        assertBinomialCount(ticks, (1 - p) * (1 - p), counts[0]);
        assertBinomialCount(ticks, 2 * p * (1 - p), counts[1]);
        assertBinomialCount(ticks, p * p, counts[2]);
    }

    @Test
    void selectionSegmentBoundaryAndListUpperBoundAreExact() {
        LevelChunk chunk = RandomTickTestSupport.chunk(0, 0, 1, 2);
        RandomSource selection = mock(RandomSource.class);
        // Five trials: hit indices 1,0 then miss 2,4095,2; refill for the sixth hit.
        when(selection.nextLong()).thenReturn(1L | (2L << 24) | (4095L << 36) | (2L << 48), 1L);
        long before = RandomTickTestSupport.callbacks;
        BitRandomSource callback = mock(BitRandomSource.class);
        new RandomTickSystem(selection).tickChunkSections(null, chunk, 6, 0, callback, false);
        assertEquals(3, RandomTickTestSupport.callbacks - before);
        verify(selection, times(2)).nextLong();
        verifyNoMoreInteractions(selection);
        verify(callback, times(3)).nextInt();
        verifyNoMoreInteractions(callback);
    }

    @Test
    void emptySectionsAreSkippedAndSpeedZeroDoesNotReadSelectionRandom() {
        LevelChunk chunk = RandomTickTestSupport.chunk(0, 0, 24, 0);
        RandomSource random = mock(RandomSource.class);
        RandomTickSystem system = new RandomTickSystem(random);
        system.tickSections(null, new LevelChunk[]{chunk}, 1, 3, 0, null, false);
        system.tickSections(null, new LevelChunk[]{RandomTickTestSupport.chunk(0, 0, 1, 1)}, 1, 0, 0, null, false);
        verifyNoInteractions(random);
    }

    @Test
    void sectionMaskTracksTickingListEmptyTransitions() {
        LevelChunk chunk = RandomTickTestSupport.chunk(0, 0, 4, 0);
        assertEquals(0L, chunk.lattice$getRandomTickingSections());

        LevelChunkSection section = chunk.getSection(2);
        section.setBlockState(1, 2, 3, RandomTickTestSupport.PROBE);
        assertEquals(1L << 2, chunk.lattice$getRandomTickingSections());

        section.setBlockState(1, 2, 3, Blocks.AIR.defaultBlockState());
        assertEquals(0L, chunk.lattice$getRandomTickingSections());
    }

    @Test
    void sectionMaskTracksBulkPaletteRecount() {
        LevelChunk chunk = RandomTickTestSupport.chunk(0, 0, 4, 0);
        LevelChunkSection section = chunk.getSection(2);
        section.getStates().set(1, 2, 3, RandomTickTestSupport.PROBE);
        section.recalcBlockCounts();
        assertEquals(1L << 2, chunk.lattice$getRandomTickingSections());
        section.getStates().set(1, 2, 3, Blocks.AIR.defaultBlockState());
        section.recalcBlockCounts();
        assertEquals(0L, chunk.lattice$getRandomTickingSections());
    }

    @Test
    void lastMaskBitAndTallWorldFallbackAreTicked() {
        for (int sectionCount : new int[]{64, 65}) {
            LevelChunk chunk = RandomTickTestSupport.chunk(-2, -3, sectionCount, 0);
            chunk.getSection(sectionCount - 1).setBlockState(1, 2, 3, RandomTickTestSupport.PROBE);
            RandomSource selection = mock(RandomSource.class);
            when(selection.nextLong()).thenReturn(0L);
            long before = RandomTickTestSupport.callbacks;
            new RandomTickSystem(selection).tickChunkSections(null, chunk, 6, -4,
                new ca.spottedleaf.moonrise.common.util.SimpleThreadUnsafeRandom(21), false);
            assertEquals(6, RandomTickTestSupport.callbacks - before);
            verify(selection, times(2)).nextLong();
        }
    }

    @Test
    void optedInBlockReceivesSelectedChunkAndSectionWithoutOriginalDispatch() {
        LevelChunk chunk = RandomTickTestSupport.chunk(0, 0, 1, 1);
        BlockState state = mock(BlockState.class);
        var block = mock(net.minecraft.world.level.block.Block.class, withSettings().extraInterfaces(LatticeTickingBlock.class));
        when(state.getBlock()).thenReturn(block);
        chunk.getSection(0).getStates().set(0, 0, 0, state);
        RandomSource selection = mock(RandomSource.class);
        var callback = new ca.spottedleaf.moonrise.common.util.SimpleThreadUnsafeRandom(21);
        new RandomTickSystem(selection).tickChunkSections(null, chunk, 1, -4, callback, false);
        verify((LatticeTickingBlock) block).lattice$randomTick(same(state), isNull(), eq(new BlockPos(0, -64, 0)),
            same(callback), same(chunk), same(chunk.getSection(0)));
        verify(state, never()).randomTick(any(), any(), any());
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
    void weatherBlockTicksAndMidTickTasksRemainInterleavedPerChunk() {
        ServerLevel world = mock(ServerLevel.class);
        MinecraftServer server = mock(MinecraftServer.class);
        BitRandomSource callbackRandom = mock(BitRandomSource.class);
        when(callbackRandom.nextInt(48)).thenReturn(0);
        GameRules rules = mock(GameRules.class);
        when(rules.get(GameRules.RANDOM_TICK_SPEED)).thenReturn(1);
        when(world.getGameRules()).thenReturn(rules);
        WorldConfiguration config = mock(WorldConfiguration.class);
        config.environment = config.new Environment();
        when(world.paperConfig()).thenReturn(config);
        when(world.getServer()).thenReturn(server);
        when(world.lattice$getRandomTickRandom()).thenReturn(callbackRandom);
        when(world.getBlockRandomPos(anyInt(), anyInt(), anyInt(), anyInt())).thenReturn(BlockPos.ZERO);

        LevelChunk[] chunks = new LevelChunk[2];
        BlockState[] states = new BlockState[2];
        ReferenceList<LevelChunk> ticking = new ReferenceList<>(chunks);
        for (int i = 0; i < chunks.length; ++i) {
            LevelChunk chunk = mock(LevelChunk.class);
            LevelChunkSection section = mock(LevelChunkSection.class);
            var list = new ca.spottedleaf.moonrise.common.list.ShortList();
            list.add((short) 1);
            when(section.moonrise$getTickingBlockList()).thenReturn(list);
            @SuppressWarnings("unchecked") PalettedContainer<BlockState> blockStates = mock(PalettedContainer.class);
            states[i] = mock(BlockState.class);
            FluidState fluid = mock(FluidState.class);
            when(states[i].getFluidState()).thenReturn(fluid);
            when(blockStates.get(1)).thenReturn(states[i]);
            when(section.getStates()).thenReturn(blockStates);
            when(chunk.getSections()).thenReturn(new LevelChunkSection[]{section});
            when(chunk.lattice$getRandomTickingSections()).thenReturn(1L);
            when(chunk.getSection(0)).thenReturn(section);
            when(chunk.getPos()).thenReturn(new ChunkPos(i, 0));
            ticking.add(chunk);
            chunks[i] = chunk;
        }
        when(world.moonrise$getEntityTickingChunks()).thenReturn(ticking);
        RandomSource selection = mock(RandomSource.class);
        when(selection.nextLong()).thenReturn(0L);

        new RandomTickSystem(selection).tick(world);

        var order = inOrder(world, states[0], server, states[1]);
        order.verify(world).tickPrecipitation(BlockPos.ZERO);
        order.verify(states[0]).randomTick(eq(world), any(), same(callbackRandom));
        order.verify(server).moonrise$executeMidTickTasks();
        order.verify(world).tickPrecipitation(BlockPos.ZERO);
        order.verify(states[1]).randomTick(eq(world), any(), same(callbackRandom));
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
        when(chunk.getSections()).thenReturn(new LevelChunkSection[]{null, section});
        when(chunk.lattice$getRandomTickingSections()).thenReturn(2L);
        when(chunk.getPos()).thenReturn(new ChunkPos(-2, -3));
        @SuppressWarnings("unchecked") PalettedContainer<BlockState> states = mock(PalettedContainer.class);
        when(section.getStates()).thenReturn(states);
        BlockState state = mock(BlockState.class);
        when(states.get(0xFED)).thenReturn(state);
        FluidState fluid = mock(FluidState.class);
        when(state.getFluidState()).thenReturn(fluid);
        when(fluid.isRandomlyTicking()).thenReturn(true);
        BitRandomSource random = mock(BitRandomSource.class);
        List<BlockPos> positions = new ArrayList<>();
        doAnswer(call -> { positions.add(call.getArgument(1)); return null; }).when(state).randomTick(eq(world), any(), eq(random));
        RandomSource selection = mock(RandomSource.class);
        new RandomTickSystem(selection).tickChunkSections(world, chunk, 2, -4, random, true);
        assertEquals(2, positions.size());
        assertEquals(new BlockPos(-19, -33, -34), positions.get(0));
        assertEquals(positions.get(0), positions.get(1));
        assertNotSame(positions.get(0), positions.get(1), "Each callback may retain its position");
        for (BlockPos position : positions) assertEquals(BlockPos.class, position.getClass());
        var order = inOrder(state, fluid);
        for (BlockPos position : positions) {
            order.verify(state).randomTick(eq(world), same(position), same(random));
            order.verify(fluid).randomTick(eq(world), same(position), same(random));
        }

        clearInvocations(state, fluid);
        new RandomTickSystem(selection).tickChunkSections(world, chunk, 1, -4, random, false);
        verify(state).randomTick(eq(world), any(), same(random));
        verifyNoInteractions(fluid);
    }

    @Test
    void sectionTrialsObserveTickingListChangesFromEarlierCallbacks() {
        ServerLevel world = mock(ServerLevel.class);
        LevelChunk chunk = mock(LevelChunk.class);
        LevelChunkSection section = mock(LevelChunkSection.class);
        var list = new ca.spottedleaf.moonrise.common.list.ShortList();
        list.add((short) 1);
        when(section.moonrise$getTickingBlockList()).thenReturn(list);
        when(chunk.getSections()).thenReturn(new LevelChunkSection[]{section});
        when(chunk.lattice$getRandomTickingSections()).thenReturn(1L);
        when(chunk.getSection(0)).thenReturn(section);
        when(chunk.getPos()).thenReturn(ChunkPos.ZERO);
        @SuppressWarnings("unchecked") PalettedContainer<BlockState> states = mock(PalettedContainer.class);
        when(section.getStates()).thenReturn(states);
        BlockState first = mock(BlockState.class);
        BlockState added = mock(BlockState.class);
        when(states.get(1)).thenReturn(first);
        when(states.get(2)).thenReturn(added);
        doAnswer(call -> { list.add((short) 2); return null; }).when(first).randomTick(eq(world), any(), any());
        RandomSource selection = mock(RandomSource.class);
        when(selection.nextLong()).thenReturn(1L << 12);
        BitRandomSource callbackRandom = mock(BitRandomSource.class);

        new RandomTickSystem(selection).tickSections(world, new LevelChunk[]{chunk}, 1, 2, 0, callbackRandom, false);

        verify(first).randomTick(eq(world), any(), same(callbackRandom));
        verify(added).randomTick(eq(world), any(), same(callbackRandom));
    }

    @Test
    void chunkMaskObservesFutureSectionsActivatedByEarlierCallbacks() {
        ServerLevel world = mock(ServerLevel.class);
        LevelChunk chunk = mock(LevelChunk.class);
        LevelChunkSection firstSection = mock(LevelChunkSection.class);
        LevelChunkSection addedSection = mock(LevelChunkSection.class);
        LevelChunkSection[] sections = {firstSection, addedSection};
        var firstList = new ca.spottedleaf.moonrise.common.list.ShortList();
        var addedList = new ca.spottedleaf.moonrise.common.list.ShortList();
        firstList.add((short) 1);
        when(firstSection.moonrise$getTickingBlockList()).thenReturn(firstList);
        when(addedSection.moonrise$getTickingBlockList()).thenReturn(addedList);
        when(chunk.getSections()).thenReturn(sections);
        when(chunk.getSection(anyInt())).thenAnswer(call -> sections[call.getArgument(0)]);
        when(chunk.getPos()).thenReturn(ChunkPos.ZERO);
        AtomicLong mask = new AtomicLong(1L);
        when(chunk.lattice$getRandomTickingSections()).thenAnswer(call -> mask.get());
        @SuppressWarnings("unchecked") PalettedContainer<BlockState> firstStates = mock(PalettedContainer.class);
        @SuppressWarnings("unchecked") PalettedContainer<BlockState> addedStates = mock(PalettedContainer.class);
        when(firstSection.getStates()).thenReturn(firstStates);
        when(addedSection.getStates()).thenReturn(addedStates);
        BlockState first = mock(BlockState.class);
        BlockState added = mock(BlockState.class);
        when(firstStates.get(1)).thenReturn(first);
        when(addedStates.get(2)).thenReturn(added);
        doAnswer(call -> { addedList.add((short) 2); mask.set(3L); return null; })
            .when(first).randomTick(eq(world), any(), any());
        RandomSource selection = mock(RandomSource.class);
        when(selection.nextLong()).thenReturn(0L);
        BitRandomSource callbackRandom = mock(BitRandomSource.class);

        new RandomTickSystem(selection).tickSections(world, new LevelChunk[]{chunk}, 1, 1, 0, callbackRandom, false);

        verify(first).randomTick(eq(world), any(), same(callbackRandom));
        verify(added).randomTick(eq(world), any(), same(callbackRandom));
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
