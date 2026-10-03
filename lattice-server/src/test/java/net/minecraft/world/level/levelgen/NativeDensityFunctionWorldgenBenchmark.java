package net.minecraft.world.level.levelgen;

import com.latticemc.lattice.nativelib.LatticeNative;
import com.latticemc.lattice.nativelib.NativeDensityFunction;
import com.latticemc.lattice.nativelib.WorldgenProfiler;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.blending.Blender;

/**
 * Measures the existing Overworld {@link NoiseChunk} Java-to-native density
 * wrappers. This benchmark never changes a production gate.
 */
public final class NativeDensityFunctionWorldgenBenchmark {
    private static final long DEFAULT_SEED = 0x4C415454494345L;
    private static final int[] DEFAULT_WORK_ITEMS = {1, 2, 4, 6, 8, 16, 32};
    private static final int[] DEFAULT_WORKERS = {1, 2, 4, 6, 8};
    private static final Field INTERPOLATORS = field("interpolatorArray");
    private static final Field CELL_CACHES = field("cellCaches");

    private static HolderLookup.Provider registries;
    private static NoiseGeneratorSettings settings;

    private NativeDensityFunctionWorldgenBenchmark() {
    }

    public static void main(final String[] args) throws Exception {
        final Config config = Config.parse(args);
        Locale.setDefault(Locale.ROOT);
        if (config.profiling) System.setProperty("lattice.worldgenProfilerAvailable", "true");
        bootstrap();

        System.out.printf("Native DensityFunction Overworld wrapper benchmark%n");
        System.out.printf("cpu=%s seed=%d warmup=%d samples=%d workItems=%s workers=%s profiling=%s%n",
            LatticeNative.cpuSummary(), config.seed, config.warmupRounds, config.sampleCount,
            Arrays.toString(config.workItems), Arrays.toString(config.workers), config.profiling);
        printCompilerCoverage(config.seed);
        if (config.coverageOnly) return;
        System.out.println("lifecycle cold=fresh worker+RandomState per sample; hot=reused worker+RandomState per mode.");
        System.out.println("Each work item owns its NoiseChunk and outputs; eligible grid programs/cache are reused sequentially within one worker and cleared per call; workers never share mutable native cache state.");
        System.out.println("path=grid uses preliminary surface grid; slice initializes one complete Overworld slice; column walks one X column of CacheAllInCell state.");
        System.out.println("executionStats: full wrapper coverage means the JNI batch completed; it does not imply every tree node used AVX2.");
        System.out.printf("%-6s %-6s %-4s %-4s %-5s %-6s %-8s %-8s %-12s %-12s %-12s %-12s %-10s %-10s %-10s %-9s%n",
            "phase", "path", "N", "P", "roots", "mode", "p50-ns", "p95-ns", "wall-ns/work", "worker-ns/work",
            "points/work", "throughput/s", "speed-p50", "speed-p95", "coverage", "parity");

        final Shape shape = Shape.verify(config.seed);
        if (config.profiling) printConstructionIdentity(config.seed, shape);
        System.out.printf("shape cellWidth=%d cellHeight=%d cellCountXZ=%d cellCountY=%d yRows=%d zRows=%d roots=%d cacheRoots=%d%n",
            shape.cellWidth, shape.cellHeight, shape.cellCountXZ, shape.cellCountY, shape.yRows, shape.zRows,
            shape.interpolatorRoots, shape.cacheRoots);
        verifyParity(config, shape);
        verifyConstructionReuse(config.seed, shape);
        if (config.verifyOnly) {
            System.out.println("PARITY_RESULT modes=eager,lazy status=passed scope=observed-native-calls; see per-path coverage");
            return;
        }

        for (final Path path : Path.values()) {
            for (final int workers : config.workers) {
                for (final int workItems : config.workItems) {
                    if (workItems < workers) continue;
                    runCase("cold", path, workItems, workers, 0, config.coldSamples, config.seed, shape);
                    runCase("hot", path, workItems, workers, config.warmupRounds, config.sampleCount, config.seed, shape);
                    if (config.profiling) {
                        for (Mode mode : new Mode[] {Mode.JAVA, Mode.NATIVE_EAGER}) {
                            profile(path, mode, workItems, workers, config, shape);
                        }
                    }
                }
            }
        }
    }

