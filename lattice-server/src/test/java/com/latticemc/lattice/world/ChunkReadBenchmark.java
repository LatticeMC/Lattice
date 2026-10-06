package com.latticemc.lattice.world;

import ca.spottedleaf.concurrentutil.map.ConcurrentLong2ReferenceChainedHashTable;
import ca.spottedleaf.moonrise.common.util.SimpleThreadUnsafeRandom;
import ca.spottedleaf.moonrise.patches.chunk_system.scheduling.ChunkHolderManager;
import ca.spottedleaf.moonrise.patches.chunk_system.scheduling.ChunkTaskScheduler;
import ca.spottedleaf.moonrise.patches.chunk_system.scheduling.NewChunkHolder;
import ca.spottedleaf.moonrise.patches.starlight.light.SWMRNibbleArray;
import com.sun.management.ThreadMXBean;
import io.papermc.paper.configuration.WorldConfiguration;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.Locale;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LightChunk;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.lighting.LevelLightEngine;

/** 同一入口加载修改前/后类；计时内无 Mockito、世界 IO 或插件事件。 */
public final class ChunkReadBenchmark {
    private static final ThreadMXBean ALLOC = (ThreadMXBean) ManagementFactory.getThreadMXBean();
    private static volatile long sink;
    private static final int ITERATIONS = 100_000, WARMUP = 8, SAMPLES = 15;
    private static final boolean KNOWN_CHUNK = Boolean.getBoolean("lattice.chunkReadKnownChunk");

    public static void main(String[] args) {
        Locale.setDefault(Locale.ROOT); ALLOC.setThreadAllocatedMemoryEnabled(true);
        System.out.printf("CHUNK_READ_BENCH java=%s mode=%s level=%s grass=%s light=%s warmup=%d samples=%d%n",
            System.getProperty("java.version"), System.getProperty("lattice.chunkReadMode", "fixed"),
            Level.class.getProtectionDomain().getCodeSource().getLocation(),
            net.minecraft.world.level.block.SpreadingSnowyDirtBlock.class.getProtectionDomain().getCodeSource().getLocation(),
            ca.spottedleaf.moonrise.patches.starlight.light.StarLightInterface.class.getProtectionDomain().getCodeSource().getLocation(), WARMUP, SAMPLES);
        String[] patterns = KNOWN_CHUNK ? new String[]{"grass", "water", "dark"} : new String[]{"level", "grass", "water", "dark", "unloaded"};
        System.out.println("CHUNK_READ_CONTEXT knownChunk=" + KNOWN_CHUNK);
        boolean reverse = Boolean.getBoolean("lattice.chunkReadReverse");
        for (int p = 0; p < patterns.length; p++) {
            String pattern = patterns[reverse ? patterns.length - 1 - p : p];
            for (int count : new int[]{1, 64, 1024}) {
                Fixture f = new Fixture(pattern, count);
                long expected = oracle(pattern, f);
                for (int i = 0; i < WARMUP; i++) check(run(pattern, f), expected);
                double[] ns = new double[SAMPLES], bytes = new double[SAMPLES];
                for (int i = 0; i < SAMPLES; i++) {
                    Measurement m = run(pattern, f); check(m, expected);
                    ns[i] = (double)m.nanos / ITERATIONS; bytes[i] = (double)m.bytes / ITERATIONS;
                    System.out.printf("CHUNK_READ_SAMPLE pattern=%s chunks=%d sample=%d ns=%.6f bytes=%.6f checksum=%d%n", pattern, count, i, ns[i], bytes[i], m.checksum);
                }
                Arrays.sort(ns); Arrays.sort(bytes);
                System.out.printf("CHUNK_READ_RESULT pattern=%s chunks=%d ns=%.6f bytes=%.6f%n", pattern, count, ns[SAMPLES/2], bytes[SAMPLES/2]);
            }
        }
    }

    private static void check(Measurement m, long expected) {
        if (expected == 0 || m.checksum != expected) throw new AssertionError("Result/random sequence mismatch: " + m + " expected=" + expected);
    }

    private static long oracle(String pattern, Fixture f) {
        if (pattern.equals("level")) return (long)ITERATIONS * (Block.getId(Blocks.GRASS_BLOCK.defaultBlockState()) + 1);
        SimpleThreadUnsafeRandom random = new SimpleThreadUnsafeRandom(2387); long sum = 0;
        for (int i = 0; i < ITERATIONS; i++) {
            if (pattern.equals("grass") || pattern.equals("water")) {
                for (int n = 0; n < 4; n++) { random.nextInt(3); random.nextInt(5); random.nextInt(3); }
            }
            sum += random.nextInt();
        }
        return sum;
    }

    private static Measurement run(String pattern, Fixture f) {
        boolean read = pattern.equals("level");
        SimpleThreadUnsafeRandom random = new SimpleThreadUnsafeRandom(2387);
        long tid = Thread.currentThread().threadId(), before = ALLOC.getThreadAllocatedBytes(tid), start = System.nanoTime(), sum = 0;
        for (int i = 0; i < ITERATIONS; i++) {
            BlockPos pos = f.positions[i & (f.positions.length - 1)];
            if (read) sum += Block.getId(f.world.getBlockState(pos)) + 1;
            else {
                if (KNOWN_CHUNK) {
                    LevelChunk chunk = f.chunks[(i & (f.positions.length - 1)) >>> 2];
                    ((LatticeTickingBlock) Blocks.GRASS_BLOCK).lattice$randomTick(Blocks.GRASS_BLOCK.defaultBlockState(),
                        f.world, pos, random, chunk, chunk.getSection(0));
                } else {
                    Blocks.GRASS_BLOCK.defaultBlockState().randomTick(f.world, pos, random);
                }
                sum += random.nextInt();
            }
        }
        long nanos = System.nanoTime() - start, bytes = ALLOC.getThreadAllocatedBytes(tid) - before;
        sink = sum; return new Measurement(nanos, bytes, sum);
    }

