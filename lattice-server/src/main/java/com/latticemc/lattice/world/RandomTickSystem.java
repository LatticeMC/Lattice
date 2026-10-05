package com.latticemc.lattice.world;

import ca.spottedleaf.moonrise.common.list.ReferenceList;
import ca.spottedleaf.moonrise.common.list.ShortList;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongArrays;
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
 * 默认关闭的加权系统采样实验。静态列表保持期望刻数，但分布、相关性和成长行为与原版不同。
 * 列表大小每轮直接读取 Moonrise 的统计，不持有可能漏失效的跨 tick 计数缓存。
 */
public final class RandomTickSystem {
    public static final boolean ENABLED = Boolean.getBoolean("lattice.optimizeRandomTick");
    static final long SCALE = 1L << 20;
    private static final int SECTION_BITS = 16;
    private static final int SECTION_MASK = 0xFFFF;
    private static final long CHUNK_WEIGHT_SCALE = SCALE / 4096 * 4;
    private final LongArrayList queue = new LongArrayList();
    private long[] samples = LongArrays.EMPTY_ARRAY;
    private long[] weights = LongArrays.EMPTY_ARRAY;
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
        int sampleCount = 0;
        long totalWeight = 0L;
        final long scale = speed * CHUNK_WEIGHT_SCALE;
        for (int chunkIndex = 0; chunkIndex < size; ++chunkIndex) {
            if (this.selectionRandom.nextInt(4) != 0) continue;
            final LevelChunkSection[] sections = chunks[chunkIndex].getSections();
            this.samples = LongArrays.grow(this.samples, sampleCount + sections.length, sampleCount);
            this.weights = LongArrays.grow(this.weights, sampleCount + sections.length, sampleCount);
            for (int sectionIndex = 0; sectionIndex < sections.length; ++sectionIndex) {
                final int count = sections[sectionIndex].moonrise$getTickingBlockList().size();
                if (count == 0) continue;
                final long weight = count * scale;
                totalWeight += weight;
                this.samples[sampleCount] = ((long) chunkIndex << SECTION_BITS) | sectionIndex;
                this.weights[sampleCount++] = weight;
            }
        }
        if (totalWeight != 0L) {
            sample(this.samples, this.weights, totalWeight, this.selectionRandom.nextInt((int) SCALE), this.queue);
        }
        return this.queue;
    }

    // 对半开区间作固定相位采样，期望命中数恰为 weight / SCALE。
    static void sample(final long[] samples, final long[] weights, final long totalWeight,
                       final long phase, final LongArrayList output) {
        if (phase >= totalWeight) return;
        int index = 0;
        long accumulated = weights[0];
        for (long point = phase; point < totalWeight;) {
            while (point >= accumulated) accumulated += weights[++index];
            output.add(samples[index]);
            if (totalWeight - point <= SCALE) break; // 同时避免最后一步 long 加法溢出。
            point += SCALE;
        }
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
        state.randomTick(world, pos, random);
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
