package com.latticemc.lattice.nativelib;

import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.NodeEvaluator;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathFinder;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.PathfindingContext;
import net.minecraft.world.level.pathfinder.Target;

/**
 * 独立 JVM 的寻路 Java-native 对比基准：在同一合成 serpentine 网格上分别跑
 * vanilla {@link PathFinder}（纯 Java A*）与 {@link NativePathfinder#findPath}（native A*），
 * 对照每次搜索的耗时与分配；两实现节点数必须一致。不是服务端 MSPT 基准。
 */
public final class PathfinderSearchBenchmark {
    private static final ThreadMXBean ALLOC = (ThreadMXBean) ManagementFactory.getThreadMXBean();
    private static volatile long sink;
    private static volatile boolean[] publishedGrid;
    private static volatile byte[] publishedPathTypes;
    private static volatile int[] publishedOut;

    private static Method vanillaFindPath;

    private enum Impl {
        VANILLA("vanilla"), NATIVE("native");
        final String label;
        Impl(final String label) { this.label = label; }
    }

    public static void main(final String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        if (!ALLOC.isThreadAllocatedMemorySupported()) {
            throw new IllegalStateException("allocation counter unavailable");
        }
        ALLOC.setThreadAllocatedMemoryEnabled(true);
        if (!NativePathfinder.isAvailable()) {
            throw new IllegalStateException("native pathfinder unavailable");
        }

        vanillaFindPath = PathFinder.class.getDeclaredMethod(
                "findPath", Node.class, List.class, float.class, int.class, float.class);
        vanillaFindPath.setAccessible(true);

        final int warmup = Integer.getInteger("lattice.pathWarmup", 6);
        final int samples = Integer.getInteger("lattice.pathSamples", 11);
        final boolean reverse = Boolean.getBoolean("lattice.pathReverse");
        final int sizeX = Integer.getInteger("lattice.pathSizeX", 64);
        final int sizeZ = Integer.getInteger("lattice.pathSizeZ", 64);
        final int iterations = Integer.getInteger("lattice.pathIterations", 128);

        System.out.printf("PATHFIND_BENCH java=%s warmup=%d samples=%d reverse=%s size=%dx%d iterations=%d%n",
                System.getProperty("java.version"), warmup, samples, reverse, sizeX, sizeZ, iterations);

        runConfig(sizeX, sizeZ, warmup, samples, reverse, iterations);
        if (sink == Long.MIN_VALUE) throw new AssertionError("no samples ran");
    }

    private static void runConfig(final int sizeX, final int sizeZ, final int warmup, final int samples,
                                  final boolean reverse, final int iterations) throws Exception {
        final boolean[] passable = buildGrid(sizeX, sizeZ);
        final int startX = 0, startZ = 1;
        final int targetX = sizeX - 1, targetZ = sizeZ - 2;
        final int maxNodes = sizeX * sizeZ;
        final float maxRange = 100000.0F;
        final NativeInputs ni = new NativeInputs(passable, sizeX, sizeZ, maxNodes);

        // 正确性：两实现节点数必须一致（在计时区外核对一次）。
        final int vanillaNodes = vanillaNodeCount(passable, sizeX, sizeZ, startX, startZ, targetX, targetZ, maxNodes, maxRange);
        final int nativeLen = nativeLength(ni, sizeX, sizeZ, startX, startZ, targetX, targetZ, maxNodes, maxRange);
        if (vanillaNodes <= 0 || vanillaNodes != nativeLen) {
            throw new AssertionError("path parity failed: vanilla=" + vanillaNodes + " native=" + nativeLen);
        }

        final Impl[] impls = Impl.values();
        final double[][] nanos = new double[impls.length][samples];
        final double[][] bytes = new double[impls.length][samples];
        for (int i = 0; i < warmup; i++) {
            for (final Impl impl : impls) {
                measure(impl, passable, ni, sizeX, sizeZ, startX, startZ, targetX, targetZ, maxNodes, maxRange, iterations);
            }
        }
        for (int i = 0; i < samples; i++) {
            for (int order = 0; order < impls.length; order++) {
                final int idx = (order + i + (reverse ? 1 : 0)) % impls.length;
                final Measurement m = measure(impls[idx], passable, ni, sizeX, sizeZ, startX, startZ, targetX, targetZ, maxNodes, maxRange, iterations);
                nanos[idx][i] = (double) m.nanos / iterations;
                bytes[idx][i] = (double) m.bytes / iterations;
            }
        }
        System.out.printf("PATHFIND_PARITY size=%dx%d nodes=%d%n", sizeX, sizeZ, vanillaNodes);
        for (final Impl impl : impls) {
            Arrays.sort(nanos[impl.ordinal()]);
            Arrays.sort(bytes[impl.ordinal()]);
            System.out.printf("PATHFIND_RESULT size=%dx%d impl=%s iterations=%d ns-per-search=%.1f bytes-per-search=%.2f%n",
                    sizeX, sizeZ, impl.label, iterations,
                    nanos[impl.ordinal()][samples / 2], bytes[impl.ordinal()][samples / 2]);
        }
    }