    private record Measurement(long nanos, long bytes, long checksum) {}

    private static final class Fixture {
        final BenchWorld world = RandomTickTestSupport.allocate(BenchWorld.class);
        final BlockPos[] positions;
        final LevelChunk[] chunks;
        Fixture(String pattern, int count) {
            world.source = RandomTickTestSupport.allocate(ServerChunkCache.class);
            var full = new ConcurrentLong2ReferenceChainedHashTable<LevelChunk>();
            RandomTickTestSupport.field(world.source, ServerChunkCache.class, "fullChunks", full);
            world.config = RandomTickTestSupport.allocate(WorldConfiguration.class); world.config.tickRates = world.config.new TickRates();
            world.darkness = pattern.equals("dark") ? 15 : 0;
            RandomTickTestSupport.field(world, Level.class, "minY", 0); RandomTickTestSupport.field(world, Level.class, "maxY", 31);
            RandomTickTestSupport.field(world, Level.class, "minSectionY", 0); RandomTickTestSupport.field(world, Level.class, "maxSectionY", 1);
            var manager = RandomTickTestSupport.allocate(ChunkHolderManager.class);
            var holders = ConcurrentLong2ReferenceChainedHashTable.<NewChunkHolder>createWithCapacity(16384, 0.25f);
            RandomTickTestSupport.field(manager, ChunkHolderManager.class, "chunkHolders", holders);
            var scheduler = RandomTickTestSupport.allocate(ChunkTaskScheduler.class);
            RandomTickTestSupport.field(scheduler, ChunkTaskScheduler.class, "chunkHolderManager", manager);
            RandomTickTestSupport.field(world, ServerLevel.class, "chunkTaskScheduler", scheduler);
            positions = new BlockPos[count * 4];
            chunks = new LevelChunk[count];
            for (int i = 0; i < count; i++) {
                int x = (i % 32) * 2 - 32, z = (i / 32) * 2 - 32;
                LevelChunk chunk = RandomTickTestSupport.chunk(x, z, 2, 0);
                chunks[i] = chunk;
                RandomTickTestSupport.field(chunk, ChunkAccess.class, "levelHeightAccessor", LevelHeightAccessor.create(0, 32));
                RandomTickTestSupport.field(chunk, ChunkAccess.class, "locX", x); RandomTickTestSupport.field(chunk, ChunkAccess.class, "locZ", z);
                BlockState floor = pattern.equals("water") ? Blocks.DIRT.defaultBlockState() : Blocks.GRASS_BLOCK.defaultBlockState();
                for (int bx = 0; bx < 16; bx++) for (int bz = 0; bz < 16; bz++) {
                    chunk.getSection(0).setBlockState(bx, 3, bz, floor);
                    if (pattern.equals("water")) chunk.getSection(0).setBlockState(bx, 4, bz, Blocks.WATER.defaultBlockState());
                }
                SWMRNibbleArray[] sky = new SWMRNibbleArray[4], block = new SWMRNibbleArray[4];
                for (int n = 0; n < 4; n++) { sky[n] = nibble(15); block[n] = nibble(0); }
                chunk.starlight$setSkyNibbles(sky); chunk.starlight$setBlockNibbles(block);
                RandomTickTestSupport.field(chunk, ChunkAccess.class, "isLightCorrect", true);
                for (int n = 0; n < 4; n++) {
                    positions[i * 4 + n] = new BlockPos((x << 4) + 4 + n * 2, 3, (z << 4) + 4 + n * 2);
                    chunk.getSection(0).setBlockState(4 + n * 2, 3, 4 + n * 2, Blocks.GRASS_BLOCK.defaultBlockState());
                    chunk.getSection(0).setBlockState(4 + n * 2, 4, 4 + n * 2, Blocks.AIR.defaultBlockState());
                }
                if (!pattern.equals("unloaded")) {
                    long key = net.minecraft.world.level.ChunkPos.asLong(x, z); full.put(key, chunk);
                    NewChunkHolder holder = RandomTickTestSupport.allocate(NewChunkHolder.class);
                    RandomTickTestSupport.field(holder, NewChunkHolder.class, "lastChunkCompletion", new NewChunkHolder.ChunkCompletion(chunk, ChunkStatus.FULL));
                    holders.put(key, holder);
                }
            }
            world.lighting = new LevelLightEngine(new LightChunkGetter() {
                @Override public LightChunk getChunkForLighting(int x, int z) { return full.get(net.minecraft.world.level.ChunkPos.asLong(x, z)); }
                @Override public net.minecraft.world.level.BlockGetter getLevel() { return world; }
            }, true, true);
        }
    }

    private static SWMRNibbleArray nibble(int value) {
        byte[] data = new byte[2048]; Arrays.fill(data, (byte)(value | value << 4)); return new SWMRNibbleArray(data);
    }

    /** 仅覆盖环境接入；被测 Level、区块表、palette、草块和光照代码均为实际实现。 */
    private static final class BenchWorld extends ServerLevel {
        ServerChunkCache source; LevelLightEngine lighting; WorldConfiguration config; int darkness;
        private BenchWorld() { super(null, null, null, null, null, null, false, 0L, java.util.List.of(), false, null, null, null, null); }
        @Override public ServerChunkCache getChunkSource() { return source; }
        @Override public LevelLightEngine getLightEngine() { return lighting; }
        @Override public WorldConfiguration paperConfig() { return config; }
        @Override public int getSkyDarken() { return darkness; }
    }
}
