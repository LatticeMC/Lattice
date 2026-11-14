package com.latticemc.lattice.nativelib;

import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;
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
 * vanilla {@link PathFinder}（纯 Java A*）、关闭快路径的 native A*，以及开启 cardinal/diagonal
 * 快路径的 native A*，对照每次搜索的耗时与分配；三实现节点数必须一致。
 * 不是服务端 MSPT 基准。
 */
public final class PathfinderSearchBenchmark {
    private static final ThreadMXBean ALLOC = (ThreadMXBean) ManagementFactory.getThreadMXBean();
    private static volatile long sink;
    private static volatile boolean[] publishedGrid;
    private static volatile byte[] publishedPathTypes;
    private static volatile int[] publishedOut;

    private static Method vanillaFindPath;

    /**
     * 生产入口选择：{@code kernel}（默认）走 benchmark 专用的扁平 {@code findPath}，仅测 A* 内核；
     * {@code snapshot} 走生产真正使用的 {@code findPathFromStateSnapshot}（含 descriptor 解码 + mirror 上传
     * 固定开销），调用链贴近生产。生产热路径是 {@code findPathFromStateMirror}（命中缓存），冷路径才是
     * snapshot；本档测的是冷路径上限。
     */
    private static final String PATH_ENTRY = System.getProperty("lattice.pathEntry",
            System.getenv().getOrDefault("LATTICE_PATH_ENTRY", "kernel"));
    /** 路径长度：{@code long}（默认，强制长路径）或 {@code short}（起点附近短路径，暴露固定开销悬崖）。 */
    private static final String PATH_LENGTH = System.getProperty("lattice.pathLength",
            System.getenv().getOrDefault("LATTICE_PATH_LENGTH", "long"));
    // 状态快照编码常量：快照比搜索区外扩一圈（x/z 各 -1..+1），并在 y 方向包含地板层(y=-1)与头部净空层(y=1)。
    private static long SNAPSHOT_WORLD_KEY;
    private static final int SNAPSHOT_LEVEL_MIN_Y = -64;
    private static final int SNAPSHOT_STATE_MIN_X = -1;
    private static final int SNAPSHOT_STATE_MIN_Y = -1;
    private static final int SNAPSHOT_STATE_MIN_Z = -1;
    private static final int SNAPSHOT_STATE_SIZE_Y = 3;

    private enum Impl {
        VANILLA("vanilla"), NATIVE("native"), NATIVE_FASTPATH("native-fastpath"),
        NATIVE_SNAPSHOT_LEGACY("snapshot-old-order"), NATIVE_SNAPSHOT_FUSED("snapshot-fused"),
        NATIVE_MIRROR("native-mirror");
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
        NativePathfinder.setCardinalFastPathStatsEnabledForBenchmark(false);
        NativePathfinder.setCardinalFastPathEnabledForBenchmark(true);

        vanillaFindPath = PathFinder.class.getDeclaredMethod(
                "findPath", Node.class, List.class, float.class, int.class, float.class);
        vanillaFindPath.setAccessible(true);

        final int warmup = Integer.getInteger("lattice.pathWarmup", 6);
        final int samples = Integer.getInteger("lattice.pathSamples", 11);
        final int sizeX = Integer.getInteger("lattice.pathSizeX", 64);
        final int sizeZ = Integer.getInteger("lattice.pathSizeZ", sizeX);
        final int iterations = Integer.getInteger("lattice.pathIterations", 128);
        final boolean reverse = Boolean.getBoolean("lattice.pathReverse");
        final String world = System.getProperty("lattice.pathWorld",
                System.getenv().getOrDefault("LATTICE_PATH_WORLD", "serpentine"));
        if (sizeX <= 0 || sizeZ <= 0 || warmup < 0 || samples <= 0 || iterations <= 0) {
            throw new IllegalArgumentException("path benchmark dimensions and iteration counts must be positive (warmup may be zero)");
        }
        if (!PATH_ENTRY.equals("snapshot") && !PATH_ENTRY.equals("kernel")) {
            throw new IllegalArgumentException("lattice.pathEntry must be snapshot or kernel: " + PATH_ENTRY);
        }
        if (!PATH_LENGTH.equals("short") && !PATH_LENGTH.equals("long")) {
            throw new IllegalArgumentException("lattice.pathLength must be short or long: " + PATH_LENGTH);
        }
        System.out.printf("PATHFIND_SCALING java=%s vm=%s os=%s arch=%s jvmArgs=%s entry=%s length=%s world=%s size=%dx%d warmup=%d samples=%d iterations=%d reverse=%s%n",
                System.getProperty("java.version"), System.getProperty("java.vm.name"),
                System.getProperty("os.name"), System.getProperty("os.arch"),
                ManagementFactory.getRuntimeMXBean().getInputArguments(),
                PATH_ENTRY, PATH_LENGTH, world, sizeX, sizeZ, warmup, samples, iterations, reverse);
        SNAPSHOT_WORLD_KEY = NativePathfinder.createMirrorWorld();
        try {
            if (world.equals("serpentine") || world.equals("both")) {
                runConfig(sizeX, sizeZ, warmup, samples, reverse, iterations);
            }
            if (world.equals("village") || world.equals("both")) {
                final byte[] vpt = buildVillageGrid(sizeX, sizeZ);
                final float[] vfloor = new float[vpt.length];
                runVillageConfig(vpt, vfloor, sizeX, sizeZ, 0, 1, sizeX - 1, sizeZ - 2,
                        warmup, samples, reverse, iterations);
            }
            if (!world.equals("serpentine") && !world.equals("village") && !world.equals("both")) {
                throw new IllegalArgumentException("lattice.pathWorld must be serpentine, village, or both: " + world);
            }
        } finally {
            NativePathfinder.retireMirrorWorld(SNAPSHOT_WORLD_KEY);
            NativePathfinder.setCardinalFastPathStatsEnabledForBenchmark(false);
            NativePathfinder.setCardinalFastPathEnabledForBenchmark(true);
        }
        if (sink == Long.MIN_VALUE) throw new AssertionError("no samples ran");
    }