    private static void printCompilerCoverage(final long seed) throws Exception {
        final RandomState random = RandomState.create(registries, NoiseGeneratorSettings.OVERWORLD, seed);
        final NoiseSettings noise = settings.noiseSettings();
        final Shape shape = new Shape(noise.getCellWidth(), noise.getCellHeight(), 16 / noise.getCellWidth(),
            noise.height() / noise.getCellHeight(), 0, 0, 0, 0);
        final NoiseChunk chunk = newChunk(random, 0, shape);
        final NoiseRouter router = random.router().mapAll(chunk::wrap);
        System.out.println("compiler source=vanilla-overworld wrapped=NoiseChunk blender=empty; error: -1=java-arena, 0=none, 1=root, 2=unsupported, 3=operand, 4=program-too-large");
        int roots = 0;
        int compiled = 0;
        long instructions = 0;
        long opaque = 0;
        long cseHits = 0;
        long deadInstructions = 0;
        for (final var component : NoiseRouter.class.getRecordComponents()) {
            final DensityFunction function = (DensityFunction) component.getAccessor().invoke(router);
            final var stats = NativeDensityFunction.compilerStats(function);
            roots++;
            if (stats.compiled()) compiled++;
            instructions += stats.instructions();
            opaque += stats.opaqueOps();
            cseHits += stats.cseHits();
            deadInstructions += stats.deadInstructions();
            System.out.printf("COMPILER root=%s arena-built=%s compiled=%s error=%d error-node=%d instructions=%d opaque=%d opaque-ratio=%.6f cse=%d dead=%d%n",
                component.getName(), stats.arenaBuilt(), stats.compiled(), stats.error(), stats.errorNode(),
                stats.instructions(), stats.opaqueOps(), stats.opaqueRatio(), stats.cseHits(), stats.deadInstructions());
        }
        final double coverage = roots == 0 ? 0.0 : (double) compiled / roots;
        final double opaqueRatio = instructions == 0 ? Double.NaN : (double) opaque / instructions;
        System.out.printf("COMPILER_SUMMARY roots=%d compiled=%d compile-ratio=%.6f instructions=%d opaque=%d opaque-ratio=%.6f target-met=%s cse=%d dead=%d%n",
            roots, compiled, coverage, instructions, opaque, opaqueRatio, coverage >= 0.9 && opaqueRatio <= 0.3, cseHits, deadInstructions);
    }

    private static void bootstrap() {
        System.setProperty("lattice.nativeDensityFunction", "true");
        System.setProperty("lattice.nativeDensityFunctionGrid", "true");
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        LatticeNative.load();
        if (!LatticeNative.isLoaded()) {
            throw new IllegalStateException("Native library unavailable: " + LatticeNative.failureReason());
        }
        registries = VanillaRegistries.createLookup();
        settings = registries.lookupOrThrow(Registries.NOISE_SETTINGS)
            .getOrThrow(NoiseGeneratorSettings.OVERWORLD).value();
    }

    private static void verifyParity(final Config config, final Shape shape) throws Exception {
        for (final boolean lazyMixedRange : new boolean[] {false, true}) {
            configureNative(true, true, lazyMixedRange, false, true);
            NativeDensityFunction.setIntOption("parityInterval", 1);
            for (final Path path : Path.values()) {
                NativeDensityFunction.resetStats();
                NativeDensityFunction.beginExecutionStatsSample();
                final NativeDensityFunction.ExecutionStatsSnapshot execution;
                try {
                    execute(path, config.seed, 0, shape);
                } finally {
                    execution = NativeDensityFunction.finishExecutionStatsSample();
                }
                final String status = NativeDensityFunction.status();
                if (!status.contains("parity={checks=") || status.contains("parity={checks=0,") || !status.contains("failures=0")) {
                    throw new IllegalStateException("Native worldgen parity failed or was unavailable for " + path + ": " + status);
                }
                System.out.printf("PARITY mode=%s path=%s coverage=%s status=%s%n",
                    lazyMixedRange ? "lazy" : "eager", path, coverage(path, status), status);
                System.out.printf("PROGRAM mode=%s path=%s %s%n",
                    lazyMixedRange ? "lazy" : "eager", path, execution.benchmarkFields());
                if (execution.compiledPoints() <= 0
                        || execution.compiledNoiseBatches() <= 0 || execution.compiledNoisePoints() <= 0
                        || (path == Path.GRID && execution.compiledGridCalls() <= 0)
                        || (path == Path.SLICE && (execution.compiledColumnCalls() <= 0
                            || execution.compiledColumnCalls() != execution.columnCalls()))
                        || (path == Path.COLUMN && (execution.compiledColumnCalls() <= 0
                            || execution.compiledColumnCalls() != execution.columnCalls()))) {
                    throw new IllegalStateException("Compiled batch path did not cover " + path + ": " + execution.benchmarkFields());
                }
            }
        }
        configureNative(false, false, false, false, false);
    }

