package com.latticemc.lattice.nativelib;

import com.latticemc.lattice.mixin.NativeInterpolatedNoiseAccess;
import com.latticemc.lattice.mixin.NativeNormalNoiseAccess;
import java.lang.ref.Cleaner;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.IdentityHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.WeakHashMap;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class NativeDensityFunction {
    private static final Logger LOGGER = LoggerFactory.getLogger("Lattice");
    private static volatile boolean ENABLED = Boolean.parseBoolean(System.getProperty("lattice.nativeDensityFunction", "true"));
    private static volatile boolean CELL_ENABLED = Boolean.parseBoolean(System.getProperty("lattice.nativeDensityFunctionCell", "true"));
    private static volatile boolean DIRECT_CELL_ENABLED = Boolean.parseBoolean(System.getProperty("lattice.nativeDensityFunctionDirectCell", "true"));
    private static volatile boolean SHIFTED_NOISE_ENABLED = Boolean.parseBoolean(System.getProperty("lattice.nativeDensityFunctionShiftedNoise", "true"));
    private static volatile boolean SPLINE_ENABLED = Boolean.parseBoolean(System.getProperty("lattice.nativeDensityFunctionSpline", "true"));
    private static volatile boolean MULTIPOINT_SPLINE_ENABLED = Boolean.parseBoolean(System.getProperty("lattice.nativeDensityFunctionMultipointSpline", "true"));
    private static volatile boolean STATS_ENABLED = Boolean.getBoolean("lattice.nativeDensityFunctionStats");
    private static volatile boolean PROFILING_ENABLED = Boolean.getBoolean("lattice.nativeDensityFunctionProfiling");
    private static volatile boolean PARITY_ENABLED = Boolean.getBoolean("lattice.nativeDensityFunctionParity");
    private static volatile int PARITY_INTERVAL = Integer.getInteger("lattice.nativeDensityFunctionParityInterval", 1024);
    private static final Cleaner CLEANER = Cleaner.create();
    private static final Map<DensityFunction, NativeDensityFunction> CACHE = new WeakHashMap<>();
    private static final Map<DensityFunction, NativeDensityFunction> DIRECT_CACHE = new WeakHashMap<>();
    private static final Map<DensityFunction, Boolean> FAILED_COMPILES = new WeakHashMap<>();
    private static final Map<DensityFunction, Boolean> FAILED_DIRECT_COMPILES = new WeakHashMap<>();
    private static final ConcurrentHashMap<Class<?>, Boolean> BYPASSED_ROOT_CLASSES = new ConcurrentHashMap<>();
    private static final ThreadLocal<LastCompile> LAST_COMPILE = new ThreadLocal<>();
    private static final ThreadLocal<LastCompile> LAST_DIRECT_COMPILE = new ThreadLocal<>();
    private static final ThreadLocal<LastCompile> LAST_CELL_COMPILE = new ThreadLocal<>();
    private static final ThreadLocal<LastCellBypass> LAST_CELL_BYPASS = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> BYPASS_FILL_ALL_DIRECTLY = ThreadLocal.withInitial(() -> Boolean.FALSE);
    private static final Object FAILED_COMPILE_SENTINEL = new Object();
    private static final ThreadLocal<IdentityHashMap<DensityFunction, Object>> THREAD_COMPILE_CACHE = ThreadLocal.withInitial(IdentityHashMap::new);
    private static final ThreadLocal<IdentityHashMap<DensityFunction, Object>> THREAD_DIRECT_COMPILE_CACHE = ThreadLocal.withInitial(IdentityHashMap::new);
    private static final ThreadLocal<Integer> THREAD_COMPILE_CACHE_EPOCH = ThreadLocal.withInitial(() -> -1);
    private static final ThreadLocal<Integer> THREAD_DIRECT_COMPILE_CACHE_EPOCH = ThreadLocal.withInitial(() -> -1);
    private static volatile int COMPILE_CACHE_EPOCH = 0;
    private static final LongAdder COMPILE_ATTEMPTS = new LongAdder();
    private static final LongAdder COMPILE_SUCCESS = new LongAdder();
    private static final LongAdder SLICE_ATTEMPTS = new LongAdder();
    private static final LongAdder SLICE_SUCCESS = new LongAdder();
    private static final LongAdder CELL_ATTEMPTS = new LongAdder();
    private static final LongAdder CELL_SUCCESS = new LongAdder();
    private static final LongAdder CELL_INTERPOLATED = new LongAdder();
    private static final LongAdder CELL_DIRECT_ATTEMPTS = new LongAdder();
    private static final LongAdder CELL_DIRECT_SUCCESS = new LongAdder();
    private static final LongAdder CELL_HIGH_ATTEMPTS = new LongAdder();
    private static final LongAdder CELL_HIGH_SUCCESS = new LongAdder();
    private static final LongAdder CELL_SKIP_DISABLED = new LongAdder();
    private static final LongAdder CELL_SKIP_ROOT_BYPASS = new LongAdder();
    private static final LongAdder CELL_SKIP_CELL_BYPASS = new LongAdder();
    private static final LongAdder CELL_SKIP_COMPILE_NULL = new LongAdder();
    private static final LongAdder CELL_SKIP_OUTPUT_TOO_SMALL = new LongAdder();
    private static final LongAdder COMPILE_NANOS = new LongAdder();
    private static final LongAdder SLICE_NANOS = new LongAdder();
    private static final LongAdder CELL_NANOS = new LongAdder();
    private static final LongAdder COLUMN_NANOS = new LongAdder();
    private static final LongAdder COLUMN_COUNT = new LongAdder();
    private static final LongAdder SYNC_NANOS = new LongAdder();
    private static final LongAdder SYNC_COUNT = new LongAdder();
    private static final LongAdder PARITY_CHECKS = new LongAdder();
    private static final LongAdder PARITY_FAILURES = new LongAdder();
    private static final AtomicLong PARITY_MAX_ERROR_BITS = new AtomicLong(Double.doubleToRawLongBits(0.0));
    private static final AtomicLong PARITY_SAMPLE_COUNTER = new AtomicLong();
    private static final AtomicLong DIRECT_CELL_REJECTS = new AtomicLong();
    private static final ConcurrentHashMap<String, LongAdder> UNSUPPORTED = new ConcurrentHashMap<>();
    private static final int LOG_INTERVAL = 4096;
    private static final AtomicBoolean STATUS_LOGGED = new AtomicBoolean(false);
    private static final AtomicBoolean FIRST_SLICE_LOGGED = new AtomicBoolean(false);
    private static final AtomicBoolean FIRST_CELL_LOGGED = new AtomicBoolean(false);

    private final long handle;
    private final long cacheHandle;
    private final List<InterpolatorBinding> interpolators;
    private final int[] interpolatorSlots;
    private final NativeNoiseInterpolatorAccess[] interpolatorAccesses;
    private final double[][] interpolatorStartSlices;
    private final double[][] interpolatorEndSlices;
    private final double[][] cacheAllInCellValues;
    private final boolean clearsCachePerCell;
    private int preparedHorizontalCellCount = -1;
    private int preparedVerticalCellCount = -1;
    private int syncedCellStartBlockX = Integer.MIN_VALUE;
    private boolean interpolatorColumnsBound;
    @SuppressWarnings("unused")
    private final Cleaner.Cleanable cleanable;

    private NativeDensityFunction(long handle, long cacheHandle, List<InterpolatorBinding> interpolators, double[][] cacheAllInCellValues, boolean clearsCachePerCell) {
        this.handle = handle;
        this.cacheHandle = cacheHandle;
        this.interpolators = List.copyOf(interpolators);
        this.interpolatorSlots = new int[interpolators.size()];
        this.interpolatorAccesses = new NativeNoiseInterpolatorAccess[interpolators.size()];
        this.interpolatorStartSlices = new double[interpolators.size()][];
        this.interpolatorEndSlices = new double[interpolators.size()][];
        for (int i = 0; i < interpolators.size(); i++) {
            InterpolatorBinding binding = interpolators.get(i);
            this.interpolatorSlots[i] = binding.slot();
            this.interpolatorAccesses[i] = binding.function();
        }
        this.cacheAllInCellValues = cacheAllInCellValues;
        this.clearsCachePerCell = clearsCachePerCell;
        this.cleanable = CLEANER.register(this, new Destroy(handle, cacheHandle));
    }

    public static boolean tryFillSlice(double[] values,
                                       DensityFunction function,
                                       double x,
                                       double y0,
                                       double z,
                                       double dy,
                                       int cellX,
                                       int cellZ) {
        logStatusOnce();
        if (!FIRST_SLICE_LOGGED.get() && FIRST_SLICE_LOGGED.compareAndSet(false, true)) {
            LOGGER.info("NativeDensityFunction first slice attempt");
        }
        if (!ENABLED) return false;
        if (bypassRootNative(function)) return false;
        boolean stats = STATS_ENABLED;
        boolean profiling = PROFILING_ENABLED;
        if (stats) SLICE_ATTEMPTS.increment();
        long start = profiling ? System.nanoTime() : 0L;
        NativeDensityFunction compiled = tryCompile(function);
        if (compiled == null) return false;
        try {
            nativeEvaluateYColumn(compiled.handle, compiled.cacheHandle, x, y0, z, dy, cellX, cellZ, values.length, values);
            if (stats) SLICE_SUCCESS.increment();
            if (profiling) SLICE_NANOS.add(System.nanoTime() - start);
            return true;
        } catch (RuntimeException | LinkageError e) {
            LatticeNative.logFallbackOnce("density_function_grid", e.getMessage());
            return false;
        }
    }

    public static boolean tryFillCell(double[] values,
                                      DensityFunction function,
                                      int cellStartBlockX,
                                      int cellStartBlockY,
                                      int cellStartBlockZ,
                                       int cellWidth,
                                       int cellHeight,
                                       int cellCountXZ,
                                       int cellCountY,
                                       int cellX,
                                       int cellZ,
                                       int localCellY,
                                      int localCellZ) {
        return tryFillCell(values, function, cellStartBlockX, cellStartBlockY, cellStartBlockZ, cellWidth, cellHeight, cellCountXZ, cellCountY, cellX, cellZ, localCellY, localCellZ, true);
    }

    public static boolean tryFillCellDirect(double[] values,
                                            DensityFunction function,
                                            int cellStartBlockX,
                                            int cellStartBlockY,
                                            int cellStartBlockZ,
                                            int cellWidth,
                                            int cellHeight,
                                            int cellCountXZ,
                                            int cellCountY,
                                            int cellX,
                                            int cellZ,
                                            int localCellY,
                                            int localCellZ) {
        if (!DIRECT_CELL_ENABLED || !CELL_ENABLED) return false;
        return tryFillCell(values, function, cellStartBlockX, cellStartBlockY, cellStartBlockZ, cellWidth, cellHeight, cellCountXZ, cellCountY, cellX, cellZ, localCellY, localCellZ, false);
    }

    public static boolean shouldTryFillCellDirect() {
        return ENABLED && CELL_ENABLED && DIRECT_CELL_ENABLED;
    }

    public static boolean bypassFillAllDirectly() {
        return BYPASS_FILL_ALL_DIRECTLY.get().booleanValue();
    }

    public static void runWithFillAllDirectlyBypass(Runnable action) {
        boolean previous = BYPASS_FILL_ALL_DIRECTLY.get().booleanValue();
        BYPASS_FILL_ALL_DIRECTLY.set(Boolean.TRUE);
        try {
            action.run();
        } finally {
            BYPASS_FILL_ALL_DIRECTLY.set(Boolean.valueOf(previous));
        }
    }

    public static boolean tryFillCellColumn(double[] values,
                                            DensityFunction function,
                                            int cellStartBlockX,
                                            int firstCellZ,
                                            int cellNoiseMinY,
                                            int cellWidth,
                                            int cellHeight,
                                            int cellCountXZ,
                                            int cellCountY,
                                            int cellX) {
        logStatusOnce();
        if (!ENABLED || !CELL_ENABLED) return false;
        if (bypassRootNative(function)) return false;
        if (bypassCellNative(function)) return false;
        boolean profiling = PROFILING_ENABLED;
        long start = profiling ? System.nanoTime() : 0L;
        NativeDensityFunction compiled = tryCompileCell(function);
        if (compiled == null) return false;
        if (compiled.clearsCachePerCell) return false;
        int cellValueCount = cellWidth * cellHeight * cellWidth;
        int expected = cellCountXZ * cellCountY * cellValueCount;
        if (values.length < expected) return false;

        try {
            compiled.syncInterpolatorColumn(cellStartBlockX, cellCountXZ, cellCountY);
            nativeEvaluateInterpolatedColumn(
                    compiled.handle,
                    compiled.cacheHandle,
                    cellStartBlockX,
                    firstCellZ * cellWidth,
                    cellNoiseMinY * cellHeight,
                    cellX,
                    firstCellZ,
                    cellWidth,
                    cellHeight,
                    cellCountXZ,
                    cellCountY,
                    null,
                    values);
            if (profiling) {
                COLUMN_NANOS.add(System.nanoTime() - start);
                COLUMN_COUNT.increment();
            }
            return true;
        } catch (RuntimeException | LinkageError e) {
            LatticeNative.logFallbackOnce("density_function_cell_column", e.getMessage());
            return false;
        }
    }

    private static boolean tryFillCell(double[] values,
                                       DensityFunction function,
                                       int cellStartBlockX,
                                       int cellStartBlockY,
                                       int cellStartBlockZ,
                                       int cellWidth,
                                       int cellHeight,
                                       int cellCountXZ,
                                       int cellCountY,
                                       int cellX,
                                       int cellZ,
                                       int localCellY,
                                       int localCellZ,
                                       boolean highLevel) {
        logStatusOnce();
        if (!FIRST_CELL_LOGGED.get() && FIRST_CELL_LOGGED.compareAndSet(false, true)) {
            LOGGER.info("NativeDensityFunction first cell attempt");
        }
        if (!ENABLED) return false;
        boolean stats = STATS_ENABLED;
        boolean profiling = PROFILING_ENABLED;
        if (highLevel && !CELL_ENABLED) {
            if (stats) CELL_SKIP_DISABLED.increment();
            return false;
        }
        if (bypassRootNative(function)) {
            if (stats) CELL_SKIP_ROOT_BYPASS.increment();
            return false;
        }
        if (bypassCellNative(function)) {
            if (stats) CELL_SKIP_CELL_BYPASS.increment();
            return false;
        }
        long start = profiling ? System.nanoTime() : 0L;
        NativeDensityFunction compiled = highLevel ? tryCompileCell(function) : tryCompileDirect(function);
        if (compiled == null) {
            if (stats) CELL_SKIP_COMPILE_NULL.increment();
            return false;
        }
        if (stats) {
            CELL_ATTEMPTS.increment();
            if (highLevel) CELL_HIGH_ATTEMPTS.increment();
            else CELL_DIRECT_ATTEMPTS.increment();
            maybeLogStats();
        }
        int expected = cellWidth * cellHeight * cellWidth;
        if (values.length < expected) {
            if (stats) CELL_SKIP_OUTPUT_TOO_SMALL.increment();
            return false;
        }

        try {
            if (highLevel) {
                if (compiled.clearsCachePerCell) nativeClearCache(compiled.cacheHandle);
                if (stats) CELL_INTERPOLATED.increment();
                compiled.syncInterpolatorColumn(cellStartBlockX, cellCountXZ, cellCountY);
                nativeEvaluateInterpolatedCell(
                        compiled.handle,
                        compiled.cacheHandle,
                        cellStartBlockX,
                        cellStartBlockY + cellHeight - 1,
                        cellStartBlockZ,
                        cellX,
                        cellZ,
                        localCellY,
                        localCellZ,
                        cellWidth,
                        cellHeight,
                        null,
                        values);
            } else {
                nativeEvaluateCell(
                        compiled.handle,
                        compiled.cacheHandle,
                        cellStartBlockX,
                        cellStartBlockY + cellHeight - 1,
                        cellStartBlockZ,
                        cellX,
                        cellZ,
                        cellWidth,
                        cellHeight,
                        null,
                        values);
            }
            if (stats) {
                CELL_SUCCESS.increment();
                if (highLevel) CELL_HIGH_SUCCESS.increment();
                else CELL_DIRECT_SUCCESS.increment();
            }
            if (profiling) CELL_NANOS.add(System.nanoTime() - start);
            return true;
        } catch (RuntimeException | LinkageError e) {
            LatticeNative.logFallbackOnce("density_function_cell_grid", e.getMessage());
            return false;
        }
    }

    private static NativeDensityFunction tryCompile(DensityFunction function) {
        if (!LatticeNative.isLoaded() || function == null) return null;
        IdentityHashMap<DensityFunction, Object> threadCache = threadCompileCache();
        Object threadCached = threadCache.get(function);
        if (threadCached != null) return threadCached == FAILED_COMPILE_SENTINEL ? null : (NativeDensityFunction) threadCached;
        LastCompile last = LAST_COMPILE.get();
        if (last != null && last.function() == function) {
            threadCache.put(function, compileCacheValue(last.compiled()));
            return last.compiled();
        }
        synchronized (CACHE) {
            NativeDensityFunction cached = CACHE.get(function);
            if (cached != null) {
                LAST_COMPILE.set(new LastCompile(function, cached));
                threadCache.put(function, cached);
                return cached;
            }
            if (FAILED_COMPILES.containsKey(function)) {
                LAST_COMPILE.set(new LastCompile(function, null));
                threadCache.put(function, FAILED_COMPILE_SENTINEL);
                return null;
            }
        }

        if (STATS_ENABLED) COMPILE_ATTEMPTS.increment();
        long start = PROFILING_ENABLED ? System.nanoTime() : 0L;
        NativeDensityFunction compiled = compileNew(function);
        if (PROFILING_ENABLED) COMPILE_NANOS.add(System.nanoTime() - start);

        synchronized (CACHE) {
            NativeDensityFunction cached = CACHE.get(function);
            if (cached != null) {
                if (compiled != null) compiled.destroyNow();
                LAST_COMPILE.set(new LastCompile(function, cached));
                threadCache.put(function, cached);
                return cached;
            }
            if (FAILED_COMPILES.containsKey(function)) {
                if (compiled != null) compiled.destroyNow();
                LAST_COMPILE.set(new LastCompile(function, null));
                threadCache.put(function, FAILED_COMPILE_SENTINEL);
                return null;
            }
            if (compiled != null) {
                if (STATS_ENABLED) COMPILE_SUCCESS.increment();
                CACHE.put(function, compiled);
            } else {
                FAILED_COMPILES.put(function, Boolean.TRUE);
            }
            LAST_COMPILE.set(new LastCompile(function, compiled));
            threadCache.put(function, compileCacheValue(compiled));
            return compiled;
        }
    }

    private static NativeDensityFunction tryCompileDirect(DensityFunction function) {
        if (!LatticeNative.isLoaded() || function == null) return null;
        if (DIRECT_CELL_REJECTS.get() > 65536L && CELL_DIRECT_SUCCESS.sum() < 1024L) return null;
        IdentityHashMap<DensityFunction, Object> threadCache = threadDirectCompileCache();
        Object threadCached = threadCache.get(function);
        if (threadCached != null) return threadCached == FAILED_COMPILE_SENTINEL ? null : (NativeDensityFunction) threadCached;
        LastCompile last = LAST_DIRECT_COMPILE.get();
        if (last != null && last.function() == function) {
            threadCache.put(function, compileCacheValue(last.compiled()));
            return last.compiled();
        }
        synchronized (DIRECT_CACHE) {
            NativeDensityFunction cached = DIRECT_CACHE.get(function);
            if (cached != null) {
                LAST_DIRECT_COMPILE.set(new LastCompile(function, cached));
                threadCache.put(function, cached);
                return cached;
            }
            if (FAILED_DIRECT_COMPILES.containsKey(function)) {
                LAST_DIRECT_COMPILE.set(new LastCompile(function, null));
                threadCache.put(function, FAILED_COMPILE_SENTINEL);
                return null;
            }
        }

        if (STATS_ENABLED) COMPILE_ATTEMPTS.increment();
        long start = PROFILING_ENABLED ? System.nanoTime() : 0L;
        NativeDensityFunction compiled = compileNew(function, true);
        if (PROFILING_ENABLED) COMPILE_NANOS.add(System.nanoTime() - start);

        synchronized (DIRECT_CACHE) {
            NativeDensityFunction cached = DIRECT_CACHE.get(function);
            if (cached != null) {
                if (compiled != null) compiled.destroyNow();
                LAST_DIRECT_COMPILE.set(new LastCompile(function, cached));
                threadCache.put(function, cached);
                return cached;
            }
            if (FAILED_DIRECT_COMPILES.containsKey(function)) {
                if (compiled != null) compiled.destroyNow();
                LAST_DIRECT_COMPILE.set(new LastCompile(function, null));
                threadCache.put(function, FAILED_COMPILE_SENTINEL);
                return null;
            }
            if (compiled != null) {
                if (STATS_ENABLED) COMPILE_SUCCESS.increment();
                DIRECT_CACHE.put(function, compiled);
            } else {
                DIRECT_CELL_REJECTS.incrementAndGet();
                FAILED_DIRECT_COMPILES.put(function, Boolean.TRUE);
            }
            LAST_DIRECT_COMPILE.set(new LastCompile(function, compiled));
            threadCache.put(function, compileCacheValue(compiled));
            return compiled;
        }
    }

    private static Object compileCacheValue(NativeDensityFunction compiled) {
        return compiled == null ? FAILED_COMPILE_SENTINEL : compiled;
    }

    private static IdentityHashMap<DensityFunction, Object> threadCompileCache() {
        IdentityHashMap<DensityFunction, Object> cache = THREAD_COMPILE_CACHE.get();
        if (THREAD_COMPILE_CACHE_EPOCH.get() != COMPILE_CACHE_EPOCH) {
            cache.clear();
            THREAD_COMPILE_CACHE_EPOCH.set(COMPILE_CACHE_EPOCH);
        }
        return cache;
    }

    private static IdentityHashMap<DensityFunction, Object> threadDirectCompileCache() {
        IdentityHashMap<DensityFunction, Object> cache = THREAD_DIRECT_COMPILE_CACHE.get();
        if (THREAD_DIRECT_COMPILE_CACHE_EPOCH.get() != COMPILE_CACHE_EPOCH) {
            cache.clear();
            THREAD_DIRECT_COMPILE_CACHE_EPOCH.set(COMPILE_CACHE_EPOCH);
        }
        return cache;
    }

    private void destroyNow() {
        this.cleanable.clean();
    }

    private static NativeDensityFunction tryCompileCell(DensityFunction function) {
        LastCompile last = LAST_CELL_COMPILE.get();
        if (last != null && last.function() == function) return last.compiled();
        NativeDensityFunction compiled = tryCompile(function);
        NativeDensityFunction cellCompiled = compiled != null && !compiled.interpolators.isEmpty() ? compiled : null;
        LAST_CELL_COMPILE.set(new LastCompile(function, cellCompiled));
        return cellCompiled;
    }

    private static boolean bypassCellNative(DensityFunction function) {
        if (function == null) return true;
        LastCellBypass last = LAST_CELL_BYPASS.get();
        if (last != null && last.function() == function) return last.bypass();
        boolean bypass = function.getClass().getName().contains("NoiseChunk$CacheAllInCell");
        LAST_CELL_BYPASS.set(new LastCellBypass(function, bypass));
        return bypass;
    }

    private static boolean bypassRootNative(DensityFunction function) {
        if (function == null) return true;
        Class<?> type = function.getClass();
        Boolean cached = BYPASSED_ROOT_CLASSES.get(type);
        if (cached != null) return cached.booleanValue();
        String name = type.getName();
        boolean bypass = name.endsWith("DensityFunctions$BeardifierMarker")
                || (!SPLINE_ENABLED && name.endsWith("DensityFunctions$Spline"));
        BYPASSED_ROOT_CLASSES.put(type, Boolean.valueOf(bypass));
        return bypass;
    }

    private static void maybeLogStats() {
        long attempts = CELL_ATTEMPTS.sum();
        if (attempts <= 0 || attempts % LOG_INTERVAL != 0) return;
        LOGGER.info(
                "NativeDensityFunction stats: compile={}/{}, slice={}/{}, cell={}/{}, interpolatedCell={}, unsupported={}",
                COMPILE_SUCCESS.sum(),
                COMPILE_ATTEMPTS.sum(),
                SLICE_SUCCESS.sum(),
                SLICE_ATTEMPTS.sum(),
                CELL_SUCCESS.sum(),
                attempts,
                CELL_INTERPOLATED.sum(),
                unsupportedSummary());
    }

    private static void logStatusOnce() {
        if (!STATUS_LOGGED.get() && STATUS_LOGGED.compareAndSet(false, true)) {
            LOGGER.info("NativeDensityFunction enabled={} cell={} directCell={} stats={}", ENABLED, CELL_ENABLED, DIRECT_CELL_ENABLED, STATS_ENABLED);
        }
    }

    private static String unsupportedSummary() {
        if (UNSUPPORTED.isEmpty()) return "{}";
        StringBuilder builder = new StringBuilder("{");
        int written = 0;
        for (Map.Entry<String, LongAdder> entry : UNSUPPORTED.entrySet()) {
            if (written++ > 0) builder.append(", ");
            if (written > 8) {
                builder.append("...");
                break;
            }
            builder.append(entry.getKey()).append('=').append(entry.getValue().sum());
        }
        return builder.append('}').toString();
    }

    private static void recordUnsupported(DensityFunction function) {
        String name = function == null ? "<null>" : function.getClass().getName();
        UNSUPPORTED.computeIfAbsent(name, ignored -> new LongAdder()).increment();
    }

    private static NativeDensityFunction compileNew(DensityFunction function) {
        return compileNew(function, false);
    }

    private static NativeDensityFunction compileNew(DensityFunction function, boolean directCell) {
        long handle = 0L;
        long cacheHandle = 0L;
        try {
            handle = nativeCreate();
            if (handle == 0L) return null;
            Compiler compiler = new Compiler(handle, function, directCell);
            int root = compiler.compile(function);
            if (root < 0) return null;
            if (directCell && !compiler.directCellCandidate()) return null;
            nativeSetRoot(handle, root);
            cacheHandle = nativeCreateCache(handle);
            if (cacheHandle == 0L) return null;
            NativeDensityFunction compiled = new NativeDensityFunction(handle, cacheHandle, compiler.interpolators(), compiler.cacheAllInCellValues(), compiler.clearsCachePerCell());
            compiled.bindCacheAllInCellArrays();
            return compiled;
        } catch (RuntimeException | LinkageError e) {
            LatticeNative.logFallbackOnce("density_function_compile", e.getMessage());
            return null;
        } finally {
            if (cacheHandle == 0L && handle != 0L) {
                try {
                    nativeDestroy(handle);
                } catch (LinkageError ignored) {
                }
            }
        }
    }

    private record Destroy(long handle, long cacheHandle) implements Runnable {
        @Override
        public void run() {
            try {
                if (cacheHandle != 0L) nativeDestroyCache(cacheHandle);
                if (handle != 0L) nativeDestroy(handle);
            } catch (LinkageError ignored) {
            }
        }
    }

    private record LastCompile(DensityFunction function, NativeDensityFunction compiled) {}
    private record LastCellBypass(DensityFunction function, boolean bypass) {}

    private void syncInterpolatorColumn(int cellStartBlockX, int cellCountXZ, int cellCountY) {
        if (preparedHorizontalCellCount != cellCountXZ || preparedVerticalCellCount != cellCountY) {
            nativePrepareInterpolators(cacheHandle, cellCountXZ, cellCountY);
            preparedHorizontalCellCount = cellCountXZ;
            preparedVerticalCellCount = cellCountY;
            syncedCellStartBlockX = Integer.MIN_VALUE;
        }
        if (syncedCellStartBlockX == cellStartBlockX) return;
        if (interpolatorAccesses.length == 0) {
            syncedCellStartBlockX = cellStartBlockX;
            return;
        }

        long start = PROFILING_ENABLED ? System.nanoTime() : 0L;
        int zRows = cellCountXZ + 1;
        int yRows = cellCountY + 1;
        for (int i = 0; i < interpolatorAccesses.length; i++) {
            NativeNoiseInterpolatorAccess access = interpolatorAccesses[i];
            double[] startSlice = access.lattice$flatSlice0();
            double[] endSlice = access.lattice$flatSlice1();
            if (interpolatorStartSlices[i] != startSlice || interpolatorEndSlices[i] != endSlice) {
                interpolatorColumnsBound = false;
                interpolatorStartSlices[i] = startSlice;
                interpolatorEndSlices[i] = endSlice;
            }
        }
        if (!interpolatorColumnsBound) {
            nativeBindInterpolatorColumnsFlat(cacheHandle, interpolatorSlots, interpolatorStartSlices, interpolatorEndSlices);
            interpolatorColumnsBound = true;
        }
        nativeSyncBoundInterpolatorColumnsFlat(cacheHandle, zRows, yRows);
        if (PROFILING_ENABLED) {
            SYNC_NANOS.add(System.nanoTime() - start);
            SYNC_COUNT.increment();
        }
        syncedCellStartBlockX = cellStartBlockX;
    }

    private void bindCacheAllInCellArrays() {
        if (cacheAllInCellValues != null) {
            nativeBindCacheAllInCellArrays(cacheHandle, cacheAllInCellValues);
        }
    }

    public static boolean setOption(String option, boolean value) {
        switch (option) {
            case "enabled" -> ENABLED = value;
            case "cell" -> CELL_ENABLED = value;
            case "directCell" -> DIRECT_CELL_ENABLED = value;
            case "shiftedNoise" -> SHIFTED_NOISE_ENABLED = value;
            case "spline" -> SPLINE_ENABLED = value;
            case "multipointSpline" -> MULTIPOINT_SPLINE_ENABLED = value;
            case "stats" -> STATS_ENABLED = value;
            case "profiling" -> PROFILING_ENABLED = value;
            case "parity" -> PARITY_ENABLED = value;
            default -> {
                return NativeWorldgenToggle.setOption(option, value);
            }
        }
        clearCompiledCaches();
        return true;
    }

    public static boolean setIntOption(String option, int value) {
        switch (option) {
            case "parityInterval" -> PARITY_INTERVAL = Math.max(1, value);
            default -> {
                return false;
            }
        }
        return true;
    }

    public static String status() {
        return "enabled=" + ENABLED
                + " cell=" + CELL_ENABLED
                + " directCell=" + DIRECT_CELL_ENABLED
                + " shiftedNoise=" + SHIFTED_NOISE_ENABLED
                + " spline=" + SPLINE_ENABLED
                + " multipointSpline=" + MULTIPOINT_SPLINE_ENABLED
                + " stats=" + STATS_ENABLED
                + " profiling=" + PROFILING_ENABLED
                + " parity=" + PARITY_ENABLED
                + " parityInterval=" + PARITY_INTERVAL
                + NativeWorldgenToggle.status()
                + " compile=" + COMPILE_SUCCESS.sum() + '/' + COMPILE_ATTEMPTS.sum()
                + " slice=" + SLICE_SUCCESS.sum() + '/' + SLICE_ATTEMPTS.sum()
                + " cell=" + CELL_SUCCESS.sum() + '/' + CELL_ATTEMPTS.sum()
                + " cellDirect=" + CELL_DIRECT_SUCCESS.sum() + '/' + CELL_DIRECT_ATTEMPTS.sum()
                + " cellHigh=" + CELL_HIGH_SUCCESS.sum() + '/' + CELL_HIGH_ATTEMPTS.sum()
                + " interpolatedCell=" + CELL_INTERPOLATED.sum()
                + " cellSkip={disabled=" + CELL_SKIP_DISABLED.sum()
                + ", root=" + CELL_SKIP_ROOT_BYPASS.sum()
                + ", cell=" + CELL_SKIP_CELL_BYPASS.sum()
                + ", compile=" + CELL_SKIP_COMPILE_NULL.sum()
                + ", output=" + CELL_SKIP_OUTPUT_TOO_SMALL.sum() + '}'
                + " timingsUs={compile=" + avgMicros(COMPILE_NANOS.sum(), COMPILE_ATTEMPTS.sum())
                + ", slice=" + avgMicros(SLICE_NANOS.sum(), SLICE_SUCCESS.sum())
                + ", cell=" + avgMicros(CELL_NANOS.sum(), CELL_SUCCESS.sum())
                + ", column=" + avgMicros(COLUMN_NANOS.sum(), COLUMN_COUNT.sum())
                + ", sync=" + avgMicros(SYNC_NANOS.sum(), SYNC_COUNT.sum()) + '}'
                + " parity={checks=" + PARITY_CHECKS.sum()
                + ", failures=" + PARITY_FAILURES.sum()
                + ", maxError=" + Double.longBitsToDouble(PARITY_MAX_ERROR_BITS.get()) + '}'
                + " unsupported=" + unsupportedSummary();
    }

    public static void resetStats() {
        COMPILE_ATTEMPTS.reset();
        COMPILE_SUCCESS.reset();
        SLICE_ATTEMPTS.reset();
        SLICE_SUCCESS.reset();
        CELL_ATTEMPTS.reset();
        CELL_SUCCESS.reset();
        CELL_INTERPOLATED.reset();
        CELL_DIRECT_ATTEMPTS.reset();
        CELL_DIRECT_SUCCESS.reset();
        CELL_HIGH_ATTEMPTS.reset();
        CELL_HIGH_SUCCESS.reset();
        CELL_SKIP_DISABLED.reset();
        CELL_SKIP_ROOT_BYPASS.reset();
        CELL_SKIP_CELL_BYPASS.reset();
        CELL_SKIP_COMPILE_NULL.reset();
        CELL_SKIP_OUTPUT_TOO_SMALL.reset();
        COMPILE_NANOS.reset();
        SLICE_NANOS.reset();
        CELL_NANOS.reset();
        COLUMN_NANOS.reset();
        COLUMN_COUNT.reset();
        SYNC_NANOS.reset();
        SYNC_COUNT.reset();
        PARITY_CHECKS.reset();
        PARITY_FAILURES.reset();
        PARITY_MAX_ERROR_BITS.set(Double.doubleToRawLongBits(0.0));
        PARITY_SAMPLE_COUNTER.set(0L);
        DIRECT_CELL_REJECTS.set(0L);
        UNSUPPORTED.clear();
    }

    public static boolean parityEnabled() {
        return PARITY_ENABLED;
    }

    public static boolean shouldCheckParity() {
        if (!PARITY_ENABLED) return false;
        int interval = Math.max(1, PARITY_INTERVAL);
        return PARITY_SAMPLE_COUNTER.incrementAndGet() % interval == 0L;
    }

    public static void recordParity(String path, DensityFunction function, double[] nativeValues, double[] javaValues) {
        if (!PARITY_ENABLED || nativeValues.length != javaValues.length) return;
        double max = 0.0;
        int index = -1;
        for (int i = 0; i < nativeValues.length; i++) {
            double error = Math.abs(nativeValues[i] - javaValues[i]);
            if (error > max) {
                max = error;
                index = i;
            }
        }
        PARITY_CHECKS.increment();
        updateMaxParityError(max);
        if (max > 1.0E-6) {
            PARITY_FAILURES.increment();
            LOGGER.warn("NativeDensityFunction parity mismatch path={} function={} maxError={} index={} native={} java={}",
                    path,
                    function == null ? "<null>" : function.getClass().getName(),
                    max,
                    index,
                    index >= 0 ? nativeValues[index] : Double.NaN,
                    index >= 0 ? javaValues[index] : Double.NaN);
        }
    }

    private static void updateMaxParityError(double candidate) {
        long currentBits;
        double current;
        do {
            currentBits = PARITY_MAX_ERROR_BITS.get();
            current = Double.longBitsToDouble(currentBits);
            if (candidate <= current) return;
        } while (!PARITY_MAX_ERROR_BITS.compareAndSet(currentBits, Double.doubleToRawLongBits(candidate)));
    }

    private static void clearCompiledCaches() {
        synchronized (CACHE) {
            CACHE.clear();
            FAILED_COMPILES.clear();
        }
        synchronized (DIRECT_CACHE) {
            DIRECT_CACHE.clear();
            FAILED_DIRECT_COMPILES.clear();
        }
        BYPASSED_ROOT_CLASSES.clear();
        COMPILE_CACHE_EPOCH++;
        LAST_COMPILE.remove();
        LAST_DIRECT_COMPILE.remove();
        LAST_CELL_COMPILE.remove();
        LAST_CELL_BYPASS.remove();
        THREAD_COMPILE_CACHE.remove();
        THREAD_DIRECT_COMPILE_CACHE.remove();
        THREAD_COMPILE_CACHE_EPOCH.remove();
        THREAD_DIRECT_COMPILE_CACHE_EPOCH.remove();
        STATUS_LOGGED.set(false);
        FIRST_SLICE_LOGGED.set(false);
        FIRST_CELL_LOGGED.set(false);
    }

    private static long avgMicros(long nanos, long count) {
        return count <= 0 ? 0L : nanos / count / 1_000L;
    }

    private static DensityFunction child(DensityFunction function, String name) {
        try {
            Object value = invoke(function, name);
            return value instanceof DensityFunction densityFunction ? densityFunction : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private record InterpolatorBinding(NativeNoiseInterpolatorAccess function, int slot) {}
    private record CacheAllInCellBinding(int slot, double[] values) {}

    private static Object invoke(Object owner, String methodName) {
        try {
            Method method = owner.getClass().getDeclaredMethod(methodName);
            method.setAccessible(true);
            return method.invoke(owner);
        } catch (ReflectiveOperationException methodFailure) {
            try {
                Field field = owner.getClass().getDeclaredField(methodName);
                field.setAccessible(true);
                return field.get(owner);
            } catch (ReflectiveOperationException fieldFailure) {
                throw new IllegalStateException(owner.getClass().getName() + "." + methodName + " changed shape", fieldFailure);
            }
        }
    }

    private static final class Compiler {
        private final long handle;
        private final DensityFunction root;
        private final boolean directCell;
        private final Map<DensityFunction, Integer> refs = new IdentityHashMap<>();
        private final List<InterpolatorBinding> interpolators = new ArrayList<>();
        private final List<CacheAllInCellBinding> cacheAllInCellBindings = new ArrayList<>();
        private boolean clearsCachePerCell;
        private int directNodeCount;
        private int directExpensiveNodeCount;

        private Compiler(long handle, DensityFunction root, boolean directCell) {
            this.handle = handle;
            this.root = root;
            this.directCell = directCell;
        }

        private int compile(DensityFunction function) {
            Integer cached = refs.get(function);
            if (cached != null) return cached.intValue();

            int ref = compileUncached(function);
            if (ref >= 0) {
                refs.put(function, ref);
                if (directCell) recordDirectCost(function);
            }
            return ref;
        }

        private List<InterpolatorBinding> interpolators() {
            return interpolators;
        }

        private double[][] cacheAllInCellValues() {
            int size = 0;
            for (CacheAllInCellBinding binding : cacheAllInCellBindings) {
                size = Math.max(size, binding.slot() + 1);
            }
            if (size == 0) return null;
            double[][] values = new double[size][];
            for (CacheAllInCellBinding binding : cacheAllInCellBindings) {
                values[binding.slot()] = binding.values();
            }
            return values;
        }

        private boolean clearsCachePerCell() {
            return clearsCachePerCell;
        }

        private boolean directCellCandidate() {
            return !directCell || (directExpensiveNodeCount >= 4 && directNodeCount >= 32);
        }

        private void recordDirectCost(DensityFunction function) {
            directNodeCount++;
            String name = function.getClass().getName();
            if (name.endsWith("DensityFunctions$Noise")
                    || name.endsWith("DensityFunctions$ShiftedNoise")
                    || name.endsWith("DensityFunctions$ShiftA")
                    || name.endsWith("DensityFunctions$ShiftB")
                    || name.endsWith("DensityFunctions$Shift")
                    || name.endsWith("DensityFunctions$WeirdScaledSampler")
                    || name.endsWith("DensityFunctions$Spline")
                    || name.contains("InterpolatedNoise")) {
                directExpensiveNodeCount++;
            }
        }

        private int compileUncached(DensityFunction function) {
            if (function instanceof NativeInterpolatedNoiseAccess access) {
                NativeInterpolatedNoise nativeNoise = access.lattice$getNativeInterpolatedNoise();
                return nativeNoise == null ? -1 : nativeAddInterpolatedNoise(handle, nativeNoise.handle());
            }

            String name = function.getClass().getName();
            if (name.endsWith("DensityFunctions$Constant")) {
                return nativeAddConstant(handle, (Double) invoke(function, "value"));
            }
            if (name.endsWith("DensityFunctions$BeardifierMarker")) {
                // This singleton is replaced by NoiseChunk.wrap(...) with the
                // chunk-local Beardifier instance. Compiling it as constant 0
                // is only correct before wrapping and corrupts structure terrain
                // when it leaks into a compiled tree.
                recordUnsupported(function);
                return -1;
            }
            if (name.endsWith("DensityFunctions$Noise")) {
                NativeDoublePerlinNoise noise = nativeNoiseHolderNoise(invoke(function, "noise"));
                if (noise == null) return -1;
                return nativeAddNoise(handle, noise.handle(), (Double) invoke(function, "xzScale"), (Double) invoke(function, "yScale"));
            }
            if (name.endsWith("DensityFunctions$ShiftA")) {
                NativeDoublePerlinNoise noise = nativeNoiseHolderNoise(invoke(function, "offsetNoise"));
                return noise == null ? -1 : nativeAddShiftA(handle, noise.handle());
            }
            if (name.endsWith("DensityFunctions$ShiftB")) {
                NativeDoublePerlinNoise noise = nativeNoiseHolderNoise(invoke(function, "offsetNoise"));
                return noise == null ? -1 : nativeAddShiftB(handle, noise.handle());
            }
            if (name.endsWith("DensityFunctions$Shift")) {
                NativeDoublePerlinNoise noise = nativeNoiseHolderNoise(invoke(function, "offsetNoise"));
                return noise == null ? -1 : nativeAddShift(handle, noise.handle());
            }
            if (name.endsWith("DensityFunctions$ShiftedNoise")) {
                if (!SHIFTED_NOISE_ENABLED) return -1;
                int shiftX = compile((DensityFunction) invoke(function, "shiftX"));
                int shiftY = compile((DensityFunction) invoke(function, "shiftY"));
                int shiftZ = compile((DensityFunction) invoke(function, "shiftZ"));
                if (shiftX < 0 || shiftY < 0 || shiftZ < 0) {
                    recordUnsupported(function);
                    return -1;
                }
                NativeDoublePerlinNoise noise = nativeNoiseHolderNoise(invoke(function, "noise"));
                if (noise == null) {
                    recordUnsupported(function);
                    return -1;
                }
                return nativeAddShiftedNoise(handle, shiftX, shiftY, shiftZ, noise.handle(), (Double) invoke(function, "xzScale"), (Double) invoke(function, "yScale"));
            }
            if (name.contains("NoiseChunk$NoiseInterpolator")) {
                int input = compile((DensityFunction) invoke(function, "wrapped"));
                if (input < 0) return -1;
                if (directCell) return input;
                if (!(function instanceof NativeNoiseInterpolatorAccess access)) return -1;
                int ref = nativeAddInterpolated(handle, input);
                if (ref >= 0) {
                    interpolators.add(new InterpolatorBinding(access, ref));
                    access.lattice$setNativeSlot(ref);
                }
                return ref;
            }
            if (name.endsWith("DensityFunctions$Mapped")) {
                int input = compile((DensityFunction) invoke(function, "input"));
                if (input < 0) return -1;
                return switch (((Enum<?>) invoke(function, "type")).name()) {
                    case "ABS" -> nativeAddAbs(handle, input);
                    case "SQUARE" -> nativeAddSquare(handle, input);
                    case "CUBE" -> nativeAddCube(handle, input);
                    case "HALF_NEGATIVE" -> nativeAddHalfNegative(handle, input);
                    case "QUARTER_NEGATIVE" -> nativeAddQuarterNegative(handle, input);
                    case "INVERT" -> nativeAddInvert(handle, input);
                    case "SQUEEZE" -> nativeAddSqueeze(handle, input);
                    default -> -1;
                };
            }
            if (name.endsWith("DensityFunctions$Ap2") || name.endsWith("DensityFunctions$MulOrAdd")) {
                int left = compile((DensityFunction) invoke(function, "argument1"));
                int right = compile((DensityFunction) invoke(function, "argument2"));
                if (left < 0 || right < 0) return -1;
                return switch (((Enum<?>) invoke(function, "type")).name()) {
                    case "ADD" -> nativeAddAdd(handle, left, right);
                    case "MUL" -> nativeAddMul(handle, left, right);
                    case "MIN" -> nativeAddMin(handle, left, right);
                    case "MAX" -> nativeAddMax(handle, left, right);
                    default -> -1;
                };
            }
            if (name.endsWith("DensityFunctions$RangeChoice")) {
                int input = compile((DensityFunction) invoke(function, "input"));
                int whenIn = compile((DensityFunction) invoke(function, "whenInRange"));
                int whenOut = compile((DensityFunction) invoke(function, "whenOutOfRange"));
                if (input < 0 || whenIn < 0 || whenOut < 0) return -1;
                return nativeAddRangeChoice(handle, input, (Double) invoke(function, "minInclusive"), (Double) invoke(function, "maxExclusive"), whenIn, whenOut);
            }
            if (name.endsWith("DensityFunctions$MapRange")) {
                int input = compile((DensityFunction) invoke(function, "input"));
                if (input < 0) return -1;
                return nativeAddMapRange(handle, input,
                        (Double) invoke(function, "fromLow"),
                        (Double) invoke(function, "fromHigh"),
                        (Double) invoke(function, "toLow"),
                        (Double) invoke(function, "toHigh"));
            }
            if (name.endsWith("DensityFunctions$WeirdScaledSampler")) {
                int input = compile((DensityFunction) invoke(function, "input"));
                if (input < 0) return -1;
                NativeDoublePerlinNoise noise = nativeNoiseHolderNoise(invoke(function, "noise"));
                if (noise == null) return -1;
                int type = "TYPE2".equals(((Enum<?>) invoke(function, "rarityValueMapper")).name()) ? 1 : 0;
                return nativeAddWeirdScaledSampler(handle, input, noise.handle(), type);
            }
            if (name.endsWith("DensityFunctions$Clamp")) {
                int input = compile((DensityFunction) invoke(function, "input"));
                if (input < 0) return -1;
                return nativeAddClamp(handle, input, (Double) invoke(function, "minValue"), (Double) invoke(function, "maxValue"));
            }
            if (name.endsWith("DensityFunctions$Spline")) {
                if (!SPLINE_ENABLED) return -1;
                int spline = compileSpline(invoke(function, "spline"));
                return spline < 0 ? -1 : nativeAddSpline(handle, spline);
            }
            if (name.equals("net.minecraft.world.level.levelgen.Beardifier")) {
                long beardifierHandle = function instanceof NativeBeardifierAccess access
                        ? access.lattice$nativeBeardifierHandleFromMixin()
                        : ((Number) invoke(function, "lattice$nativeBeardifierHandle")).longValue();
                return beardifierHandle == 0L ? -1 : nativeAddBeardifier(handle, beardifierHandle);
            }
            if (name.endsWith("DensityFunctions$BlendAlpha")) return nativeAddBlendAlpha(handle);
            if (name.endsWith("DensityFunctions$BlendOffset")) return nativeAddBlendOffset(handle);
            if (name.contains("NoiseChunk$BlendAlpha") || name.contains("NoiseChunk$BlendOffset")) {
                recordUnsupported(function);
                return -1;
            }
            if (name.endsWith("DensityFunctions$BlendDensity")) {
                int input = compile((DensityFunction) invoke(function, "input"));
                return input < 0 ? -1 : nativeAddBlendDensity(handle, input);
            }
            if (name.endsWith("DensityFunctions$YClampedGradient")) {
                return nativeAddYClampedGradient(handle,
                        (Integer) invoke(function, "fromY"),
                        (Integer) invoke(function, "toY"),
                        (Double) invoke(function, "fromValue"),
                        (Double) invoke(function, "toValue"));
            }
            if (name.contains("NoiseChunk$FlatCache")) {
                int input = compile((DensityFunction) invoke(function, "wrapped"));
                clearsCachePerCell = true;
                return input < 0 ? -1 : nativeAddFlatCache(handle, input);
            }
            if (name.contains("NoiseChunk$Cache2D")) {
                int input = compile((DensityFunction) invoke(function, "wrapped"));
                clearsCachePerCell = true;
                return input < 0 ? -1 : nativeAddCache2D(handle, input);
            }
            if (name.contains("NoiseChunk$CacheOnce")) {
                int input = compile((DensityFunction) invoke(function, "wrapped"));
                clearsCachePerCell = true;
                return input < 0 ? -1 : nativeAddCacheOnce(handle, input);
            }
            if (name.contains("NoiseChunk$CacheAllInCell")) {
                if (function == root) return -1;
                int ref = nativeAddCacheAllInCellValue(handle);
                int slot = ref < 0 ? -1 : nativeCacheSlot(handle, ref);
                if (slot >= 0) {
                    cacheAllInCellBindings.add(new CacheAllInCellBinding(slot, (double[]) invoke(function, "values")));
                }
                return ref;
            }

            recordUnsupported(function);
            return -1;
        }

        private int compileSpline(Object spline) {
            String name = spline.getClass().getName();
            if (name.endsWith("CubicSpline$Constant")) {
                return nativeAddFixedFloatSpline(handle, ((Number) invoke(spline, "value")).floatValue());
            }
            if (name.endsWith("CubicSpline$Multipoint")) {
                if (!MULTIPOINT_SPLINE_ENABLED) return -1;
                Object coordinate = invoke(spline, "coordinate");
                Object holder = invoke(coordinate, "function");
                int locationFunction = compile((DensityFunction) invoke(holder, "value"));
                if (locationFunction < 0) return -1;
                float[] locations = (float[]) invoke(spline, "locations");
                float[] derivatives = (float[]) invoke(spline, "derivatives");
                List<?> values = (List<?>) invoke(spline, "values");
                int[] valueRefs = new int[values.size()];
                for (int i = 0; i < values.size(); i++) {
                    int valueRef = compileSpline(values.get(i));
                    if (valueRef < 0) return -1;
                    valueRefs[i] = valueRef;
                }
                return nativeAddImplSpline(handle, locationFunction, locations, derivatives, valueRefs);
            }
            return -1;
        }

        private static NativeDoublePerlinNoise nativeNoiseHolderNoise(Object noiseHolder) {
            NormalNoise noise = (NormalNoise) invoke(noiseHolder, "noise");
            if (!(noise instanceof NativeNormalNoiseAccess access)) return null;
            return access.lattice$getNativeDoublePerlinNoise();
        }

    }

    private static native long nativeCreate();
    private static native void nativeDestroy(long handle);
    private static native void nativeSetRoot(long handle, int nodeRef);
    private static native long nativeCreateCache(long handle);
    private static native void nativeDestroyCache(long cacheHandle);
    private static native void nativeBindCacheAllInCellArrays(long cacheHandle, double[][] arrays);
    private static native void nativeClearCache(long cacheHandle);
    private static native void nativeEvaluateGrid(long handle, long cacheHandle, double x0, double y0, double z0, double dx, double dy, double dz, int cellX0, int cellZ0, int nx, int ny, int nz, double[] out);
    private static native void nativeEvaluateYColumn(long handle, long cacheHandle, double x, double y0, double z, double dy, int cellX, int cellZ, int ny, double[] out);
    private static native void nativeEvaluateCell(long handle, long cacheHandle, double x0, double yTop, double z0, int cellX, int cellZ, int cellWidth, int cellHeight, double[][] cacheAllInCellValues, double[] out);
    private static native void nativeEvaluateInterpolatedCell(long handle, long cacheHandle, double x0, double yTop, double z0, int cellX, int cellZ, int localCellY, int localCellZ, int cellWidth, int cellHeight, double[][] cacheAllInCellValues, double[] out);
    private static native void nativeEvaluateInterpolatedColumn(long handle, long cacheHandle, double x0, double z0, double yMin, int cellX, int firstCellZ, int cellWidth, int cellHeight, int cellCountXZ, int cellCountY, double[][] cacheAllInCellValues, double[] out);
    private static native int nativeAddConstant(long handle, double value);
    private static native int nativeAddAbs(long handle, int input);
    private static native int nativeAddSquare(long handle, int input);
    private static native int nativeAddCube(long handle, int input);
    private static native int nativeAddHalfNegative(long handle, int input);
    private static native int nativeAddQuarterNegative(long handle, int input);
    private static native int nativeAddInvert(long handle, int input);
    private static native int nativeAddSqueeze(long handle, int input);
    private static native int nativeAddAdd(long handle, int left, int right);
    private static native int nativeAddMul(long handle, int left, int right);
    private static native int nativeAddMin(long handle, int left, int right);
    private static native int nativeAddMax(long handle, int left, int right);
    private static native int nativeAddYClampedGradient(long handle, int fromY, int toY, double fromValue, double toValue);
    private static native int nativeAddRangeChoice(long handle, int input, double minInclusive, double maxExclusive, int whenIn, int whenOut);
    private static native int nativeAddNoise(long handle, long noiseHandle, double scaleXZ, double scaleY);
    private static native int nativeAddShiftedNoise(long handle, int shiftX, int shiftY, int shiftZ, long noiseHandle, double xzScale, double yScale);
    private static native int nativeAddShiftA(long handle, long noiseHandle);
    private static native int nativeAddShiftB(long handle, long noiseHandle);
    private static native int nativeAddShift(long handle, long noiseHandle);
    private static native int nativeAddCache2D(long handle, int input);
    private static native int nativeAddCacheOnce(long handle, int input);
    private static native int nativeAddCacheAllInCell(long handle, int input);
    private static native int nativeAddCacheAllInCellValue(long handle);
    private static native int nativeAddFlatCache(long handle, int input);
    private static native int nativeAddInterpolated(long handle, int input);
    private static native int nativeAddBlendAlpha(long handle);
    private static native int nativeAddBlendOffset(long handle);
    private static native int nativeAddBlendDensity(long handle, int input);
    private static native int nativeAddClamp(long handle, int input, double minValue, double maxValue);
    private static native int nativeAddMapRange(long handle, int input, double fromLow, double fromHigh, double toLow, double toHigh);
    private static native int nativeAddInterpolatedNoise(long handle, long samplerHandle);
    private static native int nativeAddWeirdScaledSampler(long handle, int input, long noiseHandle, int type);
    private static native int nativeAddFixedFloatSpline(long handle, float value);
    private static native int nativeAddImplSpline(long handle, int locationFunctionNodeRef, float[] locations, float[] derivatives, int[] valueSplineRefs);
    private static native int nativeAddSpline(long handle, int splineRef);
    private static native int nativeAddBeardifier(long handle, long beardifierHandle);
    private static native int nativeCacheSlot(long handle, int nodeRef);
    private static native void nativePrepareInterpolators(long cacheHandle, int horizontalCellCount, int verticalCellCount);
    private static native void nativeSetInterpolatorColumn(long cacheHandle, int slot, double[][] startSlice, double[][] endSlice, int zRows, int yRows);
    private static native void nativeSetInterpolatorColumnFlat(long cacheHandle, int slot, double[] startSlice, double[] endSlice, int zRows, int yRows);
    private static native void nativeSetInterpolatorColumnsFlat(long cacheHandle, int[] slots, double[][] startSlices, double[][] endSlices, int zRows, int yRows);
    private static native void nativeBindInterpolatorColumnsFlat(long cacheHandle, int[] slots, double[][] startSlices, double[][] endSlices);
    private static native void nativeSyncBoundInterpolatorColumnsFlat(long cacheHandle, int zRows, int yRows);
    private static native void nativeSetDensityRow(long cacheHandle, int slot, int cellZ, boolean toEndBuffer, double[] values);

}