    private static void runConfig(final int sizeX, final int sizeZ, final int warmup, final int samples,
                                  final boolean reverse, final int iterations) throws Exception {
        final boolean shortPath = "short".equals(PATH_LENGTH);
        final boolean useSnapshot = "snapshot".equals(PATH_ENTRY);
        final boolean[] passable = shortPath ? buildOpenGrid(sizeX, sizeZ) : buildGrid(sizeX, sizeZ);
        final int startX, startZ, targetX, targetZ;
        if (shortPath) {
            startX = 2; startZ = 2; targetX = 5; targetZ = 2;
        } else {
            startX = 0; startZ = 1; targetX = sizeX - 1; targetZ = sizeZ - 2;
        }
        final int maxNodes = sizeX * sizeZ;
        final float maxRange = 100000.0F;
        final NativeInputs ni = new NativeInputs(passable, sizeX, sizeZ, maxNodes, useSnapshot);

        // 正确性：vanilla 与 native 内核（关/开快路径）节点数必须一致；snapshot 入口下再比对 native-snapshot。
        final Path vanillaPath = vanillaPath(passable, sizeX, sizeZ, startX, startZ,
                targetX, targetZ, maxNodes, maxRange);
        final int vanillaNodes = vanillaPath == null ? -1 : vanillaPath.getNodeCount();
        final int nativeBaselineNodes = nativeLength(ni, sizeX, sizeZ, startX, startZ, targetX, targetZ,
                maxNodes, maxRange, false);
        final int nativeFastPathNodes = nativeLength(ni, sizeX, sizeZ, startX, startZ, targetX, targetZ,
                maxNodes, maxRange, true);
        int nativeSnapshotNodes = -1;
        int nativeSnapshotFusedNodes = -1;
        int nativeMirrorNodes = -1;
        if (useSnapshot) {
            final NativePathfinder.PathResult oldOrder = callNativeResult(Impl.NATIVE_SNAPSHOT_LEGACY,
                    ni, sizeX, sizeZ, startX, startZ, targetX, targetZ, maxNodes, maxRange);
            final NativePathfinder.PathResult fused = callNativeResult(Impl.NATIVE_SNAPSHOT_FUSED,
                    ni, sizeX, sizeZ, startX, startZ, targetX, targetZ, maxNodes, maxRange);
            // 预热 mirror：snapshot 入口会写入全局 mirror，使后续 findPathFromStateMirror 命中（生产热路径）。
            final NativePathfinder.PathResult mirror = callNativeResult(Impl.NATIVE_MIRROR,
                    ni, sizeX, sizeZ, startX, startZ, targetX, targetZ, maxNodes, maxRange);
            nativeSnapshotNodes = reachedLength(oldOrder);
            nativeSnapshotFusedNodes = reachedLength(fused);
            nativeMirrorNodes = reachedLength(mirror);
            assertPathParity("snapshot-old-order", vanillaPath, oldOrder);
            assertPathParity("snapshot-fused", vanillaPath, fused);
            assertPathParity("native-mirror", vanillaPath, mirror);
            assertNativePathParity("snapshot old/fused", oldOrder, fused);
            assertNativePathParity("snapshot fused/mirror", fused, mirror);
        }
        if (vanillaNodes <= 0 || vanillaNodes != nativeBaselineNodes || vanillaNodes != nativeFastPathNodes
                || (useSnapshot && (vanillaNodes != nativeSnapshotNodes
                        || vanillaNodes != nativeSnapshotFusedNodes || vanillaNodes != nativeMirrorNodes))) {
            throw new AssertionError("path parity failed: vanilla=" + vanillaNodes
                    + " native=" + nativeBaselineNodes + " native-fastpath=" + nativeFastPathNodes
                    + (useSnapshot ? " snapshot-old-order=" + nativeSnapshotNodes
                            + " snapshot-fused=" + nativeSnapshotFusedNodes + " native-mirror=" + nativeMirrorNodes : ""));
        }

        final Impl[] impls = useSnapshot
                ? new Impl[]{Impl.VANILLA, Impl.NATIVE_SNAPSHOT_LEGACY, Impl.NATIVE_SNAPSHOT_FUSED, Impl.NATIVE_MIRROR}
                : new Impl[]{Impl.VANILLA, Impl.NATIVE, Impl.NATIVE_FASTPATH};
        final double[][] nanos = new double[Impl.values().length][samples];
        final double[][] bytes = new double[Impl.values().length][samples];
        final double[] hitRates = new double[Impl.values().length];
        for (int i = 0; i < warmup; i++) {
            for (final Impl impl : impls) {
                measure(impl, passable, ni, sizeX, sizeZ, startX, startZ, targetX, targetZ, maxNodes, maxRange, iterations);
            }
        }
        for (int i = 0; i < samples; i++) {
            for (int order = 0; order < impls.length; order++) {
                final int idx = Math.floorMod(i + (reverse ? -order : order), impls.length);
                final Impl impl = impls[idx];
                final Measurement m = measure(impl, passable, ni, sizeX, sizeZ, startX, startZ, targetX, targetZ, maxNodes, maxRange, iterations);
                nanos[impl.ordinal()][i] = (double) m.nanos / iterations;
                bytes[impl.ordinal()][i] = (double) m.bytes / iterations;
            }
        }
        validateMeasurements(impls, nanos, bytes, samples);
        final FastPathDiagnostics diagnostics = measureFastPathHitRate(
                ni, sizeX, sizeZ, startX, startZ, targetX, targetZ, maxNodes, maxRange, iterations);
        for (final Impl impl : impls) {
            if (impl == Impl.NATIVE_FASTPATH) hitRates[impl.ordinal()] = diagnostics.hitRate;
        }
        printRawSamples("serpentine", sizeX, sizeZ, impls, nanos, bytes, samples);
        System.out.printf("PATHFIND_PARITY entry=%s length=%s size=%dx%d nodes=%d%n", PATH_ENTRY, PATH_LENGTH, sizeX, sizeZ, vanillaNodes);
        System.out.printf("PATHFIND_FASTPATH entry=%s length=%s size=%dx%d cardinal-candidates=%d cardinal-hits=%d out-of-region=%d cardinal-hit-rate=%.2f%% top-misses=%s%n",
                PATH_ENTRY, PATH_LENGTH, sizeX, sizeZ, diagnostics.candidates, diagnostics.hits, diagnostics.outOfRegion,
                diagnostics.hitRate * 100.0, diagnostics.topMisses);
        for (final Impl impl : impls) {
            final double nsPerSearch = median(nanos[impl.ordinal()]);
            final double bytesPerSearch = median(bytes[impl.ordinal()]);
            System.out.printf("PATHFIND_RESULT entry=%s length=%s size=%dx%d impl=%s pathNodes=%d ns-per-search=%.1f ns-per-node=%.3f bytes-per-search=%.1f fastpath-hit-rate=%.2f%%%n",
                    PATH_ENTRY, PATH_LENGTH, sizeX, sizeZ, impl.label, vanillaNodes, nsPerSearch, nsPerSearch / vanillaNodes,
                    bytesPerSearch, hitRates[impl.ordinal()] * 100.0);
        }
    }