    // 使用真实 Overworld 构造路径；resetStats 不改变配置 epoch，不把冷编译误算成稳态。
    private static void verifyConstructionReuse(long seed, Shape shape) throws Exception {
        boolean cacheEnabled = Boolean.parseBoolean(System.getProperty("lattice.nativeDensityFunctionGridCompileCache", "true"));
        for (long testSeed : new long[] {seed, seed + 1}) {
            configureNative(true, true, false, false, false);
            NativeDensityFunction.setIntOption("parityInterval", 1);
            RandomState random = RandomState.create(registries, NoiseGeneratorSettings.OVERWORLD, testSeed);
            newChunk(random, 0, shape);
            NativeDensityFunction.resetStats();
            for (int i = 1; i <= 16; i++) newChunk(random, i, shape);
            long attempts = nativeCounter("COMPILE_ATTEMPTS");
            long grids = nativeCounter("GRID_SUCCESS");
            String status = NativeDensityFunction.status();
            if (grids != 144 || (cacheEnabled && attempts != 0) || !status.contains("failures=0")) {
                throw new IllegalStateException("Construction reuse regression: " + status);
            }
            System.out.printf("CONSTRUCTION_REGRESSION seed=%d cache=%s chunks=16 grid=%d compile-attempts=%d status=%s%n",
                testSeed, cacheEnabled, grids, attempts, status);
        }
        configureNative(false, false, false, false, false);
    }

    private static void runCase(final String phase, final Path path, final int workItems, final int workers,
                                final int warmupRounds, final int samples, final long seed, final Shape shape) throws Exception {
        final Stats[] results = new Stats[Mode.values().length];
        final NativeDensityFunction.ExecutionStatsSnapshot[] diagnostics =
            new NativeDensityFunction.ExecutionStatsSnapshot[Mode.values().length];
        // Rotate complete mode blocks across cases so Java/eager/lazy do not
        // always inherit the same warm-machine position.
        final int firstMode = Math.floorMod(path.ordinal() + workItems + workers + phase.hashCode(), Mode.values().length);
        for (int offset = 0; offset < Mode.values().length; offset++) {
            final Mode mode = Mode.values()[(firstMode + offset) % Mode.values().length];
            results[mode.ordinal()] = measure("cold".equals(phase), path, mode, workItems, workers,
                warmupRounds, samples, seed, shape);
            if (mode.nativeEnabled) {
                diagnostics[mode.ordinal()] = collectExecutionStats(path, mode, workItems, workers, warmupRounds, seed, shape);
            }
        }
        final Stats javaStats = results[Mode.JAVA.ordinal()];
        for (final Mode mode : Mode.values()) {
            final Stats stats = results[mode.ordinal()];
            final double p50Speedup = mode.nativeEnabled ? (double)javaStats.p50Wall / stats.p50Wall : 1.0D;
            final double p95Speedup = mode.nativeEnabled ? (double)javaStats.p95Wall / stats.p95Wall : 1.0D;
            print(phase, path, workItems, workers, shape.roots(path), mode.label, stats, p50Speedup, p95Speedup,
                stats.coverage, mode.nativeEnabled ? "pass" : "n/a");
            if (mode.nativeEnabled) {
                System.out.printf(Locale.ROOT,
                    "EXECUTION phase=%s path=%s N=%d P=%d mode=%s samples=1 timed-samples=%d warmup=%d pass=untimed-diagnostics %s%n",
                    phase, path.name().toLowerCase(Locale.ROOT), workItems, workers, mode.label, samples, warmupRounds,
                    diagnostics[mode.ordinal()].benchmarkFields());
            }
        }
    }

