package net.minecraft.world.level.levelgen;

import com.latticemc.lattice.nativelib.LatticeNative;
import com.latticemc.lattice.nativelib.NativeDensityFunction;
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
        bootstrap();

        System.out.printf("Native DensityFunction Overworld wrapper benchmark%n");
        System.out.printf("cpu=%s seed=%d warmup=%d samples=%d workItems=%s workers=%s%n",
            LatticeNative.cpuSummary(), config.seed, config.warmupRounds, config.sampleCount,
            Arrays.toString(config.workItems), Arrays.toString(config.workers));
        System.out.println("lifecycle cold=fresh worker+RandomState per sample; hot=reused worker+RandomState per mode.");
        System.out.println("Each work item owns its NoiseChunk, native cache and output buffers; workers never share mutable worldgen state.");
        System.out.println("path=grid uses preliminary surface grid; slice initializes one complete Overworld slice; column walks one X column of CacheAllInCell state.");
        System.out.println("executionStats: full wrapper coverage means the JNI batch completed; it does not imply every tree node used AVX2.");
        System.out.printf("%-6s %-6s %-4s %-4s %-5s %-6s %-8s %-8s %-12s %-12s %-12s %-12s %-10s %-10s %-10s %-9s%n",
            "phase", "path", "N", "P", "roots", "mode", "p50-ns", "p95-ns", "wall-ns/work", "worker-ns/work",
            "points/work", "throughput/s", "speed-p50", "speed-p95", "coverage", "parity");

        final Shape shape = Shape.verify(config.seed);
        System.out.printf("shape cellWidth=%d cellHeight=%d cellCountXZ=%d cellCountY=%d yRows=%d zRows=%d roots=%d cacheRoots=%d%n",
            shape.cellWidth, shape.cellHeight, shape.cellCountXZ, shape.cellCountY, shape.yRows, shape.zRows,
            shape.interpolatorRoots, shape.cacheRoots);
        verifyParity(config, shape);

        for (final Path path : Path.values()) {
            for (final int workers : config.workers) {
                for (final int workItems : config.workItems) {
                    if (workItems < workers) continue;
                    runCase("cold", path, workItems, workers, 0, config.coldSamples, config.seed, shape);
                    runCase("hot", path, workItems, workers, config.warmupRounds, config.sampleCount, config.seed, shape);
                }
            }
        }
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
            configureNative(true, true, lazyMixedRange, false);
            NativeDensityFunction.setIntOption("parityInterval", 1);
            NativeDensityFunction.resetStats();
            for (final Path path : Path.values()) {
                execute(path, config.seed, 0, shape);
            }
            final String status = NativeDensityFunction.status();
            if (!status.contains("parity={checks=") || !status.contains("failures=0")) {
                throw new IllegalStateException("Native worldgen parity failed or was unavailable: " + status);
            }
        }
        configureNative(false, false, false, false);
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
                diagnostics[mode.ordinal()] = collectExecutionStats(path, mode, workItems, workers, seed, shape);
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
                    "EXECUTION phase=%s path=%s N=%d P=%d mode=%s samples=1 timed-samples=%d pass=untimed-diagnostics %s%n",
                    phase, path.name().toLowerCase(Locale.ROOT), workItems, workers, mode.label, samples,
                    diagnostics[mode.ordinal()].benchmarkFields());
            }
        }
    }

    private static Stats measure(final boolean freshWorkersPerSample, final Path path, final Mode mode,
                                 final int workItems, final int workers,
                                  final int warmupRounds, final int samples, final long seed, final Shape shape) throws Exception {
        configureNative(mode.nativeEnabled, false, mode.lazyMixedRange, false);
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
        final Path path, final Mode mode, final int workItems, final int workers, final long seed, final Shape shape
    ) throws Exception {
        configureNative(true, false, mode.lazyMixedRange, true);
        NativeDensityFunction.resetStats();
        try (WorkerPool pool = new WorkerPool(workers, seed)) {
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
        final NoiseChunk chunk = newChunk(context.randomState, workIndex, shape);
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
                                        final boolean executionStats) {
        NativeDensityFunction.setOption("enabled", enabled);
        NativeDensityFunction.setOption("cell", enabled);
        NativeDensityFunction.setOption("directCell", enabled);
        NativeDensityFunction.setOption("directCellColumn", enabled);
        NativeDensityFunction.setOption("shiftedNoise", enabled);
        NativeDensityFunction.setOption("spline", enabled);
        NativeDensityFunction.setOption("multipointSpline", enabled);
        NativeDensityFunction.setOption("climateBatch", enabled);
        NativeDensityFunction.setOption("lazyMixedRange", enabled && lazyMixedRange);
        NativeDensityFunction.setOption("stats", enabled);
        NativeDensityFunction.setOption("executionStats", enabled && executionStats);
        NativeDensityFunction.setOption("parity", parity);
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
        JAVA("java", false, false),
        NATIVE_EAGER("native-eager", true, false),
        NATIVE_LAZY("native-lazy", true, true);

        private final String label;
        private final boolean nativeEnabled;
        private final boolean lazyMixedRange;

        Mode(String label, boolean nativeEnabled, boolean lazyMixedRange) {
            this.label = label;
            this.nativeEnabled = nativeEnabled;
            this.lazyMixedRange = lazyMixedRange;
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

    private record Config(int warmupRounds, int sampleCount, int coldSamples, long seed, int[] workItems, int[] workers) {
        static Config parse(final String[] args) {
            int warmup = 4;
            int samples = 9;
            int coldSamples = 3;
            long seed = DEFAULT_SEED;
            int[] workItems = DEFAULT_WORK_ITEMS;
            int[] workers = DEFAULT_WORKERS;
            for (final String argument : args) {
                if (argument.startsWith("--warmup=")) warmup = positive(argument, "--warmup=");
                else if (argument.startsWith("--samples=")) samples = positive(argument, "--samples=");
                else if (argument.startsWith("--cold-samples=")) coldSamples = positive(argument, "--cold-samples=");
                else if (argument.startsWith("--seed=")) seed = Long.parseLong(argument.substring("--seed=".length()));
                else if (argument.startsWith("--work-items=")) workItems = list(argument, "--work-items=");
                else if (argument.startsWith("--workers=")) workers = list(argument, "--workers=");
                else throw new IllegalArgumentException("Unknown benchmark argument: " + argument);
            }
            return new Config(warmup, samples, coldSamples, seed, workItems, workers);
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