    private static double median(final double[] values) {
        Arrays.sort(values);
        final int middle = values.length / 2;
        return values.length % 2 == 0
                ? (values[middle - 1] + values[middle]) / 2.0
                : values[middle];
    }

    private static void validateMeasurements(final Impl[] impls, final double[][] nanos,
                                             final double[][] bytes, final int samples) {
        for (final Impl impl : impls) {
            for (int sample = 0; sample < samples; sample++) {
                final double elapsed = nanos[impl.ordinal()][sample];
                final double allocated = bytes[impl.ordinal()][sample];
                if (!Double.isFinite(elapsed) || elapsed <= 0.0
                        || !Double.isFinite(allocated) || allocated < 0.0) {
                    throw new AssertionError("invalid benchmark sample: impl=" + impl.label
                            + " sample=" + sample + " ns-per-search=" + elapsed
                            + " bytes-per-search=" + allocated);
                }
            }
        }
    }

    private static int vanillaNodeCount(final boolean[] passable, final int sx, final int sz,
                                        final int startX, final int startZ, final int targetX, final int targetZ,
                                        final int maxNodes, final float maxRange) throws Exception {
        final Path path = vanillaPath(passable, sx, sz, startX, startZ, targetX, targetZ, maxNodes, maxRange);
        return path == null ? -1 : path.getNodeCount();
    }

    private static Path vanillaPath(final boolean[] passable, final int sx, final int sz,
                                    final int startX, final int startZ, final int targetX, final int targetZ,
                                    final int maxNodes, final float maxRange) throws Exception {
        final GridNodeEvaluator evaluator = new GridNodeEvaluator(passable, sx, sz);
        final PathFinder finder = new PathFinder(evaluator, maxNodes);
        final Node start = evaluator.node(startX, startZ);
        final Target target = new Target(targetX, 0, targetZ);
        return (Path) vanillaFindPath.invoke(finder, start,
                List.of(Map.entry(target, new BlockPos(targetX, 0, targetZ))), maxRange, 0, 1.0F);
    }

    /**
     * 统一 native 搜索入口。{@code NATIVE_SNAPSHOT} 走生产真正使用的 {@code findPathFromStateSnapshot}
     * （含 descriptor 解码 + mirror 上传），其余走 benchmark 专用的扁平 {@code findPath} 内核。
     */
    private static int callNative(final Impl impl, final NativeInputs ni, final int sx, final int sz,
                                 final int startX, final int startZ, final int targetX, final int targetZ,
                                 final int maxNodes, final float maxRange) {
        return reachedLength(callNativeResult(impl, ni, sx, sz, startX, startZ,
                targetX, targetZ, maxNodes, maxRange));
    }

    private static int reachedLength(final NativePathfinder.PathResult result) {
        return result != null && result.reachedTarget() ? result.length() : -1;
    }

    private static NativePathfinder.PathResult callNativeResult(final Impl impl, final NativeInputs ni,
                                                                 final int sx, final int sz,
                                                                 final int startX, final int startZ,
                                                                 final int targetX, final int targetZ,
                                                                 final int maxNodes, final float maxRange) {
        if (impl == Impl.NATIVE_SNAPSHOT_LEGACY || impl == Impl.NATIVE_SNAPSHOT_FUSED) {
            final SnapshotEncoding s = ni.snapshot;
            NativePathfinder.setCardinalFastPathEnabledForBenchmark(true);
            return NativePathfinder.findPathFromStateSnapshotForBenchmark(
                    s.cells, s.descriptorPathTypes, s.descriptorFloorHeights, s.descriptorCount,
                    s.stateMinX, s.stateMinY, s.stateMinZ, s.stateSizeX, s.stateSizeY, s.stateSizeZ,
                    0, 0, 0, sx, 1, sz, startX, 0, startZ,
                    new int[]{targetX}, new int[]{0}, new int[]{targetZ}, 1,
                    maxRange, maxNodes, 0, 1, 1, 1.0F, 3, ni.malus, 1.125F, 0.6F,
                    true, false, startX, 0, startZ, false, false, false, false,
                    SNAPSHOT_LEVEL_MIN_Y, SNAPSHOT_WORLD_KEY, ni.out,
                    impl == Impl.NATIVE_SNAPSHOT_LEGACY);
        }
        if (impl == Impl.NATIVE_MIRROR) {
            NativePathfinder.setCardinalFastPathEnabledForBenchmark(true);
            return NativePathfinder.findPathFromStateMirror(
                    SNAPSHOT_WORLD_KEY, 0, 0, 0, sx, 1, sz, startX, 0, startZ,
                    new int[]{targetX}, new int[]{0}, new int[]{targetZ}, 1,
                    maxRange, maxNodes, 0, 1, 1, 1.0F, 3, ni.malus, 1.125F, 0.6F,
                    true, false, startX, 0, startZ, false, false, false, false,
                    SNAPSHOT_LEVEL_MIN_Y, ni.out);
        }
        NativePathfinder.setCardinalFastPathEnabledForBenchmark(impl == Impl.NATIVE_FASTPATH);
        return NativePathfinder.findPath(
                ni.pathTypes, ni.floorLevels, 0, 0, 0, sx, 1, sz,
                startX, 0, startZ, new int[]{targetX}, new int[]{0}, new int[]{targetZ}, 1,
                maxRange, maxNodes, 0, 1, 1, 1.0F, 3, ni.malus, 1.125F, 0.6F,
                false, false, false, false, 0, ni.out);
    }

    private static void assertPathParity(final String label, final Path vanilla,
                                         final NativePathfinder.PathResult nativePath) {
        if (vanilla == null || nativePath == null) {
            throw new AssertionError(label + " path missing: vanilla=" + (vanilla != null)
                    + " native=" + (nativePath != null));
        }
        if (vanilla.canReach() != nativePath.reachedTarget() || vanilla.getNodeCount() != nativePath.length()) {
            throw new AssertionError(label + " path header mismatch: vanillaReach=" + vanilla.canReach()
                    + " nativeReach=" + nativePath.reachedTarget() + " vanillaNodes=" + vanilla.getNodeCount()
                    + " nativeNodes=" + nativePath.length());
        }
        if (nativePath.targetIndex() != 0) {
            throw new AssertionError(label + " target index mismatch: " + nativePath.targetIndex());
        }
        for (int i = 0; i < vanilla.getNodeCount(); i++) {
            final Node node = vanilla.getNode(i);
            if (node.x != nativePath.x(i) || node.y != nativePath.y(i) || node.z != nativePath.z(i)) {
                throw new AssertionError(label + " coordinate mismatch at " + i + ": vanilla=("
                        + node.x + "," + node.y + "," + node.z + ") native=("
                        + nativePath.x(i) + "," + nativePath.y(i) + "," + nativePath.z(i) + ")");
            }
        }
    }

