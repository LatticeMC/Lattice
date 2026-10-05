package com.latticemc.lattice.world;

import ca.spottedleaf.moonrise.common.list.ReferenceList;
import ca.spottedleaf.moonrise.common.list.ShortList;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.BitRandomSource;
import net.minecraft.world.level.levelgen.RandomSupport;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.material.FluidState;

/**
 * 默认关闭的逐 section 抽样实验。每个非空 section 保留原版的独立 Bernoulli
 * 试验分布，同时用一个 long 携带最多五次 12-bit 命中试验。
 */
public final class RandomTickSystem {
    public static final boolean ENABLED = Boolean.getBoolean("lattice.optimizeRandomTick");
    private static final int SECTION_BITS = 16;
    private static final int SECTION_MASK = 0xFFFF;
    private final LongArrayList queue = new LongArrayList();
    // 选择流不能与天气、位置和方块回调共用，否则分支消耗会反馈到下一次选择。
    private final RandomSource selectionRandom;

    public RandomTickSystem() {
        this(new XoroshiroRandomSource(RandomSupport.generateUniqueSeed()));
    }

    RandomTickSystem(final RandomSource selectionRandom) {
        this.selectionRandom = selectionRandom;
    }

    public void tick(final ServerLevel world) {
        this.queue.clear();
        final ReferenceList<LevelChunk> ticking = world.moonrise$getEntityTickingChunks();
        final LevelChunk[] raw = ticking.getRawDataUnchecked();
        final int size = ticking.size();
        final int speed = world.getGameRules().get(GameRules.RANDOM_TICK_SPEED);
        final BitRandomSource random = world.lattice$getRandomTickRandom();
        final boolean weather = !world.paperConfig().environment.disableIceAndSnow;
        final ProfilerFiller profiler = Profiler.get();
        profiler.push("iceandsnow");
        // 沿用原遍历的生命周期约束：本阶段只可能追加区块，不会移除既有区块。
        for (int i = 0; i < size; ++i) {
            if (weather) {
                final ChunkPos pos = raw[i].getPos();
                for (int j = 0; j < speed; ++j) {
                    if (random.nextInt(48) == 0) {
                        world.tickPrecipitation(world.getBlockRandomPos(pos.getMinBlockX(), 0, pos.getMinBlockZ(), 15));
                    }
                }
            }
            if ((i & 7) == 0) {
                world.getServer().moonrise$executeMidTickTasks();
            }
        }
        profiler.popPush("tickBlocks");
        if (speed > 0) {
            this.selectSections(raw, size, speed);
            final int minSection = ca.spottedleaf.moonrise.common.util.WorldUtil.getMinSection(world);
            final boolean doubleTickFluids = !ca.spottedleaf.moonrise.common.PlatformHooks.get().configFixMC224294();
            final long[] selected = this.queue.elements();
            for (int i = 0, len = this.queue.size(); i < len; ++i) {
                final long packed = selected[i];
                tickBlock(world, raw[(int) (packed >>> SECTION_BITS)], (int) packed & SECTION_MASK,
                    minSection, random, doubleTickFluids);
            }
        }
        profiler.pop();
    }

    // 返回值仅用于当前 tick；测试与基准直接调用同一选择入口。
    LongArrayList selectSections(final LevelChunk[] chunks, final int size, final int speed) {
        this.queue.clear();
        if (speed <= 0 || size == 0) return this.queue;
        for (int chunkIndex = 0; chunkIndex < size; ++chunkIndex) {
            final LevelChunkSection[] sections = chunks[chunkIndex].getSections();
            for (int sectionIndex = 0; sectionIndex < sections.length; ++sectionIndex) {
                final int count = sections[sectionIndex].moonrise$getTickingBlockList().size();
                if (count == 0) continue;
                int remaining = speed;
                while (remaining > 0) {
                    final long bits = this.selectionRandom.nextLong();
                    final int trials = Math.min(5, remaining);
                    for (int trial = 0; trial < trials; ++trial) {
                        if (((bits >>> (trial * 12)) & 4095L) < count) {
                            this.queue.add(((long) chunkIndex << SECTION_BITS) | sectionIndex);
                        }
                    }
                    remaining -= trials;
                }
            }
        }
        return this.queue;
    }

    static void tickBlock(final ServerLevel world, final LevelChunk chunk, final int sectionIndex,
                          final int minSection, final BitRandomSource random, final boolean doubleTickFluids) {
        final LevelChunkSection section = chunk.getSection(sectionIndex);
        final ShortList ticking = section.moonrise$getTickingBlockList();
        final int count = ticking.size();
        if (count == 0) return;
        final int location = ticking.getRaw(boundedNextInt(random, count)) & 0xFFFF;
        final BlockState state = section.getStates().get(location);
        final ChunkPos chunkPos = chunk.getPos();
        // 某些方块保存输入位置，不能复用 MutableBlockPos。
        final BlockPos pos = new BlockPos((location & 15) | chunkPos.getMinBlockX(),
            (location >>> 8) | ((minSection + sectionIndex) << 4),
            ((location >>> 4) & 15) | chunkPos.getMinBlockZ());
        if (state.getBlock() instanceof LatticeTickingBlock optimized) {
            optimized.lattice$randomTick(state, world, pos, random, chunk, section);
        } else {
            state.randomTick(world, pos, random);
        }
        if (doubleTickFluids) {
            final FluidState fluid = state.getFluidState();
            if (fluid.isRandomlyTicking()) fluid.randomTick(world, pos, random);
        }
    }

    static int boundedNextInt(final BitRandomSource random, final int bound) {
        final int mask = bound - 1;
        int value = random.nextInt();
        if ((bound & mask) == 0) return value & mask;
        for (int candidate = value >>> 1; candidate + mask - (value = candidate % bound) < 0;
             candidate = random.nextInt() >>> 1) { }
        return value;
    }
}