    private static int vanillaNodeCount(final boolean[] passable, final int sx, final int sz,
                                        final int startX, final int startZ, final int targetX, final int targetZ,
                                        final int maxNodes, final float maxRange) throws Exception {
        final GridNodeEvaluator evaluator = new GridNodeEvaluator(passable, sx, sz);
        final PathFinder finder = new PathFinder(evaluator, maxNodes);
        final Node start = evaluator.node(startX, startZ);
        final Target target = new Target(targetX, 0, targetZ);
        final Path path = (Path) vanillaFindPath.invoke(finder, start,
                List.of(Map.entry(target, new BlockPos(targetX, 0, targetZ))), maxRange, 0, 1.0F);
        return path == null ? -1 : path.getNodeCount();
    }

    private static int nativeLength(final NativeInputs ni, final int sx, final int sz,
                                    final int startX, final int startZ, final int targetX, final int targetZ,
                                    final int maxNodes, final float maxRange) {
        final NativePathfinder.PathResult r = NativePathfinder.findPath(
                ni.pathTypes, ni.floorLevels, 0, 0, 0, sx, 1, sz,
                startX, 0, startZ, new int[]{targetX}, new int[]{0}, new int[]{targetZ}, 1,
                maxRange, maxNodes, 0, 1, 1, 1.0F, 3, ni.malus, 1.125F, 0.6F,
                false, false, false, false, 0, ni.out);
        return r.reachedTarget() ? r.length() : -1;
    }

    private static Measurement measure(final Impl impl, final boolean[] passable, final NativeInputs ni,
                                       final int sx, final int sz, final int startX, final int startZ,
                                       final int targetX, final int targetZ, final int maxNodes,
                                       final float maxRange, final int iterations) throws Exception {
        publishedGrid = passable;
        publishedPathTypes = ni.pathTypes;
        publishedOut = ni.out;
        // vanilla 的 target 列表与 impl 无关，循环外构造；仅每次搜索新建 evaluator/finder/start（其真实分配）。
        final Target target = new Target(targetX, 0, targetZ);
        final List<Map.Entry<Target, BlockPos>> targets = List.of(Map.entry(target, new BlockPos(targetX, 0, targetZ)));
        final long thread = Thread.currentThread().threadId();
        final long beforeBytes = ALLOC.getThreadAllocatedBytes(thread);
        final long beforeTime = System.nanoTime();
        long acc = 0;
        for (int i = 0; i < iterations; i++) {
            if (impl == Impl.VANILLA) {
                final GridNodeEvaluator evaluator = new GridNodeEvaluator(passable, sx, sz);
                final PathFinder finder = new PathFinder(evaluator, maxNodes);
                final Node start = evaluator.node(startX, startZ);
                final Path path = (Path) vanillaFindPath.invoke(finder, start, targets, maxRange, 0, 1.0F);
                acc += path == null ? 0 : path.getNodeCount();
            } else {
                final NativePathfinder.PathResult r = NativePathfinder.findPath(
                        ni.pathTypes, ni.floorLevels, 0, 0, 0, sx, 1, sz,
                        startX, 0, startZ, new int[]{targetX}, new int[]{0}, new int[]{targetZ}, 1,
                        maxRange, maxNodes, 0, 1, 1, 1.0F, 3, ni.malus, 1.125F, 0.6F,
                        false, false, false, false, 0, ni.out);
                acc += r.length();
            }
        }
        final long nanos = System.nanoTime() - beforeTime;
        final long bytes = ALLOC.getThreadAllocatedBytes(thread) - beforeBytes;
        sink = acc;
        return new Measurement(nanos, bytes);
    }