    private static void profile(final Path path, final Mode mode, final int workItems, final int workers,
                                final Config config, final Shape shape) throws Exception {
        configureNative(mode.nativeEnabled, false, mode.lazyMixedRange, mode.segmentedMixedRange, false);
        // setOption 会使编译缓存失效；必须在预热之前开启，不能在采样边界破坏热状态。
        NativeDensityFunction.setOption("profiling", mode.nativeEnabled);
        try (WorkerPool pool = new WorkerPool(workers, config.seed)) {
            for (int i = 0; i < config.warmupRounds; ++i) runParallel(pool, path, workItems, shape);
            WorldgenProfiler.reset();
            WorldgenProfiler.setEnabled(true);
            WorldgenProfiler.setHotLoopsEnabled(true);
            NativeDensityFunction.resetStats();
            long workerNanos = 0;
            try {
                for (int i = 0; i < config.sampleCount; ++i) workerNanos += runParallel(pool, path, workItems, shape).workerNanos;
            } finally {
                WorldgenProfiler.setEnabled(false);
                WorldgenProfiler.setHotLoopsEnabled(false);
                NativeDensityFunction.setOption("profiling", false);
            }
            System.out.printf("PROFILE_TOTAL path=%s N=%d P=%d mode=%s samples=%d worker-ns=%d coverage=%s status=%s%n",
                    path, workItems, workers, mode.label, config.sampleCount, workerNanos,
                    mode.nativeEnabled ? coverage(path, NativeDensityFunction.status()) : "java", NativeDensityFunction.status());
            System.out.printf("PROFILE_COMPILE path=%s mode=%s chunks=%d compile-attempts=%d compile-ns=%d compile-ns-per-chunk=%.3f grid-ns=%d%n",
                    path, mode.label, workItems * config.sampleCount, nativeCounter("COMPILE_ATTEMPTS"),
                    nativeCounter("COMPILE_NANOS"), (double) nativeCounter("COMPILE_NANOS") / (workItems * config.sampleCount),
                    nativeCounter("GRID_NANOS"));
            for (var probe : WorldgenProfiler.snapshot()) {
                if (probe.count() == 0) continue;
                System.out.printf("PROFILE path=%s N=%d P=%d mode=%s probe=%s calls=%d ns=%d inclusive-worker-share=%.6f%n",
                        path, workItems, workers, mode.label, probe.name(), probe.count(), probe.nanos(),
                        workerNanos == 0 ? 0.0 : (double) probe.nanos() / workerNanos);
            }
        }
    }

    private static long nativeCounter(String name) throws ReflectiveOperationException {
        Field counter = NativeDensityFunction.class.getDeclaredField(name);
        counter.setAccessible(true);
        return ((java.util.concurrent.atomic.LongAdder) counter.get(null)).sum();
    }

    private static void printConstructionIdentity(long seed, Shape shape) throws ReflectiveOperationException {
        configureNative(true, false, false, false, false);
        RandomState random = RandomState.create(registries, NoiseGeneratorSettings.OVERWORLD, seed);
        NoiseChunk first = newChunk(random, 0, shape), second = newChunk(random, 1, shape);
        var firstWrapped = (java.util.Map<?, ?>) field("wrapped").get(first);
        var secondWrapped = (java.util.Map<?, ?>) field("wrapped").get(second);
        List<DensityFunction> roots = new ArrayList<>();
        for (Object wrapped : firstWrapped.values()) {
            if (wrapped instanceof NoiseChunk.FlatCache flat) roots.add(flat.wrapped());
        }
        for (Object wrapped : secondWrapped.values()) {
            if (wrapped instanceof NoiseChunk.FlatCache flat) {
                DensityFunction root = flat.wrapped();
                var keyReason = Class.forName("com.latticemc.lattice.nativelib.NativeDensityGridKey")
                        .getDeclaredMethod("rejectionReason", DensityFunction.class);
                keyReason.setAccessible(true);
                System.out.printf("CONSTRUCTION_IDENTITY type=%s identity-match=%s equals-match=%s key=%s%n",
                        root.getClass().getName(), roots.stream().anyMatch(previous -> previous == root), roots.contains(root),
                        keyReason.invoke(null, root));
            }
        }
    }