    private static void assertNativePathParity(final String label,
                                               final NativePathfinder.PathResult expected,
                                               final NativePathfinder.PathResult actual) {
        if (expected == null || actual == null
                || expected.reachedTarget() != actual.reachedTarget()
                || expected.targetIndex() != actual.targetIndex()
                || expected.length() != actual.length()) {
            throw new AssertionError(label + " path header mismatch");
        }
        for (int i = 0; i < expected.length(); i++) {
            if (expected.x(i) != actual.x(i) || expected.y(i) != actual.y(i) || expected.z(i) != actual.z(i)) {
                throw new AssertionError(label + " coordinate mismatch at " + i);
            }
        }
    }

    private static void printRawSamples(final String world, final int sx, final int sz, final Impl[] impls,
                                        final double[][] nanos, final double[][] bytes, final int samples) {
        for (final Impl impl : impls) {
            for (int sample = 0; sample < samples; sample++) {
                System.out.printf("PATHFIND_SAMPLE world=%s entry=%s length=%s size=%dx%d impl=%s sample=%d ns-per-search=%.1f bytes-per-search=%.1f%n",
                        world, PATH_ENTRY, PATH_LENGTH, sx, sz, impl.label, sample,
                        nanos[impl.ordinal()][sample], bytes[impl.ordinal()][sample]);
            }
        }
    }

    private static int nativeLength(final NativeInputs ni, final int sx, final int sz,
                                    final int startX, final int startZ, final int targetX, final int targetZ,
                                    final int maxNodes, final float maxRange, final boolean fastPath) {
        return callNative(fastPath ? Impl.NATIVE_FASTPATH : Impl.NATIVE, ni, sx, sz,
                startX, startZ, targetX, targetZ, maxNodes, maxRange);
    }

    private static FastPathDiagnostics measureFastPathHitRate(final NativeInputs ni, final int sx, final int sz,
                                                               final int startX, final int startZ,
                                                               final int targetX, final int targetZ,
                                                               final int maxNodes, final float maxRange,
                                                               final int iterations) {
        NativePathfinder.setCardinalFastPathEnabledForBenchmark(true);
        NativePathfinder.resetCardinalFastPathStatsForBenchmark();
        NativePathfinder.setCardinalFastPathStatsEnabledForBenchmark(true);
        try {
            long acc = 0L;
            for (int i = 0; i < iterations; i++) {
                final NativePathfinder.PathResult r = NativePathfinder.findPath(
                        ni.pathTypes, ni.floorLevels, 0, 0, 0, sx, 1, sz,
                        startX, 0, startZ, new int[]{targetX}, new int[]{0}, new int[]{targetZ}, 1,
                        maxRange, maxNodes, 0, 1, 1, 1.0F, 3, ni.malus, 1.125F, 0.6F,
                        false, false, false, false, 0, ni.out);
                acc += r.length();
            }
            sink = acc;
            final long[] buckets = NativePathfinder.cardinalFastPathDiagnosticsForBenchmark();
            final long hits = buckets[0];
            final long candidates = buckets[1];
            return new FastPathDiagnostics(candidates == 0L ? 0.0 : (double) hits / candidates,
                    hits, candidates, buckets[2], topMisses(buckets));
        } finally {
            NativePathfinder.setCardinalFastPathStatsEnabledForBenchmark(false);
        }
    }

    private static String topMisses(final long[] diagnostics) {
        final PathType[] types = PathType.values();
        final List<TypeBucket> buckets = new ArrayList<>();
        for (int origin = 0; origin < types.length; origin++) {
            for (int neighbor = 0; neighbor < types.length; neighbor++) {
                final int offset = 3 + (origin * types.length + neighbor) * 2;
                final long candidates = diagnostics[offset];
                final long hits = diagnostics[offset + 1];
                final long misses = candidates - hits;
                if (misses > 0L) buckets.add(new TypeBucket(types[origin].name(), types[neighbor].name(), misses));
            }
        }
        buckets.sort(Comparator.comparingLong(TypeBucket::misses).reversed());
        final StringJoiner result = new StringJoiner(",");
        for (int i = 0; i < Math.min(12, buckets.size()); i++) {
            final TypeBucket bucket = buckets.get(i);
            result.add(bucket.origin + "->" + bucket.neighbor + ":" + bucket.misses);
        }
        return result.length() == 0 ? "none" : result.toString();
    }

