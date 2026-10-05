package com.latticemc.lattice.world;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import ca.spottedleaf.moonrise.common.list.ReferenceList;
import ca.spottedleaf.moonrise.common.util.SimpleThreadUnsafeRandom;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import io.papermc.paper.configuration.WorldConfiguration;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.BitRandomSource;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.material.FluidState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class RandomTickTestSuite {
    @BeforeAll static void bootstrap() { assertNotNull(RandomTickTestSupport.PROBE); }

    @Test void exhaustivePhasesMatchIndependentIntervalCounts() {
        long scale=RandomTickSystem.SCALE;
        for(long[] weights:new long[][] {{1024},{scale},{scale+1024,1024,2*scale},{3072,5120,1024,4096}}) {
            long total=0;for(long w:weights) total+=w;
            long[] counts=new long[weights.length],ids=new long[weights.length];for(int i=0;i<ids.length;i++)ids[i]=i;
            for(long phase=0;phase<scale;phase+=1024) {
                var out=new LongArrayList();RandomTickSystem.sample(ids,weights,total,phase,out);
                long[] actual=new long[ids.length];for(long id:out)actual[(int)id]++;
                long start=0;
                for(int i=0;i<ids.length;i++) {
                    long end=start+weights[i];
                    long expected=Math.max(0,Math.floorDiv(end-1-phase,scale)-Math.max(-1,Math.floorDiv(start-1-phase,scale)));
                    assertEquals(expected,actual[i]);counts[i]+=actual[i];start=end;
                }
                assertTrue(out.size()==total/scale || out.size()==(total+scale-1)/scale);
            }
            for(int i=0;i<weights.length;i++)assertEquals(weights[i]/1024,counts[i]);
        }
    }

    @Test void chunkFilterKeepsExpectationButChangesFullSectionDistribution() {
        LevelChunk chunk=RandomTickTestSupport.chunk(0,0,1,4096);
        int total=0;
        for(int filter=0;filter<4;filter++) {
            RandomSource rng=mock(RandomSource.class);when(rng.nextInt(4)).thenReturn(filter);
            int count=new RandomTickSystem(rng).selectSections(new LevelChunk[]{chunk},1,3).size();
            assertEquals(filter==0?12:0,count);total+=count;
        }
        assertEquals(3,total/4);
    }

    @Test void directSectionMutationsAndRebuildsAreSeenNextSelection() {
        LevelChunk chunk=RandomTickTestSupport.chunk(0,0,1,4096);
        RandomTickSystem system=new RandomTickSystem(mock(RandomSource.class));
        assertEquals(12,system.selectSections(new LevelChunk[]{chunk},1,3).size());
        LevelChunkSection section=chunk.getSection(0);
        for(int i=0;i<4096;i++) section.setBlockState(i&15,i>>>8,(i>>>4)&15,net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
        assertEquals(0,system.selectSections(new LevelChunk[]{chunk},1,3).size());
        section.getStates().set(0,0,0,RandomTickTestSupport.PROBE);section.recalcBlockCounts();
        assertEquals(1,system.selectSections(new LevelChunk[]{chunk},1,3).size()); // phase=0 命中稀疏区间。
    }

    @Test void zeroSpeedAndDisabledWeatherStillRunMidTickTasks() {
        Fixture f=new Fixture(17,0,true);new RandomTickSystem().tick(f.world);
        verify(f.server,times(3)).moonrise$executeMidTickTasks();
        verify(f.world,never()).tickPrecipitation(any());verifyNoInteractions(f.rng);
    }

    @Test void weatherTrialCountIsUnchangedAndExceptionsPropagate() {
        Fixture f=new Fixture(2,3,false);when(f.rng.nextInt(48)).thenReturn(0);
        new RandomTickSystem().tick(f.world);
        verify(f.rng,times(6)).nextInt(48);verify(f.world,times(6)).tickPrecipitation(any());
        RuntimeException error=new RuntimeException("weather");doThrow(error).when(f.world).tickPrecipitation(any());
        assertSame(error,assertThrows(RuntimeException.class,()->new RandomTickSystem().tick(f.world)));
    }

    @Test void blockCallbacksKeepCoordinatesImmutablePositionsAndFluidOrder() {
        ServerLevel world=mock(ServerLevel.class);LevelChunk chunk=mock(LevelChunk.class);LevelChunkSection section=mock(LevelChunkSection.class);
        var list=new ca.spottedleaf.moonrise.common.list.ShortList();list.add((short)0xFED);
        when(section.moonrise$getTickingBlockList()).thenReturn(list);when(chunk.getSection(1)).thenReturn(section);when(chunk.getPos()).thenReturn(new ChunkPos(-2,-3));
        @SuppressWarnings("unchecked") PalettedContainer<BlockState> states=mock(PalettedContainer.class);when(section.getStates()).thenReturn(states);
        BlockState state=mock(BlockState.class);when(states.get(0xFED)).thenReturn(state);FluidState fluid=mock(FluidState.class);
        when(state.getFluidState()).thenReturn(fluid);when(fluid.isRandomlyTicking()).thenReturn(true);
        BitRandomSource rng=mock(BitRandomSource.class);List<BlockPos> positions=new ArrayList<>();
        doAnswer(call->{positions.add(call.getArgument(1));return null;}).when(state).randomTick(eq(world),any(),eq(rng));
        RandomTickSystem.tickBlock(world,chunk,1,-4,rng,false);verifyNoInteractions(fluid);
        RandomTickSystem.tickBlock(world,chunk,1,-4,rng,true);
        assertEquals(new BlockPos(-19,-33,-34),positions.get(0));assertNotSame(positions.get(0),positions.get(1));
        var order=inOrder(state,fluid);order.verify(state,times(2)).randomTick(eq(world),any(),eq(rng));order.verify(state).getFluidState();order.verify(fluid).isRandomlyTicking();order.verify(fluid).randomTick(eq(world),same(positions.get(1)),eq(rng));
        list.clear();RandomTickSystem.tickBlock(world,chunk,1,-4,rng,true);assertEquals(2,positions.size());
    }

    @Test void productionTickUsesCurrentList() {
        Fixture f=new Fixture(1,3,true);LevelChunk real=RandomTickTestSupport.chunk(0,0,1,4096);f.chunks[0]=real;
        RandomTickSystem system=new RandomTickSystem(mock(RandomSource.class));
        long before=RandomTickTestSupport.callbacks;system.tick(f.world);assertEquals(12,RandomTickTestSupport.callbacks-before);
        real.getSection(0).moonrise$getTickingBlockList().clear();system.tick(f.world);assertEquals(12,RandomTickTestSupport.callbacks-before);
    }

    @Test void failedBlockCallbackPropagatesAndNextTickClearsQueue() {
        Fixture f=new Fixture(1,3,true);LevelChunkSection section=mock(LevelChunkSection.class);
        var list=new ca.spottedleaf.moonrise.common.list.ShortList();for(short i=0;i<4096;i++)list.add(i);
        when(section.moonrise$getTickingBlockList()).thenReturn(list);
        when(f.chunks[0].getSections()).thenReturn(new LevelChunkSection[]{section});when(f.chunks[0].getSection(0)).thenReturn(section);
        @SuppressWarnings("unchecked") PalettedContainer<BlockState> states=mock(PalettedContainer.class);when(section.getStates()).thenReturn(states);
        BlockState state=mock(BlockState.class);when(states.get(anyInt())).thenReturn(state);
        RuntimeException failure=new RuntimeException("block callback");doThrow(failure).when(state).randomTick(eq(f.world),any(),eq(f.rng));
        RandomTickSystem system=new RandomTickSystem(mock(RandomSource.class));assertSame(failure,assertThrows(RuntimeException.class,()->system.tick(f.world)));
        clearInvocations(state);list.clear();system.tick(f.world);verifyNoInteractions(state);
    }

    @Test void callbackCanEmptyTheSectionDuringTheSameTick() {
        Fixture f=new Fixture(1,3,true);
        LevelChunkSection section=mock(LevelChunkSection.class);
        var list=new ca.spottedleaf.moonrise.common.list.ShortList();
        for(short i=0;i<4096;i++)list.add(i);
        when(section.moonrise$getTickingBlockList()).thenReturn(list);
        when(f.chunks[0].getSections()).thenReturn(new LevelChunkSection[]{section});
        when(f.chunks[0].getSection(0)).thenReturn(section);
        @SuppressWarnings("unchecked") PalettedContainer<BlockState> states=mock(PalettedContainer.class);
        when(section.getStates()).thenReturn(states);
        BlockState state=mock(BlockState.class);when(states.get(anyInt())).thenReturn(state);
        doAnswer(call->{list.clear();return null;}).when(state).randomTick(eq(f.world),any(),eq(f.rng));
        RandomTickSystem system=new RandomTickSystem(mock(RandomSource.class));
        system.tick(f.world);
        verify(state,times(1)).randomTick(eq(f.world),any(),eq(f.rng));
        verify(states,times(1)).get(anyInt());
        system.tick(f.world);
        verify(state,times(1)).randomTick(eq(f.world),any(),eq(f.rng));
    }

    @Test void fullChunkFrequencyDoesNotRepeatTheSharedLcgBias() {
        LevelChunk[] chunks={RandomTickTestSupport.chunk(0,0,24,4096)};
        int ticks=250_000;
        for(long seed:new long[]{230,1,0x5EED,123456789}) {
            RandomTickSystem system=new RandomTickSystem(new XoroshiroRandomSource(seed));
            long calls=0;
            for(int tick=0;tick<ticks;tick++) calls+=system.selectSections(chunks,1,3).size();
            double selected=(double)calls/288;
            double expected=ticks/4.0, sixSigma=6*Math.sqrt(ticks*0.25*0.75);
            assertEquals(expected,selected,sixSigma,"seed="+seed);
            System.out.printf(java.util.Locale.ROOT,"RANDOM_FREQUENCY seed=%d ticks=%d selected=%.0f callbacks=%.9f%n",seed,ticks,selected,(double)calls/ticks);
        }
    }

    @Test void callbackRandomConsumptionCannotChangeSelectionSequence() {
        LevelChunk[] chunks={RandomTickTestSupport.chunk(0,0,24,4096),RandomTickTestSupport.chunk(1,0,24,64)};
        RandomTickSystem[] systems=new RandomTickSystem[3];
        SimpleThreadUnsafeRandom[] callbackRandom=new SimpleThreadUnsafeRandom[3];
        for(int mode=0;mode<3;mode++) {
            systems[mode]=new RandomTickSystem(new XoroshiroRandomSource(230));
            callbackRandom[mode]=new SimpleThreadUnsafeRandom(230);
        }
        for(int tick=0;tick<2000;tick++) {
            long[] expected=null;
            for(int mode=0;mode<3;mode++) {
                long[] queue=systems[mode].selectSections(chunks,chunks.length,3).toLongArray();
                if(expected==null)expected=queue;else assertArrayEquals(expected,queue);
                for(long packed:queue) {
                    RandomTickSystem.tickBlock(null,chunks[(int)(packed>>>16)],(int)packed&65535,-4,callbackRandom[mode],false);
                    for(int draw=0;draw<mode*7;draw++)callbackRandom[mode].nextInt();
                }
            }
        }
    }

    private static final class Fixture {
        final ServerLevel world=mock(ServerLevel.class);
        final MinecraftServer server=mock(MinecraftServer.class);
        final BitRandomSource rng=mock(BitRandomSource.class);
        final LevelChunk[] chunks;
        Fixture(int count,int speed,boolean disableWeather) {
            chunks=new LevelChunk[count];ReferenceList<LevelChunk> ticking=new ReferenceList<>(chunks);
            // ReferenceList 的构造数组为空槽；用 add 建立真实size与索引。
            for(int i=0;i<count;i++) { LevelChunk chunk=mock(LevelChunk.class);when(chunk.getPos()).thenReturn(new ChunkPos(i,0));when(chunk.getSections()).thenReturn(new LevelChunkSection[0]);ticking.add(chunk); }
            // backing array 为传入的数组且容量足够。
            when(world.moonrise$getEntityTickingChunks()).thenReturn(ticking);
            GameRules rules=mock(GameRules.class);when(rules.get(GameRules.RANDOM_TICK_SPEED)).thenReturn(speed);when(world.getGameRules()).thenReturn(rules);
            WorldConfiguration config=mock(WorldConfiguration.class);config.environment=config.new Environment();config.environment.disableIceAndSnow=disableWeather;
            when(world.paperConfig()).thenReturn(config);when(world.getServer()).thenReturn(server);when(world.lattice$getRandomTickRandom()).thenReturn(rng);when(world.getMinSectionY()).thenReturn(-4);
            when(world.getBlockRandomPos(anyInt(),anyInt(),anyInt(),anyInt())).thenReturn(BlockPos.ZERO);
        }
    }
}