    private static Stats measure(final boolean freshWorkersPerSample, final Path path, final Mode mode,
                                 final int workItems, final int workers,
                                  final int warmupRounds, final int samples, final long seed, final Shape shape) throws Exception {
        configureNative(mode.nativeEnabled, false, mode.lazyMixedRange, mode.segmentedMixedRange, false);
        final long[] wall = new long[samples];
        final long[] worker = new long[samples];
        String coverage = mode.nativeEnabled ? "unknown" : "java";
        if (freshWorkersPerSample) {
            for (int sample = 0; sample < samples; sample++) {
                try (WorkerPool pool = new WorkerPool(workers, seed)) {
                    final TimedSample timed = timedSample(pool, path, mode, workItems, shape);
                    wall[sample] = timed.result.wallNanos / workItems;
                    worker[sample] = timed.result.workerNanos / workItems;
                    coverage = timed.coverage;
                }
            }
        } else {
            try (WorkerPool pool = new WorkerPool(workers, seed)) {
                for (int round = 0; round < warmupRounds; round++) {
                    runParallel(pool, path, workItems, shape);
                }
                for (int sample = 0; sample < samples; sample++) {
                    final TimedSample timed = timedSample(pool, path, mode, workItems, shape);
                    wall[sample] = timed.result.wallNanos / workItems;
                    worker[sample] = timed.result.workerNanos / workItems;
                    coverage = timed.coverage;
                }
            }
        }
        return new Stats(percentile(wall, 0.50D), percentile(wall, 0.95D), percentile(worker, 0.50D), coverage);
    }

    private static TimedSample timedSample(final WorkerPool pool, final Path path, final Mode mode,
                                           final int workItems, final Shape shape) throws Exception {
        if (mode.nativeEnabled) NativeDensityFunction.resetStats();
        final ParallelResult result = runParallel(pool, path, workItems, shape);
        final String coverage = mode.nativeEnabled ? coverage(path, NativeDensityFunction.status()) : "java";
        return new TimedSample(result, coverage);
    }

    private static NativeDensityFunction.ExecutionStatsSnapshot collectExecutionStats(
        final Path path, final Mode mode, final int workItems, final int workers, final int warmupRounds,
        final long seed, final Shape shape
    ) throws Exception {
        configureNative(true, false, mode.lazyMixedRange, mode.segmentedMixedRange, false);
        NativeDensityFunction.setOption("executionStats", true);
        try (WorkerPool pool = new WorkerPool(workers, seed)) {
            for (int i = 0; i < warmupRounds; ++i) runParallel(pool, path, workItems, shape);
            NativeDensityFunction.resetStats();
            return runParallel(pool, path, workItems, shape, true).executionStats;
        }
    }

    private static ParallelResult runParallel(final WorkerPool pool, final Path path, final int workItems,
                                              final Shape shape) throws Exception {
        return runParallel(pool, path, workItems, shape, false);
    }

    private static ParallelResult runParallel(final WorkerPool pool, final Path path, final int workItems,
                                              final Shape shape, final boolean collectExecutionStats) throws Exception {
        final CountDownLatch ready = new CountDownLatch(Math.min(workItems, pool.workers));
        final CountDownLatch start = new CountDownLatch(1);
        final List<Future<WorkResult>> futures = new ArrayList<>(workItems);
        for (int index = 0; index < workItems; index++) {
            final int workIndex = index;
            futures.add(pool.executor.submit(() -> {
                if (workIndex < pool.workers) ready.countDown();
                start.await();
                if (collectExecutionStats) NativeDensityFunction.beginExecutionStatsSample();
                final long started = System.nanoTime();
                try {
                    execute(path, pool.context.get(), workIndex, shape);
                    return new WorkResult(System.nanoTime() - started,
                        collectExecutionStats ? NativeDensityFunction.finishExecutionStatsSample()
                            : NativeDensityFunction.ExecutionStatsSnapshot.disabled());
                } catch (Exception | Error throwable) {
                    if (collectExecutionStats) NativeDensityFunction.finishExecutionStatsSample();
                    throw throwable;
                }
            }));
        }
        ready.await();
        final long started = System.nanoTime();
        start.countDown();
        long workerNanos = 0L;
        NativeDensityFunction.ExecutionStatsSnapshot executionStats = collectExecutionStats
            ? NativeDensityFunction.ExecutionStatsSnapshot.empty()
            : NativeDensityFunction.ExecutionStatsSnapshot.disabled();
        for (final Future<WorkResult> future : futures) {
            final WorkResult result = future.get();
            workerNanos += result.workerNanos;
            if (collectExecutionStats) executionStats = executionStats.plus(result.executionStats);
        }
        return new ParallelResult(System.nanoTime() - started, workerNanos, executionStats);
    }