    private static Measurement measure(final Impl impl, final boolean[] passable, final NativeInputs ni,
                                       final int sx, final int sz, final int startX, final int startZ,
                                       final int targetX, final int targetZ, final int maxNodes,
                                       final float maxRange, final int iterations) throws Exception {
        if (impl == Impl.NATIVE) {
            NativePathfinder.setCardinalFastPathEnabledForBenchmark(false);
        } else if (impl == Impl.NATIVE_FASTPATH) {
            NativePathfinder.setCardinalFastPathEnabledForBenchmark(true);
        }
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
                acc += callNative(impl, ni, sx, sz, startX, startZ, targetX, targetZ, maxNodes, maxRange);
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

    /** 全开放网格（短路径档用）：所有格可走，强制短路径以暴露固定开销悬崖。 */
    private static boolean[] buildOpenGrid(final int sx, final int sz) {
        final boolean[] passable = new boolean[sx * sz];
        Arrays.fill(passable, true);
        return passable;
    }

    /** 全开放 pathType 网格（短路径档用，byte 版）。 */
    private static byte[] buildOpenPathTypes(final int sx, final int sz) {
        final byte[] pt = new byte[sx * sz];
        Arrays.fill(pt, (byte) PathType.WALKABLE.ordinal());
        return pt;
    }

    /** 状态快照编码：descriptor 索引体积 + descriptor 表（pathType + 层高）。 */
    private static final class SnapshotEncoding {
        final int[] cells;
        final byte[] descriptorPathTypes;
        final float[] descriptorFloorHeights;
        final int descriptorCount;
        final int stateMinX, stateMinY, stateMinZ, stateSizeX, stateSizeY, stateSizeZ;

        SnapshotEncoding(final int[] cells, final byte[] descriptorPathTypes, final float[] descriptorFloorHeights,
                         final int descriptorCount, final int stateMinX, final int stateMinY, final int stateMinZ,
                         final int stateSizeX, final int stateSizeY, final int stateSizeZ) {
            this.cells = cells;
            this.descriptorPathTypes = descriptorPathTypes;
            this.descriptorFloorHeights = descriptorFloorHeights;
            this.descriptorCount = descriptorCount;
            this.stateMinX = stateMinX;
            this.stateMinY = stateMinY;
            this.stateMinZ = stateMinZ;
            this.stateSizeX = stateSizeX;
            this.stateSizeY = stateSizeY;
            this.stateSizeZ = stateSizeZ;
        }
    }

    /**
     * 把单层地形（y=0 的 pathType 扁平数组）编码成 native {@code findPathFromStateSnapshot} 要求的
     * descriptor 三明治：y=-1 地板层(BLOCKED, 层高 1.0 → 站在 y=0)、y=0 地形层、y=1 头部净空层(OPEN)。
     * x/z 各外扩一圈（匹配生产 mirror 覆盖查询的外扩），外圈地形层填 BLOCKED。
     */
    private static SnapshotEncoding encodeSnapshot(final byte[] flat, final int sx, final int sz) {
        final int stateSizeX = sx + 2;
        final int stateSizeZ = sz + 2;
        final int stateSizeY = SNAPSHOT_STATE_SIZE_Y;
        final int[] cells = new int[stateSizeX * stateSizeY * stateSizeZ];
        final byte[] descriptorPathTypes = new byte[] {
                (byte) PathType.BLOCKED.ordinal(),        // 0: 地板
                (byte) PathType.OPEN.ordinal(),           // 1: 头部净空/空气
                (byte) PathType.WALKABLE.ordinal(),       // 2: 可走
                (byte) PathType.BLOCKED.ordinal(),        // 3: 地形阻挡/外圈
                (byte) PathType.WALKABLE_DOOR.ordinal(),  // 4: 可走门
                (byte) PathType.FENCE.ordinal(),          // 5: 栏杆
                (byte) PathType.DOOR_WOOD_CLOSED.ordinal()// 6: 封闭木门
        };
        final float[] descriptorFloorHeights = new float[] { 1.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F };
        for (int y = -1; y <= 1; y++) {
            for (int z = -1; z < sz + 1; z++) {
                for (int x = -1; x < sx + 1; x++) {
                    final int lx = x - SNAPSHOT_STATE_MIN_X;
                    final int ly = y - SNAPSHOT_STATE_MIN_Y;
                    final int lz = z - SNAPSHOT_STATE_MIN_Z;
                    final int index = ((ly * stateSizeZ) + lz) * stateSizeX + lx;
                    final int descriptor;
                    if (y == -1) {
                        descriptor = 0;
                    } else if (y == 1) {
                        descriptor = 1;
                    } else if (x >= 0 && x < sx && z >= 0 && z < sz) {
                        final int t = flat[z * sx + x] & 0xFF;
                        descriptor = switch (t) {
                            case 0 -> 3;  // BLOCKED
                            case 2 -> 2;  // WALKABLE
                            case 3 -> 4;  // WALKABLE_DOOR
                            case 7 -> 5;  // FENCE
                            case 18 -> 6; // DOOR_WOOD_CLOSED
                            default -> 3;
                        };
                    } else {
                        descriptor = 3;
                    }
                    cells[index] = descriptor;
                }
            }
        }
        return new SnapshotEncoding(cells, descriptorPathTypes, descriptorFloorHeights, descriptorPathTypes.length,
                SNAPSHOT_STATE_MIN_X, SNAPSHOT_STATE_MIN_Y, SNAPSHOT_STATE_MIN_Z,
                stateSizeX, stateSizeY, stateSizeZ);
    }

    private static final class NativeInputs {
        final byte[] pathTypes;
        final float[] floorLevels;
        final float[] malus;
        final int[] out;
        final SnapshotEncoding snapshot;

        NativeInputs(final boolean[] passable, final int sx, final int sz, final int maxNodes, final boolean buildSnapshot) {
            this.pathTypes = new byte[passable.length];
            for (int i = 0; i < passable.length; i++) {
                this.pathTypes[i] = (byte) (passable[i] ? PathType.WALKABLE.ordinal() : PathType.BLOCKED.ordinal());
            }
            this.floorLevels = new float[passable.length];
            this.malus = NativePathfinder.pathfindingMalusFor(PathType.values());
            this.out = new int[3 + maxNodes * 3];
            this.snapshot = buildSnapshot ? encodeSnapshot(this.pathTypes, sx, sz) : null;
        }

        NativeInputs(final byte[] pathTypes, final float[] floorLevels, final int sx, final int sz, final int maxNodes, final boolean buildSnapshot) {
            this.pathTypes = pathTypes;
            this.floorLevels = floorLevels;
            this.malus = NativePathfinder.pathfindingMalusFor(PathType.values());
            this.out = new int[3 + maxNodes * 3];
            this.snapshot = buildSnapshot ? encodeSnapshot(this.pathTypes, sx, sz) : null;
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

    private record FastPathDiagnostics(double hitRate, long hits, long candidates,
                                       long outOfRegion, String topMisses) { }

    private record TypeBucket(String origin, String neighbor, long misses) { }

    /**
     * 村庄式地形的 pathType 网格（单层，y=0）：房间由 BLOCKED 墙分隔，墙上的缺口用
     * WALKABLE_DOOR（可走，但需完整求值，不走快路径）打通；另撒若干 FENCE（可走但带 malus）
     * 与一处 DOOR_WOOD_CLOSED（封闭门，作为 closed 节点）以压低快路径命中率。编码与 native
     * 的 PathType 序数一致（BLOCKED=0, WALKABLE=2, WALKABLE_DOOR=3, FENCE=7, DOOR_WOOD_CLOSED=18）。
     */
    private static byte[] buildVillageGrid(final int sx, final int sz) {
        final byte[] pt = new byte[sx * sz];
        Arrays.fill(pt, (byte) PathType.WALKABLE.ordinal());
        final int room = 8;
        for (int gx = room; gx < sx - 1; gx += room) {
            for (int z = 1; z < sz - 1; z++) pt[z * sx + gx] = (byte) PathType.BLOCKED.ordinal();
        }
        for (int gz = room; gz < sz - 1; gz += room) {
            for (int x = 1; x < sx - 1; x++) pt[gz * sx + x] = (byte) PathType.BLOCKED.ordinal();
        }
        int r = 0;
        for (int gx = room; gx < sx - 1; gx += room) {
            final int gap = (r % 2 == 0) ? 4 : sz - 5;
            if (gap > 0 && gap < sz) pt[gap * sx + gx] = (byte) PathType.WALKABLE_DOOR.ordinal();
            r++;
        }
        int c = 0;
        for (int gz = room; gz < sz - 1; gz += room) {
            final int gap = (c % 2 == 0) ? 4 : sx - 5;
            if (gap > 0 && gap < sx) pt[gz * sx + gap] = (byte) PathType.WALKABLE_DOOR.ordinal();
            c++;
        }
        for (int z = 4; z < sz - 4; z += 9) {
            for (int x = room + 2; x < Math.min(sx - 2, room + 4); x++) {
                pt[z * sx + x] = (byte) PathType.FENCE.ordinal();
            }
        }
        if (sx > 24 && sz > 24) pt[(sz / 2) * sx + (sx / 2)] = (byte) PathType.DOOR_WOOD_CLOSED.ordinal();
        pt[1 * sx + 0] = (byte) PathType.WALKABLE.ordinal();
        pt[(sz - 2) * sx + (sx - 1)] = (byte) PathType.WALKABLE.ordinal();
        return pt;
    }

    /** 与 native {@code find_accepted_node}/{@code resolve_step_node} 对齐的网格求值器（单层）。 */
    private static final class Step {
        int y;
        int type;
        float malus;
        boolean closed;
    }

    private static final class VillageNodeEvaluator extends NodeEvaluator {
        private static final int[] DIR_X = {0, 1, 0, -1};
        private static final int[] DIR_Z = {-1, 0, 1, 0};
        private static final int K_BLOCKED = 0, K_OPEN = 1, K_WALKABLE = 2, K_WALKABLE_DOOR = 3,
                K_TRAPDOOR = 4, K_POWDER_SNOW = 5, K_FENCE = 7, K_WATER = 9, K_UNPASSABLE_RAIL = 12,
                K_STICKY_HONEY = 22, K_DOOR_WOOD_CLOSED = 18, K_DOOR_IRON_CLOSED = 19;
        private final byte[] pathTypes;
        private final int sx, sz;
        private final float[] malus;
        private final boolean canWalkOverFences, mobsIgnoreRails, canFloat;
        private final float bbWidth, maxUpStep;

        VillageNodeEvaluator(final byte[] pathTypes, final int sx, final int sz, final float[] malus,
                             final boolean canWalkOverFences, final boolean mobsIgnoreRails,
                             final boolean canFloat, final float bbWidth, final float maxUpStep) {
            this.pathTypes = pathTypes;
            this.sx = sx;
            this.sz = sz;
            this.malus = malus;
            this.canWalkOverFences = canWalkOverFences;
            this.mobsIgnoreRails = mobsIgnoreRails;
            this.canFloat = canFloat;
            this.bbWidth = bbWidth;
            this.maxUpStep = maxUpStep;
        }

        int pathTypeAt(final int x, final int y, final int z) {
            if (x < 0 || y < 0 || z < 0 || x >= sx || y >= 1 || z >= sz) return K_BLOCKED;
            return pathTypes[z * sx + x] & 0xFF;
        }

        float malusAt(final int type) {
            return (type < 0 || type >= malus.length) ? -1.0F : malus[type];
        }

        private boolean partialCollision(final int type) {
            return type == K_FENCE || type == K_DOOR_WOOD_CLOSED || type == K_DOOR_IRON_CLOSED;
        }

        @Override
        public Node getStart() { return this.getNode(0, 0, 1); }

        public Node startNode(final int x, final int y, final int z) { return this.getNode(x, y, z); }

        @Override
        public Target getTarget(final double x, final double y, final double z) {
            return new Target((int) x, (int) y, (int) z);
        }

        @Override
        public PathType getPathTypeOfMob(final PathfindingContext context, final int x, final int y, final int z, final Mob mob) {
            return PathType.WALKABLE;
        }

        @Override
        public PathType getPathType(final PathfindingContext context, final int x, final int y, final int z) {
            return PathType.WALKABLE;
        }

        private boolean findAcceptedNode(final int x, final int y, final int z, final int vdl,
                                         final float nodeFloorLevel, final int originType, final Step out) {
            if (x < 0 || y < 0 || z < 0 || x >= sx || y >= 1 || z >= sz) return false;
            final int type = pathTypeAt(x, y, z);
            final float m = malusAt(type);
            final boolean haveNode = m >= 0.0F;
            if (partialCollision(originType) && haveNode) return false;
            if (type == K_WALKABLE) {
                out.y = y; out.type = type; out.malus = m; out.closed = false;
                return haveNode;
            }
            if ((!haveNode || m < 0.0F) && vdl > 0
                    && (type != K_FENCE || canWalkOverFences)
                    && (mobsIgnoreRails || type != K_UNPASSABLE_RAIL)
                    && type != K_TRAPDOOR && type != K_POWDER_SNOW) {
                return findAcceptedNode(x, y + 1, z, vdl - 1, nodeFloorLevel, originType, out);
            }
            if (type == K_WATER && !canFloat) return haveNode;
            if (type == K_OPEN) return haveNode;
            if (partialCollision(type) && !haveNode) {
                out.y = y; out.type = type; out.malus = malusAt(type); out.closed = true;
                return true;
            }
            out.y = y; out.type = type; out.malus = m; out.closed = false;
            return haveNode;
        }

        private boolean resolveStep(final int ox, final int oy, final int oz, final int nx, final int nz, final Step out) {
            final int originType = pathTypeAt(ox, oy, oz);
            final int headType = pathTypeAt(ox, oy + 1, oz);
            int vdl = 0;
            if (malusAt(headType) >= 0.0F && originType != K_STICKY_HONEY) {
                vdl = (int) Math.floor(Math.max(1.0F, maxUpStep));
            }
            return findAcceptedNode(nx, oy, nz, vdl, 0.0F, originType, out);
        }

        @Override
        public int getNeighbors(final Node[] output, final Node node) {
            int count = 0;
            final Node[] cardinals = new Node[4];
            final Step[] csteps = new Step[4];
            for (int d = 0; d < 4; d++) {
                final int nx = node.x + DIR_X[d];
                final int nz = node.z + DIR_Z[d];
                final Step s = new Step();
                if (resolveStep(node.x, node.y, node.z, nx, nz, s)) {
                    final Node nn = this.getNode(nx, s.y, nz);
                    nn.type = PathType.values()[s.type & 0xFF];
                    nn.costMalus = s.malus;
                    if (s.closed) nn.closed = true;
                    cardinals[d] = nn;
                    csteps[d] = s;
                } else {
                    cardinals[d] = null;
                    csteps[d] = null;
                }
            }
            for (int d = 0; d < 4; d++) {
                final Node nn = cardinals[d];
                if (nn == null) continue;
                if (nn.closed) continue;
                if (!(nn.costMalus >= 0.0F || node.costMalus < 0.0F)) continue;
                output[count++] = nn;
            }
            final PathType walkableDoor = PathType.values()[K_WALKABLE_DOOR];
            final PathType fence = PathType.values()[K_FENCE];
            for (int d = 0; d < 4; d++) {
                final Node a = cardinals[d];
                final Node b = cardinals[(d + 1) & 3];
                if (a == null || b == null) continue;
                if (a.y > node.y || b.y > node.y) continue;
                if (a.type == walkableDoor || b.type == walkableDoor) continue;
                final boolean fenceGap = a.type == fence && b.type == fence && bbWidth < 0.5F;
                if (!((b.y < node.y || b.costMalus >= 0.0F || fenceGap)
                        && (a.y < node.y || a.costMalus >= 0.0F || fenceGap))) continue;
                final int nx = node.x + DIR_X[d] + DIR_X[(d + 1) & 3];
                final int nz = node.z + DIR_Z[d] + DIR_Z[(d + 1) & 3];
                final Step s = new Step();
                if (!resolveStep(node.x, node.y, node.z, nx, nz, s)) continue;
                final Node dn = this.getNode(nx, s.y, nz);
                dn.type = PathType.values()[s.type & 0xFF];
                dn.costMalus = s.malus;
                if (dn.closed) continue;
                if (dn.type == walkableDoor) continue;
                if (!(dn.costMalus >= 0.0F)) continue;
                output[count++] = dn;
            }
            return count;
        }
    }

    private static int vanillaNodeCountVillage(final VillageNodeEvaluator evaluator, final int sx, final int sz,
                                               final int startX, final int startZ, final int targetX, final int targetZ,
                                               final int maxNodes, final float maxRange) throws Exception {
        final Path path = vanillaPathVillage(evaluator, startX, startZ, targetX, targetZ, maxNodes, maxRange);
        return path == null ? -1 : path.getNodeCount();
    }

    private static Path vanillaPathVillage(final VillageNodeEvaluator evaluator,
                                           final int startX, final int startZ,
                                           final int targetX, final int targetZ,
                                           final int maxNodes, final float maxRange) throws Exception {
        final PathFinder finder = new PathFinder(evaluator, maxNodes);
        final Node start = evaluator.startNode(startX, 0, startZ);
        final int st = evaluator.pathTypeAt(startX, 0, startZ) & 0xFF;
        start.type = PathType.values()[st];
        start.costMalus = evaluator.malusAt(st);
        final Target target = new Target(targetX, 0, targetZ);
        return (Path) vanillaFindPath.invoke(finder, start,
                List.of(Map.entry(target, new BlockPos(targetX, 0, targetZ))), maxRange, 0, 1.0F);
    }

    private static Measurement measureVillage(final Impl impl, final NativeInputs ni,
                                               final int sx, final int sz, final int startX, final int startZ,
                                               final int targetX, final int targetZ, final int maxNodes,
                                               final float maxRange, final int iterations) throws Exception {
        if (impl == Impl.NATIVE) {
            NativePathfinder.setCardinalFastPathEnabledForBenchmark(false);
        } else if (impl == Impl.NATIVE_FASTPATH) {
            NativePathfinder.setCardinalFastPathEnabledForBenchmark(true);
        }
        publishedPathTypes = ni.pathTypes;
        publishedOut = ni.out;
        final Target target = new Target(targetX, 0, targetZ);
        final List<Map.Entry<Target, BlockPos>> targets = List.of(Map.entry(target, new BlockPos(targetX, 0, targetZ)));
        final float[] malus = NativePathfinder.pathfindingMalusFor(PathType.values());
        final long thread = Thread.currentThread().threadId();
        final long beforeBytes = ALLOC.getThreadAllocatedBytes(thread);
        final long beforeTime = System.nanoTime();
        long acc = 0;
        for (int i = 0; i < iterations; i++) {
            if (impl == Impl.VANILLA) {
                // Fresh evaluator per iteration: NodeEvaluator caches nodes in a Map that must
                // not survive across searches, otherwise vanilla timing is understated. Mirror runConfig.
                final VillageNodeEvaluator evaluator = new VillageNodeEvaluator(
                        ni.pathTypes, sx, sz, malus, false, false, false, 0.6F, 1.0F);
                final PathFinder finder = new PathFinder(evaluator, maxNodes);
                final Node start = evaluator.startNode(startX, 0, startZ);
                final int st = evaluator.pathTypeAt(startX, 0, startZ) & 0xFF;
                start.type = PathType.values()[st];
                start.costMalus = evaluator.malusAt(st);
                final Path path = (Path) vanillaFindPath.invoke(finder, start, targets, maxRange, 0, 1.0F);
                acc += path == null ? 0 : path.getNodeCount();
            } else {
                acc += callNative(impl, ni, sx, sz, startX, startZ, targetX, targetZ, maxNodes, maxRange);
            }
        }
        final long nanos = System.nanoTime() - beforeTime;
        final long bytes = ALLOC.getThreadAllocatedBytes(thread) - beforeBytes;
        sink = acc;
        return new Measurement(nanos, bytes);
    }

    private static void runVillageConfig(final byte[] inPathTypes, final float[] inFloorLevels, final int sizeX, final int sizeZ,
                                         final int startX, final int startZ, final int targetX, final int targetZ,
                                         final int warmup, final int samples, final boolean reverse, final int iterations) throws Exception {
        final boolean shortPath = "short".equals(PATH_LENGTH);
        final boolean useSnapshot = "snapshot".equals(PATH_ENTRY);
        final byte[] pathTypes;
        final float[] floorLevels;
        final int sX, sZ, tX, tZ;
        if (shortPath) {
            pathTypes = buildOpenPathTypes(sizeX, sizeZ);
            floorLevels = new float[pathTypes.length];
            sX = 2; sZ = 2; tX = 5; tZ = 2;
        } else {
            pathTypes = inPathTypes;
            floorLevels = inFloorLevels;
            sX = startX; sZ = startZ; tX = targetX; tZ = targetZ;
        }
        final int maxNodes = sizeX * sizeZ;
        final float maxRange = 100000.0F;
        final NativeInputs ni = new NativeInputs(pathTypes, floorLevels, sizeX, sizeZ, maxNodes, useSnapshot);
        final float[] malus = NativePathfinder.pathfindingMalusFor(PathType.values());
        final VillageNodeEvaluator evaluator = new VillageNodeEvaluator(
                pathTypes, sizeX, sizeZ, malus,
                false, false, false, 0.6F, 1.0F);

        final Path vanillaPath = vanillaPathVillage(evaluator, sX, sZ, tX, tZ, maxNodes, maxRange);
        final int vanillaNodes = vanillaPath == null ? -1 : vanillaPath.getNodeCount();
        final int nativeBaselineNodes = nativeLength(ni, sizeX, sizeZ, sX, sZ, tX, tZ, maxNodes, maxRange, false);
        final int nativeFastPathNodes = nativeLength(ni, sizeX, sizeZ, sX, sZ, tX, tZ, maxNodes, maxRange, true);
        int nativeSnapshotNodes = -1;
        int nativeSnapshotFusedNodes = -1;
        int nativeMirrorNodes = -1;
        if (useSnapshot) {
            final NativePathfinder.PathResult oldOrder = callNativeResult(Impl.NATIVE_SNAPSHOT_LEGACY,
                    ni, sizeX, sizeZ, sX, sZ, tX, tZ, maxNodes, maxRange);
            final NativePathfinder.PathResult fused = callNativeResult(Impl.NATIVE_SNAPSHOT_FUSED,
                    ni, sizeX, sizeZ, sX, sZ, tX, tZ, maxNodes, maxRange);
            final NativePathfinder.PathResult mirror = callNativeResult(Impl.NATIVE_MIRROR,
                    ni, sizeX, sizeZ, sX, sZ, tX, tZ, maxNodes, maxRange);
            nativeSnapshotNodes = reachedLength(oldOrder);
            nativeSnapshotFusedNodes = reachedLength(fused);
            nativeMirrorNodes = reachedLength(mirror);
            assertPathParity("village snapshot-old-order", vanillaPath, oldOrder);
            assertPathParity("village snapshot-fused", vanillaPath, fused);
            assertPathParity("village native-mirror", vanillaPath, mirror);
            assertNativePathParity("village snapshot old/fused", oldOrder, fused);
            assertNativePathParity("village snapshot fused/mirror", fused, mirror);
        }
        if (vanillaNodes <= 0 || vanillaNodes != nativeBaselineNodes || vanillaNodes != nativeFastPathNodes
                || (useSnapshot && (vanillaNodes != nativeSnapshotNodes
                        || vanillaNodes != nativeSnapshotFusedNodes || vanillaNodes != nativeMirrorNodes))) {
            throw new AssertionError("village path parity failed: vanilla=" + vanillaNodes
                    + " native=" + nativeBaselineNodes + " native-fastpath=" + nativeFastPathNodes
                    + (useSnapshot ? " snapshot-old-order=" + nativeSnapshotNodes
                            + " snapshot-fused=" + nativeSnapshotFusedNodes + " native-mirror=" + nativeMirrorNodes : ""));
        }

        final Impl[] impls = useSnapshot
                ? new Impl[]{Impl.VANILLA, Impl.NATIVE_SNAPSHOT_LEGACY, Impl.NATIVE_SNAPSHOT_FUSED, Impl.NATIVE_MIRROR}
                : new Impl[]{Impl.VANILLA, Impl.NATIVE, Impl.NATIVE_FASTPATH};
        final double[][] nanos = new double[Impl.values().length][samples];
        final double[][] bytes = new double[Impl.values().length][samples];
        final double[] hitRates = new double[Impl.values().length];
        for (int i = 0; i < warmup; i++) {
            for (final Impl impl : impls) {
                measureVillage(impl, ni, sizeX, sizeZ, sX, sZ, tX, tZ, maxNodes, maxRange, iterations);
            }
        }
        for (int i = 0; i < samples; i++) {
            for (int order = 0; order < impls.length; order++) {
                final int idx = Math.floorMod(i + (reverse ? -order : order), impls.length);
                final Impl impl = impls[idx];
                final Measurement m = measureVillage(impl, ni, sizeX, sizeZ, sX, sZ, tX, tZ, maxNodes, maxRange, iterations);
                nanos[impl.ordinal()][i] = (double) m.nanos / iterations;
                bytes[impl.ordinal()][i] = (double) m.bytes / iterations;
            }
        }
        validateMeasurements(impls, nanos, bytes, samples);
        final FastPathDiagnostics diagnostics = measureFastPathHitRate(
                ni, sizeX, sizeZ, sX, sZ, tX, tZ, maxNodes, maxRange, iterations);
        for (final Impl impl : impls) {
            if (impl == Impl.NATIVE_FASTPATH) hitRates[impl.ordinal()] = diagnostics.hitRate;
        }
        printRawSamples("village", sizeX, sizeZ, impls, nanos, bytes, samples);
        System.out.printf("PATHFIND_PARITY world=village entry=%s length=%s size=%dx%d nodes=%d%n", PATH_ENTRY, PATH_LENGTH, sizeX, sizeZ, vanillaNodes);
        System.out.printf("PATHFIND_FASTPATH world=village entry=%s length=%s size=%dx%d cardinal-candidates=%d cardinal-hits=%d out-of-region=%d cardinal-hit-rate=%.2f%% top-misses=%s%n",
                PATH_ENTRY, PATH_LENGTH, sizeX, sizeZ, diagnostics.candidates, diagnostics.hits, diagnostics.outOfRegion,
                diagnostics.hitRate * 100.0, diagnostics.topMisses);
        for (final Impl impl : impls) {
            final double nsPerSearch = median(nanos[impl.ordinal()]);
            final double bytesPerSearch = median(bytes[impl.ordinal()]);
            System.out.printf("PATHFIND_RESULT world=village entry=%s length=%s size=%dx%d impl=%s pathNodes=%d ns-per-search=%.1f ns-per-node=%.3f bytes-per-search=%.1f fastpath-hit-rate=%.2f%%%n",
                    PATH_ENTRY, PATH_LENGTH, sizeX, sizeZ, impl.label, vanillaNodes, nsPerSearch, nsPerSearch / vanillaNodes,
                    bytesPerSearch, hitRates[impl.ordinal()] * 100.0);
        }
    }
}