    /** Serpentine 迷宫：每 8 列一道墙，缺口上下交替，保证连通且强制长路径。 */
    private static boolean[] buildGrid(final int sx, final int sz) {
        final boolean[] passable = new boolean[sx * sz];
        Arrays.fill(passable, true);
        for (int x = 0; x < sx; x++) {
            if (x % 8 != 3) continue;
            final int gap = ((x / 8) & 1) == 0 ? 1 : sz - 2;
            for (int z = 0; z < sz; z++) {
                if (z != gap) passable[z * sx + x] = false;
            }
        }
        return passable;
    }

    private static final class NativeInputs {
        final byte[] pathTypes;
        final float[] floorLevels;
        final float[] malus;
        final int[] out;

        NativeInputs(final boolean[] passable, final int sx, final int sz, final int maxNodes) {
            this.pathTypes = new byte[passable.length];
            for (int i = 0; i < passable.length; i++) {
                this.pathTypes[i] = (byte) (passable[i] ? PathType.WALKABLE.ordinal() : PathType.BLOCKED.ordinal());
            }
            this.floorLevels = new float[passable.length];
            this.malus = NativePathfinder.pathfindingMalusFor(PathType.values());
            this.out = new int[3 + maxNodes * 3];
        }
    }

    private static final class GridNodeEvaluator extends NodeEvaluator {
        private final boolean[] passable;
        private final int sx;
        private final int sz;
        private final Node[] cardinals = new Node[4];

        GridNodeEvaluator(final boolean[] passable, final int sx, final int sz) {
            this.passable = passable;
            this.sx = sx;
            this.sz = sz;
        }

        private int index(final int x, final int z) { return z * this.sx + x; }

        Node node(final int x, final int z) { return this.getNode(x, 0, z); }

        @Override
        public Node getStart() { return this.node(0, 1); }

        @Override
        public Target getTarget(final double x, final double y, final double z) {
            return new Target((int) x, (int) y, (int) z);
        }

        @Override
        public int getNeighbors(final Node[] output, final Node node) {
            int count = 0;
            int cardinalIndex = 0;
            for (final Direction direction : Direction.Plane.HORIZONTAL) {
                final Node neighbor = this.passable(node.x + direction.getStepX(), node.z + direction.getStepZ());
                this.cardinals[cardinalIndex++] = neighbor;
                if (neighbor != null && !neighbor.closed) output[count++] = neighbor;
            }
            for (int direction = 0; direction < 4; ++direction) {
                final Node first = this.cardinals[direction];
                final Node second = this.cardinals[(direction + 1) & 3];
                if (first == null || second == null) continue;
                final int x = first.x + second.x - node.x;
                final int z = first.z + second.z - node.z;
                final Node diagonal = this.passable(x, z);
                if (diagonal != null && !diagonal.closed) output[count++] = diagonal;
            }
            return count;
        }

        private Node passable(final int x, final int z) {
            if (x < 0 || x >= this.sx || z < 0 || z >= this.sz || !this.passable[index(x, z)]) return null;
            final Node node = this.node(x, z);
            node.type = PathType.WALKABLE;
            node.costMalus = 0.0F;
            return node;
        }

        @Override
        public PathType getPathTypeOfMob(final PathfindingContext context, final int x, final int y, final int z, final Mob mob) {
            return PathType.WALKABLE;
        }

        @Override
        public PathType getPathType(final PathfindingContext context, final int x, final int y, final int z) {
            return PathType.WALKABLE;
        }
    }

    private record Measurement(long nanos, long bytes) { }
}