    private static void execute(final Path path, final long seed, final int workIndex, final Shape shape) throws Exception {
        execute(path, new WorkerContext(RandomState.create(registries, NoiseGeneratorSettings.OVERWORLD, seed)), workIndex, shape);
    }

    private static void execute(final Path path, final WorkerContext context, final int workIndex, final Shape shape) throws Exception {
        final long creationStart = WorldgenProfiler.start();
        final NoiseChunk chunk = newChunk(context.randomState, workIndex, shape);
        WorldgenProfiler.end("benchmark.newNoiseChunk", creationStart);
        switch (path) {
            case GRID -> chunk.maxPreliminarySurfaceLevel(chunkMinX(workIndex), chunkMinZ(workIndex),
                chunkMinX(workIndex) + 16, chunkMinZ(workIndex) + 16);
            case SLICE -> {
                chunk.initializeForFirstCellX();
                chunk.stopInterpolation();
            }
            case COLUMN -> {
                chunk.initializeForFirstCellX();
                chunk.advanceCellX(0);
                for (int z = 0; z < shape.cellCountXZ; z++) {
                    for (int y = 0; y < shape.cellCountY; y++) chunk.selectCellYZ(y, z);
                }
                chunk.stopInterpolation();
            }
        }
    }

    private static NoiseChunk newChunk(final long seed, final int workIndex, final Shape shape) {
        return newChunk(RandomState.create(registries, NoiseGeneratorSettings.OVERWORLD, seed), workIndex, shape);
    }

    private static NoiseChunk newChunk(final RandomState randomState, final int workIndex, final Shape shape) {
        return new NoiseChunk(shape.cellCountXZ, randomState, chunkMinX(workIndex), chunkMinZ(workIndex),
            settings.noiseSettings(), Beardifier.EMPTY, settings, fluidPicker(), Blender.empty());
    }

    private static Aquifer.FluidPicker fluidPicker() {
        final Aquifer.FluidStatus lava = new Aquifer.FluidStatus(-54, Blocks.LAVA.defaultBlockState());
        final Aquifer.FluidStatus water = new Aquifer.FluidStatus(settings.seaLevel(), settings.defaultFluid());
        final Aquifer.FluidStatus air = new Aquifer.FluidStatus(DimensionType.MIN_Y * 2, Blocks.AIR.defaultBlockState());
        return (x, y, z) -> y < Math.min(-54, settings.seaLevel()) ? lava : (SharedConstants.DEBUG_DISABLE_FLUID_GENERATION ? air : water);
    }

    private static int chunkMinX(final int workIndex) {
        return (25565 + workIndex * 37) << 4;
    }

    private static int chunkMinZ(final int workIndex) {
        return (-25565 + workIndex * 53) << 4;
    }

    private static void configureNative(final boolean enabled, final boolean parity, final boolean lazyMixedRange,
                                        final boolean segmentedMixedRange, final boolean executionStats) {
        final boolean lazy = enabled && lazyMixedRange && !segmentedMixedRange;
        final boolean segmented = enabled && segmentedMixedRange && !lazyMixedRange;
        NativeDensityFunction.setOption("enabled", enabled);
        NativeDensityFunction.setOption("cell", enabled);
        NativeDensityFunction.setOption("directCell", enabled);
        NativeDensityFunction.setOption("directCellColumn", enabled);
        NativeDensityFunction.setOption("shiftedNoise", enabled);
        NativeDensityFunction.setOption("spline", enabled);
        NativeDensityFunction.setOption("multipointSpline", enabled);
        NativeDensityFunction.setOption("climateBatch", enabled);
        NativeDensityFunction.setOption("lazyMixedRange", lazy);
        NativeDensityFunction.setOption("segmentedMixedRange", segmented);
        NativeDensityFunction.setOption("stats", enabled);
        NativeDensityFunction.setOption("executionStats", enabled && executionStats);
        NativeDensityFunction.setOption("parity", parity);
        NativeDensityFunction.setOption("profiling", false);
        WorldgenProfiler.setEnabled(false);
        WorldgenProfiler.setHotLoopsEnabled(false);
    }

    private static String coverage(final Path path, final String status) {
        final String field = switch (path) {
            case GRID -> " grid=";
            case SLICE -> " slice=";
            case COLUMN -> " columnBatch=";
        };
        final int start = status.indexOf(field);
        if (start < 0) return "unknown";
        final int end = status.indexOf(' ', start + 1);
        final String value = status.substring(start + 1, end < 0 ? status.length() : end);
        final int slash = value.indexOf('/');
        if (slash <= 0) return value;
        try {
            final long success = Long.parseLong(value.substring(value.indexOf('=') + 1, slash));
            final long attempts = Long.parseLong(value.substring(slash + 1));
            return attempts > 0L && success == attempts ? "full" : value;
        } catch (NumberFormatException ignored) {
            return value;
        }
    }

    private static void print(final String phase, final Path path, final int workItems, final int workers,
                              final int roots, final String mode, final Stats stats, final double p50Speedup,
                              final double p95Speedup, final String coverage, final String parity) {
        final long points = path.points(roots);
        final double throughput = points * 1_000_000_000.0D / stats.p50Wall;
        System.out.printf(Locale.ROOT,
            "RESULT phase=%s path=%s N=%d P=%d roots=%d mode=%s p50-ns=%d p95-ns=%d wall-ns-per-work=%d worker-ns-per-work=%d points-per-work=%d throughput=%.3f speed-p50=%.3f speed-p95=%.3f coverage=%s parity=%s%n",
            phase, path.name().toLowerCase(Locale.ROOT), workItems, workers, roots, mode,
            stats.p50Wall, stats.p95Wall, stats.p50Wall, stats.p50Worker, points, throughput,
            p50Speedup, p95Speedup, coverage, parity);
    }

    private static long percentile(final long[] values, final double percentile) {
        final long[] sorted = values.clone();
        Arrays.sort(sorted);
        return sorted[Math.max(0, Math.min(sorted.length - 1, (int)Math.ceil(sorted.length * percentile) - 1))];
    }

    private static Field field(final String name) {
        try {
            final Field field = NoiseChunk.class.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private record WorkResult(long workerNanos, NativeDensityFunction.ExecutionStatsSnapshot executionStats) {
    }

    private record ParallelResult(long wallNanos, long workerNanos, NativeDensityFunction.ExecutionStatsSnapshot executionStats) {
    }

    private record TimedSample(ParallelResult result, String coverage) {
    }

    private record WorkerContext(RandomState randomState) {
    }

    private static final class WorkerPool implements AutoCloseable {
        private final int workers;
        private final ExecutorService executor;
        private final ThreadLocal<WorkerContext> context;

        private WorkerPool(final int workers, final long seed) {
            this.workers = workers;
            this.context = ThreadLocal.withInitial(() -> new WorkerContext(
                RandomState.create(registries, NoiseGeneratorSettings.OVERWORLD, seed)
            ));
            this.executor = Executors.newFixedThreadPool(workers, runnable -> {
                final Thread thread = new Thread(runnable, "lattice-density-bench");
                thread.setDaemon(true);
                return thread;
            });
        }

        @Override
        public void close() throws InterruptedException {
            this.executor.shutdown();
            try {
                if (!this.executor.awaitTermination(30L, TimeUnit.SECONDS)) {
                    this.executor.shutdownNow();
                    if (!this.executor.awaitTermination(30L, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Timed out shutting down density benchmark workers");
                    }
                }
            } catch (InterruptedException interrupted) {
                this.executor.shutdownNow();
                throw interrupted;
            }
        }
    }

    private record Stats(long p50Wall, long p95Wall, long p50Worker, String coverage) {
    }

    private enum Mode {
        JAVA("java", false, false, false),
        NATIVE_EAGER("native-eager", true, false, false),
        NATIVE_LAZY("native-lazy", true, true, false),
        NATIVE_SEGMENTED("native-segmented", true, false, true);

        private final String label;
        private final boolean nativeEnabled;
        private final boolean lazyMixedRange;
        private final boolean segmentedMixedRange;

        Mode(String label, boolean nativeEnabled, boolean lazyMixedRange, boolean segmentedMixedRange) {
            this.label = label;
            this.nativeEnabled = nativeEnabled;
            this.lazyMixedRange = lazyMixedRange;
            this.segmentedMixedRange = segmentedMixedRange;
        }
    }

    private enum Path {
        GRID {
            @Override int points(final int roots) { return 25; }
        },
        SLICE {
            @Override int points(final int roots) { return roots * 245; }
        },
        COLUMN {
            @Override int points(final int roots) { return roots * 24_576; }
        };

        abstract int points(int roots);
    }

    private record Shape(int cellWidth, int cellHeight, int cellCountXZ, int cellCountY,
                         int yRows, int zRows, int interpolatorRoots, int cacheRoots) {
        static Shape verify(final long seed) throws IllegalAccessException {
            final NoiseSettings noise = settings.noiseSettings();
            final int cellWidth = noise.getCellWidth();
            final int cellHeight = noise.getCellHeight();
            final int cellCountXZ = 16 / cellWidth;
            final int cellCountY = noise.height() / cellHeight;
            if (cellWidth != 4 || cellHeight != 8 || cellCountXZ != 4 || cellCountY != 48) {
                throw new IllegalStateException("Unexpected Overworld shape: " + cellWidth + '/' + cellHeight + '/' + cellCountXZ + '/' + cellCountY);
            }
            final NoiseChunk chunk = newChunk(seed, 0, new Shape(cellWidth, cellHeight, cellCountXZ, cellCountY,
                cellCountY + 1, cellCountXZ + 1, 0, 0));
            final int interpolatorRoots = ((Object[])INTERPOLATORS.get(chunk)).length;
            final int cacheRoots = ((List<?>)CELL_CACHES.get(chunk)).size();
            return new Shape(cellWidth, cellHeight, cellCountXZ, cellCountY, cellCountY + 1, cellCountXZ + 1,
                interpolatorRoots, cacheRoots);
        }

        int roots(final Path path) {
            return switch (path) {
                case GRID -> 1;
                case SLICE -> this.interpolatorRoots;
                case COLUMN -> this.cacheRoots;
            };
        }
    }

    private record Config(int warmupRounds, int sampleCount, int coldSamples, long seed, int[] workItems, int[] workers,
                          boolean coverageOnly, boolean verifyOnly, boolean profiling) {
        static Config parse(final String[] args) {
            int warmup = 4;
            int samples = 9;
            int coldSamples = 3;
            long seed = DEFAULT_SEED;
            int[] workItems = DEFAULT_WORK_ITEMS;
            int[] workers = DEFAULT_WORKERS;
            boolean coverageOnly = false;
            boolean verifyOnly = false;
            boolean profiling = false;
            for (final String argument : args) {
                if (argument.startsWith("--warmup=")) warmup = positive(argument, "--warmup=");
                else if (argument.startsWith("--samples=")) samples = positive(argument, "--samples=");
                else if (argument.startsWith("--cold-samples=")) coldSamples = positive(argument, "--cold-samples=");
                else if (argument.startsWith("--seed=")) seed = Long.parseLong(argument.substring("--seed=".length()));
                else if (argument.startsWith("--work-items=")) workItems = list(argument, "--work-items=");
                else if (argument.startsWith("--workers=")) workers = list(argument, "--workers=");
                else if (argument.equals("--coverage-only")) coverageOnly = true;
                else if (argument.equals("--verify-only")) verifyOnly = true;
                else if (argument.equals("--profiling")) profiling = true;
                else throw new IllegalArgumentException("Unknown benchmark argument: " + argument);
            }
            return new Config(warmup, samples, coldSamples, seed, workItems, workers, coverageOnly, verifyOnly, profiling);
        }

        private static int positive(final String argument, final String prefix) {
            final int value = Integer.parseInt(argument.substring(prefix.length()));
            if (value <= 0) throw new IllegalArgumentException(prefix + " must be positive");
            return value;
        }

        private static int[] list(final String argument, final String prefix) {
            final String[] values = argument.substring(prefix.length()).split(",");
            final int[] parsed = new int[values.length];
            for (int i = 0; i < values.length; i++) parsed[i] = positive(prefix + values[i], prefix);
            return parsed;
        }
    }
}
