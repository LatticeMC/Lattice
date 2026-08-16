package com.latticemc.lattice.nativelib;

import com.latticemc.lattice.bridge.NativeInterpolatedNoiseAccess;
import com.latticemc.lattice.bridge.NativeNormalNoiseAccess;
import java.lang.ref.Cleaner;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.WeakHashMap;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class NativeDensityFunction {
    public static final int CELL_COLUMNS_UNHANDLED = -1;
    public static final int CELL_COLUMNS_KNOWN_JAVA_ONLY = -2;
    private static final Logger LOGGER = LoggerFactory.getLogger("Lattice");
    private static volatile boolean ENABLED = Boolean.parseBoolean(System.getProperty("lattice.nativeDensityFunction", "false"));
    private static final boolean GRID_ENABLED = Boolean.getBoolean("lattice.nativeDensityFunctionGrid");
    private static volatile boolean CELL_ENABLED = Boolean.parseBoolean(System.getProperty("lattice.nativeDensityFunctionCell", "true"));
    private static volatile boolean DIRECT_CELL_ENABLED = Boolean.parseBoolean(System.getProperty("lattice.nativeDensityFunctionDirectCell", "true"));
    private static volatile boolean DIRECT_CELL_COLUMN_ENABLED = Boolean.parseBoolean(System.getProperty("lattice.nativeDensityFunctionDirectCellColumn", "true"));
    private static volatile boolean SHIFTED_NOISE_ENABLED = Boolean.parseBoolean(System.getProperty("lattice.nativeDensityFunctionShiftedNoise", "true"));
    private static volatile boolean SPLINE_ENABLED = Boolean.parseBoolean(System.getProperty("lattice.nativeDensityFunctionSpline", "true"));
    private static volatile boolean MULTIPOINT_SPLINE_ENABLED = Boolean.parseBoolean(System.getProperty("lattice.nativeDensityFunctionMultipointSpline", "true"));
    private static volatile boolean CLIMATE_BATCH_ENABLED = Boolean.parseBoolean(System.getProperty("lattice.nativeDensityFunctionClimateBatch", "true"));
    private static volatile boolean STATS_ENABLED = Boolean.getBoolean("lattice.nativeDensityFunctionStats");
    private static volatile boolean EXECUTION_STATS_ENABLED = Boolean.getBoolean("lattice.nativeDensityFunctionExecutionStats");
    private static volatile boolean PROFILING_ENABLED = Boolean.getBoolean("lattice.nativeDensityFunctionProfiling");
    private static volatile boolean PARITY_ENABLED = Boolean.getBoolean("lattice.nativeDensityFunctionParity");
    private static volatile int PARITY_INTERVAL = Integer.getInteger("lattice.nativeDensityFunctionParityInterval", 1024);
    private static volatile boolean Y_COLUMN_NATIVE_AVAILABLE = true;
    private static volatile boolean GRID_ROOTS_NATIVE_AVAILABLE = true;
    private static volatile boolean Y_COLUMN_ROOTS_FLAT_ROWS_NATIVE_AVAILABLE = true;
    private static volatile boolean Y_COLUMN_ROOTS_FLAT_ROWS_FAST_NATIVE_AVAILABLE = true;
    private static volatile boolean Y_COLUMN_ROOTS_FLAT_ROWS_BOUND_NATIVE_AVAILABLE = true;
    private static volatile boolean Y_COLUMNS_FLAT_ROWS_NATIVE_AVAILABLE = true;
    private static volatile boolean Y_COLUMNS_FLAT_NATIVE_AVAILABLE = true;
    private static volatile boolean Y_COLUMNS_PACKED_NATIVE_AVAILABLE = true;
    private static volatile boolean INTERPOLATED_COLUMN_NATIVE_AVAILABLE = true;
    private static volatile boolean INTERPOLATED_COLUMNS_NATIVE_AVAILABLE = true;
    private static volatile boolean EXECUTION_STATS_NATIVE_AVAILABLE = true;
    private static final Cleaner CLEANER = Cleaner.create();
    private static final Map<DensityFunction, NativeDensityFunction> CACHE = new WeakHashMap<>();
    private static final Map<DensityFunction, NativeDensityFunction> DIRECT_CACHE = new WeakHashMap<>();
    private static final Map<DensityFunction, Boolean> FAILED_COMPILES = new WeakHashMap<>();
    private static final Map<DensityFunction, Boolean> FAILED_DIRECT_COMPILES = new WeakHashMap<>();
    private static final IdentityHashMap<Object, Map<Integer, SliceBatchTemplate>> SLICE_BATCH_TEMPLATES = new IdentityHashMap<>();
    private static final IdentityHashMap<Object, ClimateBatchTemplate> CLIMATE_BATCH_TEMPLATES = new IdentityHashMap<>();
    private static final IdentityHashMap<Object, Boolean> CLIMATE_BATCH_JAVA_ONLY_ARENAS = new IdentityHashMap<>();
    private static final IdentityHashMap<Object, Boolean> CELL_COLUMN_JAVA_ONLY_ARENAS = new IdentityHashMap<>();
    private static final ConcurrentHashMap<Class<?>, Boolean> BYPASSED_ROOT_CLASSES = new ConcurrentHashMap<>();
    private static final ThreadLocal<LastCompile> LAST_COMPILE = new ThreadLocal<>();
    private static final ThreadLocal<LastCompile> LAST_DIRECT_COMPILE = new ThreadLocal<>();
    private static final ThreadLocal<LastCompile> LAST_CELL_COMPILE = new ThreadLocal<>();
    private static final ThreadLocal<LastCellBypass> LAST_CELL_BYPASS = new ThreadLocal<>();
    private static final ThreadLocal<LastSliceRowsReject> LAST_SLICE_ROWS_REJECT = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> BYPASS_FILL_ALL_DIRECTLY = ThreadLocal.withInitial(() -> Boolean.FALSE);
    private static final Object FAILED_COMPILE_SENTINEL = new Object();
    private static final ThreadLocal<ThreadCompileCache> THREAD_COMPILE_CACHE = ThreadLocal.withInitial(ThreadCompileCache::new);
    private static final ThreadLocal<ThreadCompileCache> THREAD_DIRECT_COMPILE_CACHE = ThreadLocal.withInitial(ThreadCompileCache::new);
    private static final int THREAD_COMPILE_CACHE_CAPACITY = 32;
    private static final Object THREAD_COMPILE_CACHE_MISS = new Object();
    private static final int MAX_DIRECT_CELL_COLUMN_POOL_ENTRIES = 16;
    private static final int MAX_POOLED_DIRECT_CELL_COLUMN_LENGTH = 1 << 20;
    private static final ThreadLocal<ArrayDeque<double[]>> DIRECT_CELL_COLUMN_POOL = ThreadLocal.withInitial(ArrayDeque::new);
    private static final ThreadLocal<SliceBatchBuffers> SLICE_BATCH_BUFFERS = ThreadLocal.withInitial(SliceBatchBuffers::new);
    private static final ThreadLocal<ColumnBatchBuffers> COLUMN_BATCH_BUFFERS = ThreadLocal.withInitial(ColumnBatchBuffers::new);
    private static final ThreadLocal<ClimateBatchBuffers> CLIMATE_BATCH_BUFFERS = ThreadLocal.withInitial(ClimateBatchBuffers::new);
    private static final ThreadLocal<ExecutionStatsSample> EXECUTION_STATS_SAMPLE = new ThreadLocal<>();
    private static final ThreadLocal<Integer> THREAD_COMPILE_CACHE_EPOCH = ThreadLocal.withInitial(() -> -1);
    private static final ThreadLocal<Integer> THREAD_DIRECT_COMPILE_CACHE_EPOCH = ThreadLocal.withInitial(() -> -1);
    private static volatile int COMPILE_CACHE_EPOCH = 0;
    private static final LongAdder COMPILE_ATTEMPTS = new LongAdder();
    private static final LongAdder COMPILE_SUCCESS = new LongAdder();
    private static final LongAdder SLICE_ATTEMPTS = new LongAdder();
    private static final LongAdder SLICE_SUCCESS = new LongAdder();
    private static final LongAdder SLICE_BATCH_CALLS = new LongAdder();
    private static final LongAdder SLICE_BATCH_FUNCTIONS = new LongAdder();
    private static final LongAdder GRID_ATTEMPTS = new LongAdder();
    private static final LongAdder GRID_SUCCESS = new LongAdder();
    private static final LongAdder CLIMATE_BATCH_ATTEMPTS = new LongAdder();
    private static final LongAdder CLIMATE_BATCH_SUCCESS = new LongAdder();
    private static final LongAdder SHARED_LEAF_MARKS = new LongAdder();
    private static final LongAdder SLICE_TEMPLATE_HITS = new LongAdder();
    private static final LongAdder SLICE_TEMPLATE_MISSES = new LongAdder();
    private static final LongAdder CLIMATE_TEMPLATE_HITS = new LongAdder();
    private static final LongAdder CLIMATE_TEMPLATE_MISSES = new LongAdder();
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
    private static final LongAdder THREAD_COMPILE_CACHE_REPLACEMENTS = new LongAdder();
    private static final LongAdder COMPILE_NANOS = new LongAdder();
    private static final LongAdder SLICE_NANOS = new LongAdder();
    private static final LongAdder GRID_NANOS = new LongAdder();
    private static final LongAdder CLIMATE_BATCH_NANOS = new LongAdder();
    private static final LongAdder CELL_NANOS = new LongAdder();
    private static final LongAdder COLUMN_NANOS = new LongAdder();
    private static final LongAdder COLUMN_COUNT = new LongAdder();
    private static final LongAdder COLUMN_BATCH_CALLS = new LongAdder();
    private static final LongAdder COLUMN_BATCH_FUNCTIONS = new LongAdder();
    private static final LongAdder COLUMN_JAVA_ONLY_BATCHES = new LongAdder();
    private static final LongAdder COLUMN_JAVA_ONLY_BYPASSES = new LongAdder();
    private static final LongAdder SYNC_NANOS = new LongAdder();
    private static final LongAdder SYNC_COUNT = new LongAdder();
    private static final LongAdder PARITY_CHECKS = new LongAdder();
    private static final LongAdder PARITY_FAILURES = new LongAdder();
    private static final AtomicLong PARITY_MAX_ERROR_BITS = new AtomicLong(Double.doubleToRawLongBits(0.0));
    private static final AtomicLong PARITY_SAMPLE_COUNTER = new AtomicLong();
    private static final AtomicLong DIRECT_CELL_REJECTS = new AtomicLong();
    private static final AtomicLong DIRECT_CELL_CANDIDATE_REJECTS = new AtomicLong();
    private static final ConcurrentHashMap<String, LongAdder> UNSUPPORTED = new ConcurrentHashMap<>();
    private static final int LOG_INTERVAL = 4096;
    private static final AtomicBoolean STATUS_LOGGED = new AtomicBoolean(false);
    private static final AtomicBoolean FIRST_SLICE_LOGGED = new AtomicBoolean(false);
    private static final AtomicBoolean FIRST_CELL_LOGGED = new AtomicBoolean(false);
    private static final int EXECUTION_STATS_HEADER_LONGS = 11;
    private static final String[] EXECUTION_NODE_KINDS = {
            "Constant", "Abs", "Square", "Cube", "HalfNegative", "QuarterNegative", "Invert", "Squeeze",
            "Add", "Mul", "Min", "Max", "YClampedGradient", "MapRange", "Lerp", "RangeChoice",
            "Noise", "ShiftedNoise", "ShiftA", "ShiftB", "Shift", "Cache2D", "CacheOnce", "CacheAllInCell",
            "FlatCache", "Interpolated", "WeirdScaledSampler", "EndIslands", "Clamp", "BlendAlpha",
            "BlendOffset", "BlendDensity", "Spline", "FindTopSurface", "InterpolatedNoise", "Beardifier"
    };
    private static final int EXECUTION_STATS_LONGS = EXECUTION_STATS_HEADER_LONGS + EXECUTION_NODE_KINDS.length * 2;

    private final long handle;
    private final long cacheHandle;
    private final List<InterpolatorBinding> interpolators;
    private final int[] interpolatorSlots;
    private final NativeNoiseInterpolatorAccess[] interpolatorAccesses;
    private final double[][] interpolatorStartSlices;
    private final double[][] interpolatorEndSlices;
    private final double[][] cacheAllInCellValues;
    private final NativeCacheAllInCellAccess[] cacheAllInCellBindings;
    private final boolean clearsCachePerCell;
    private int preparedHorizontalCellCount = -1;
    private int preparedVerticalCellCount = -1;
    private int syncedCellStartBlockX = Integer.MIN_VALUE;
    private boolean interpolatorColumnsBound;
    @SuppressWarnings("unused")
    private final Cleaner.Cleanable cleanable;

    private NativeDensityFunction(long handle, long cacheHandle, List<InterpolatorBinding> interpolators,
                                  double[][] cacheAllInCellValues,
                                  NativeCacheAllInCellAccess[] cacheAllInCellBindings,
                                  boolean clearsCachePerCell) {
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
        this.cacheAllInCellBindings = cacheAllInCellBindings;
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
        trackExecutionStatsCache(compiled.cacheHandle);
        try {
            evaluateYColumn(compiled.handle, compiled.cacheHandle, x, y0, z, dy, cellX, cellZ, values.length, values);
            if (stats) SLICE_SUCCESS.increment();
            if (profiling) SLICE_NANOS.add(System.nanoTime() - start);
            return true;
        } catch (RuntimeException | LinkageError e) {
            LatticeNative.logFallbackOnce("density_function_grid", e.getMessage());
            return false;
        }
    }

    public static boolean tryFillSliceRows(List<NoiseChunk.NoiseInterpolator> interpolators,
                                           Object arenaKey,
                                           boolean isSlice0,
                                           double x,
                                           double y0,
                                           double z0,
                                           double dy,
                                           int cellX,
                                           int firstCellZ,
                                           int cellWidth,
                                           int yRows,
                                           int zRows) {
        logStatusOnce();
        if (!ENABLED || interpolators == null || interpolators.isEmpty()) return false;
        LastSliceRowsReject previousReject = LAST_SLICE_ROWS_REJECT.get();
        if (previousReject != null && previousReject.interpolators() == interpolators
                && previousReject.epoch() == COMPILE_CACHE_EPOCH) return false;
        boolean stats = STATS_ENABLED;
        boolean profiling = PROFILING_ENABLED;
        SliceBatchBuffers buffers = SLICE_BATCH_BUFFERS.get();
        buffers.ensureCapacity(interpolators.size());
        double[][] flatOutputs = buffers.flatOutputs;
        NativeNoiseInterpolatorAccess[] accesses = buffers.accesses;
        SliceBatchCompilation batch = buffers.compilation(interpolators, arenaKey);
        if (batch == null) {
            rejectSliceRows(interpolators);
            return false;
        }
        trackExecutionStatsCache(batch.cacheHandle);
        int count = interpolators.size();
        int required = yRows * zRows;
        int index = 0;
        for (NoiseChunk.NoiseInterpolator interpolator : interpolators) {
            NativeNoiseInterpolatorAccess access = (NativeNoiseInterpolatorAccess) (Object) interpolator;
            double[] flat = isSlice0 ? access.lattice$flatSlice0() : access.lattice$flatSlice1();
            if (flat.length < required) {
                rejectSliceRows(interpolators);
                return false;
            }
            flatOutputs[index] = flat;
            accesses[index] = access;
            index++;
        }
        if (stats) {
            long evaluations = (long) count * zRows;
            SLICE_ATTEMPTS.add(evaluations);
            SLICE_BATCH_CALLS.increment();
            SLICE_BATCH_FUNCTIONS.add(evaluations);
        }
        long start = profiling ? System.nanoTime() : 0L;
        try {
            if (!evaluateYColumnRootsFlatRows(buffers, batch, count, isSlice0, x, y0, z0, dy, cellX, firstCellZ, cellWidth, yRows, zRows, flatOutputs)) {
                return false;
            }
            for (int i = 0; i < count; i++) {
                accesses[i].lattice$markFlatSliceReadable();
            }
            if (stats) SLICE_SUCCESS.add((long) count * zRows);
            if (profiling) SLICE_NANOS.add(System.nanoTime() - start);
            return true;
        } catch (RuntimeException | LinkageError e) {
            LatticeNative.logFallbackOnce("density_function_y_columns_rows", e.getMessage());
            return false;
        }
    }

    public static boolean tryFillGrid(double[] values,
                                      DensityFunction function,
                                      double x0,
                                      double y0,
                                      double z0,
                                      double dx,
                                      double dy,
                                      double dz,
                                      int cellX0,
                                      int cellZ0,
                                      int nx,
                                      int ny,
                                      int nz) {
        if (!GRID_ENABLED) return false;
        logStatusOnce();
        if (!ENABLED || values == null || function == null || nx <= 0 || ny <= 0 || nz <= 0) return false;
        if (bypassRootNative(function)) return false;
        long required = (long)nx * ny * nz;
        if (required > values.length) return false;
        boolean stats = STATS_ENABLED;
        boolean profiling = PROFILING_ENABLED;
        if (stats) GRID_ATTEMPTS.increment();
        long start = profiling ? System.nanoTime() : 0L;
        NativeDensityFunction compiled = tryCompile(function);
        if (compiled != null) trackExecutionStatsCache(compiled.cacheHandle);
        // Grid evaluation supplies complete coordinates and advances cell X/Z for
        // every sample, so CacheOnce/Cache2D/FlatCache remain correctly keyed.
        // Interpolators still require NoiseChunk's live interpolation state.
        if (compiled == null || !compiled.interpolators.isEmpty()) return false;
        try {
            nativeEvaluateGrid(compiled.handle, compiled.cacheHandle, x0, y0, z0, dx, dy, dz, cellX0, cellZ0, nx, ny, nz, values);
            if (stats) GRID_SUCCESS.increment();
            if (profiling) GRID_NANOS.add(System.nanoTime() - start);
            return true;
        } catch (RuntimeException | LinkageError e) {
            LatticeNative.logFallbackOnce("density_function_flat_grid", e.getMessage());
            return false;
        }
    }

    public static boolean tryFillGridRoots(double[] values,
                                           DensityFunction[] functions,
                                           Object arenaKey,
                                           double x0,
                                           double y0,
                                           double z0,
                                           double dx,
                                           double dy,
                                           double dz,
                                           int cellX0,
                                           int cellZ0,
                                           int nx,
                                           int ny,
                                           int nz) {
        logStatusOnce();
        if (!ENABLED || !CLIMATE_BATCH_ENABLED || !GRID_ROOTS_NATIVE_AVAILABLE
                || values == null || functions == null || functions.length == 0
                || nx <= 0 || ny <= 0 || nz <= 0) return false;
        long rootStride = (long) nx * ny * nz;
        if (rootStride > Integer.MAX_VALUE || rootStride * functions.length > values.length) return false;

        boolean stats = STATS_ENABLED;
        boolean profiling = PROFILING_ENABLED;
        if (stats) CLIMATE_BATCH_ATTEMPTS.increment();
        long start = profiling ? System.nanoTime() : 0L;
        ClimateBatchCompilation batch = CLIMATE_BATCH_BUFFERS.get().compilation(functions, arenaKey);
        if (batch == null) return false;
        trackExecutionStatsCache(batch.cacheHandle);
        try {
            nativeEvaluateGridRoots(
                    batch.handle, batch.cacheHandle, batch.roots, functions.length,
                    x0, y0, z0, dx, dy, dz, cellX0, cellZ0, nx, ny, nz, values);
            if (stats) CLIMATE_BATCH_SUCCESS.increment();
            if (profiling) CLIMATE_BATCH_NANOS.add(System.nanoTime() - start);
            return true;
        } catch (UnsatisfiedLinkError | NoSuchMethodError e) {
            GRID_ROOTS_NATIVE_AVAILABLE = false;
            LatticeNative.logFallbackOnce("density_function_grid_roots_symbol", e.getMessage());
            return false;
        } catch (RuntimeException | LinkageError e) {
            LatticeNative.logFallbackOnce("density_function_grid_roots", e.getMessage());
            return false;
        }
    }

    public static boolean tryFillSlices(List<NoiseChunk.NoiseInterpolator> interpolators,
                                        DensityFunction.ContextProvider contextProvider,
                                        boolean isSlice0,
                                        int zRow,
                                        double x,
                                        double y0,
                                        double z,
                                        double dy,
                                        int cellX,
                                        int cellZ,
                                        int yRows,
                                        int zRows) {
        logStatusOnce();
        if (!FIRST_SLICE_LOGGED.get() && FIRST_SLICE_LOGGED.compareAndSet(false, true)) {
            LOGGER.info("NativeDensityFunction first slice attempt");
        }
        if (!ENABLED || interpolators == null || interpolators.isEmpty()) return false;
        boolean stats = STATS_ENABLED;
        boolean profiling = PROFILING_ENABLED;
        boolean checkParity = shouldCheckParity();
        int size = interpolators.size();
        SliceBatchBuffers buffers = SLICE_BATCH_BUFFERS.get();
        buffers.ensureCapacity(size);
        long[] handles = buffers.handles;
        long[] cacheHandles = buffers.cacheHandles;
        double[][] flatOutputs = buffers.flatOutputs;
        NativeNoiseInterpolatorAccess[] accesses = buffers.accesses;
        NoiseChunk.NoiseInterpolator[] nativeInterpolators = buffers.nativeInterpolators;
        NoiseChunk.NoiseInterpolator[] javaInterpolators = buffers.javaInterpolators;
        double[][] javaOutputs = buffers.javaOutputs;
        NativeNoiseInterpolatorAccess[] javaAccesses = buffers.javaAccesses;
        int count = 0;
        int javaCount = 0;
        for (NoiseChunk.NoiseInterpolator interpolator : interpolators) {
            DensityFunction function = interpolator.wrapped();
            NativeNoiseInterpolatorAccess access = (NativeNoiseInterpolatorAccess) (Object) interpolator;
            if (bypassRootNative(function)) {
                double[] values = access.lattice$sliceRow(isSlice0, zRow);
                if (values == null || values.length < yRows) return false;
                javaInterpolators[javaCount] = interpolator;
                javaOutputs[javaCount] = values;
                javaAccesses[javaCount] = access;
                javaCount++;
                continue;
            }
            NativeDensityFunction compiled = tryCompile(function);
            if (compiled == null) {
                double[] values = access.lattice$sliceRow(isSlice0, zRow);
                if (values == null || values.length < yRows) return false;
                javaInterpolators[javaCount] = interpolator;
                javaOutputs[javaCount] = values;
                javaAccesses[javaCount] = access;
                javaCount++;
                continue;
            }
            trackExecutionStatsCache(compiled.cacheHandle);
            handles[count] = compiled.handle;
            cacheHandles[count] = compiled.cacheHandle;
            double[] flat = isSlice0 ? access.lattice$flatSlice0() : access.lattice$flatSlice1();
            if (flat.length < yRows * zRows) return false;
            flatOutputs[count] = flat;
            accesses[count] = access;
            nativeInterpolators[count] = interpolator;
            count++;
        }
        if (count == 0) return false;
        if (stats) {
            SLICE_ATTEMPTS.add(count);
            SLICE_BATCH_CALLS.increment();
            SLICE_BATCH_FUNCTIONS.add(count);
        }
        long start = profiling ? System.nanoTime() : 0L;
        try {
            int flatOffset = zRow * yRows;
            if (evaluateYColumnsFlat(handles, cacheHandles, count, x, y0, z, dy, cellX, cellZ, yRows, flatOutputs, flatOffset)) {
                for (int i = 0; i < count; i++) {
                    accesses[i].lattice$markFlatSliceReadable();
                }
            } else {
                buffers.ensurePackedCapacity(count * yRows);
                if (evaluateYColumnsPacked(handles, cacheHandles, count, x, y0, z, dy, cellX, cellZ, yRows, buffers.packedOutputs)) {
                    for (int i = 0; i < count; i++) {
                        accesses[i].lattice$copyPackedFlatRow(isSlice0, zRow, buffers.packedOutputs, i * yRows, yRows, zRows);
                    }
                } else {
                    for (int i = 0; i < count; i++) {
                        double[] values = accesses[i].lattice$sliceRow(isSlice0, zRow);
                        if (values == null || values.length < yRows) return false;
                        evaluateYColumn(handles[i], cacheHandles[i], x, y0, z, dy, cellX, cellZ, yRows, values);
                        accesses[i].lattice$copyFlatRow(isSlice0, zRow, values, yRows, zRows);
                    }
                }
            }
            for (int i = 0; i < javaCount; i++) {
                javaInterpolators[i].fillArray(javaOutputs[i], contextProvider);
                javaAccesses[i].lattice$copyFlatRow(isSlice0, zRow, javaOutputs[i], yRows, zRows);
            }
            if (checkParity) {
                buffers.ensurePackedCapacity(count * yRows);
                for (int i = 0; i < count; i++) {
                    double[] javaValues = accesses[i].lattice$sliceRow(isSlice0, zRow);
                    if (javaValues == null || javaValues.length < yRows) return false;
                    System.arraycopy(flatOutputs[i], flatOffset, buffers.packedOutputs, i * yRows, yRows);
                    nativeInterpolators[i].fillArray(javaValues, contextProvider);
                    recordParitySliceRow("sliceBatchFlat", nativeInterpolators[i].wrapped(), buffers.packedOutputs, i * yRows, javaValues, yRows);
                }
            }
            if (stats) SLICE_SUCCESS.add(count);
            if (profiling) SLICE_NANOS.add(System.nanoTime() - start);
            return true;
        } catch (RuntimeException | LinkageError e) {
            LatticeNative.logFallbackOnce("density_function_y_columns", e.getMessage());
            return false;
        }
    }

    private static void evaluateYColumn(long handle,
                                        long cacheHandle,
                                        double x,
                                        double y0,
                                        double z,
                                        double dy,
                                        int cellX,
                                        int cellZ,
                                        int ny,
                                        double[] out) {
        if (Y_COLUMN_NATIVE_AVAILABLE) {
            try {
                nativeEvaluateYColumn(handle, cacheHandle, x, y0, z, dy, cellX, cellZ, ny, out);
                return;
            } catch (UnsatisfiedLinkError | NoSuchMethodError e) {
                Y_COLUMN_NATIVE_AVAILABLE = false;
                LatticeNative.logFallbackOnce("density_function_y_column_symbol", e.getMessage());
            }
        }
        nativeClearCache(cacheHandle);
        nativeEvaluateGrid(handle, cacheHandle, x, y0, z, 1.0, dy, 1.0, cellX, cellZ, 1, ny, 1, out);
    }

    private static boolean evaluateYColumnsPacked(long[] handles,
                                                  long[] cacheHandles,
                                                  int count,
                                                  double x,
                                                  double y0,
                                                  double z,
                                                  double dy,
                                                  int cellX,
                                                  int cellZ,
                                                  int ny,
                                                  double[] packedOut) {
        if (Y_COLUMNS_PACKED_NATIVE_AVAILABLE && packedOut.length >= count * ny) {
            try {
                nativeEvaluateYColumnsPacked(handles, cacheHandles, count, x, y0, z, dy, cellX, cellZ, ny, packedOut);
                return true;
            } catch (UnsatisfiedLinkError | NoSuchMethodError e) {
                Y_COLUMNS_PACKED_NATIVE_AVAILABLE = false;
                LatticeNative.logFallbackOnce("density_function_y_columns_packed_symbol", e.getMessage());
            }
        }
        return false;
    }

    private static boolean evaluateYColumnsFlat(long[] handles,
                                                long[] cacheHandles,
                                                int count,
                                                double x,
                                                double y0,
                                                double z,
                                                double dy,
                                                int cellX,
                                                int cellZ,
                                                int ny,
                                                double[][] out,
                                                int outputOffset) {
        if (!Y_COLUMNS_FLAT_NATIVE_AVAILABLE) return false;
        try {
            nativeEvaluateYColumnsFlat(handles, cacheHandles, count, x, y0, z, dy, cellX, cellZ, ny, out, outputOffset);
            return true;
        } catch (UnsatisfiedLinkError | NoSuchMethodError e) {
            Y_COLUMNS_FLAT_NATIVE_AVAILABLE = false;
            LatticeNative.logFallbackOnce("density_function_y_columns_flat_symbol", e.getMessage());
            return false;
        }
    }

    private static boolean evaluateYColumnsFlatRows(long[] handles,
                                                    long[] cacheHandles,
                                                    int count,
                                                    double x,
                                                    double y0,
                                                    double z0,
                                                    double dy,
                                                    int cellX,
                                                    int firstCellZ,
                                                    int cellWidth,
                                                    int yRows,
                                                    int zRows,
                                                    double[][] out) {
        if (!Y_COLUMNS_FLAT_ROWS_NATIVE_AVAILABLE) return false;
        try {
            nativeEvaluateYColumnsFlatRows(
                    handles, cacheHandles, count, x, y0, z0, dy,
                    cellX, firstCellZ, cellWidth, yRows, zRows, out);
            return true;
        } catch (UnsatisfiedLinkError | NoSuchMethodError e) {
            Y_COLUMNS_FLAT_ROWS_NATIVE_AVAILABLE = false;
            LatticeNative.logFallbackOnce("density_function_y_columns_flat_rows_symbol", e.getMessage());
            return false;
        }
    }

    private static boolean evaluateYColumnRootsFlatRows(SliceBatchBuffers buffers,
                                                        SliceBatchCompilation batch,
                                                        int count,
                                                        boolean isSlice0,
                                                        double x,
                                                        double y0,
                                                        double z0,
                                                        double dy,
                                                        int cellX,
                                                        int firstCellZ,
                                                        int cellWidth,
                                                        int yRows,
                                                        int zRows,
                                                        double[][] out) {
        if (!Y_COLUMN_ROOTS_FLAT_ROWS_NATIVE_AVAILABLE) return false;
        try {
            int boundSlot = buffers.boundSliceOutputSlot(
                    batch, count, isSlice0 ? 0 : 1, out);
            if (boundSlot >= 0 && Y_COLUMN_ROOTS_FLAT_ROWS_BOUND_NATIVE_AVAILABLE) {
                try {
                    nativeEvaluateYColumnRootsFlatRowsBound(
                            batch.handle, batch.cacheHandle, boundSlot, count,
                            x, y0, z0, dy, cellX, firstCellZ, cellWidth, yRows, zRows);
                    return true;
                } catch (UnsatisfiedLinkError | NoSuchMethodError e) {
                    Y_COLUMN_ROOTS_FLAT_ROWS_BOUND_NATIVE_AVAILABLE = false;
                    LatticeNative.logFallbackOnce("density_function_y_column_roots_flat_rows_bound_symbol", e.getMessage());
                } catch (RuntimeException | LinkageError e) {
                    LatticeNative.logFallbackOnce("density_function_y_column_roots_flat_rows_bound", e.getMessage());
                }
            }
            if (Y_COLUMN_ROOTS_FLAT_ROWS_FAST_NATIVE_AVAILABLE) {
                try {
                    nativeEvaluateYColumnRootsFlatRowsFast(
                            batch.handle, batch.cacheHandle, count,
                            x, y0, z0, dy, cellX, firstCellZ, cellWidth, yRows, zRows, out);
                    return true;
                } catch (UnsatisfiedLinkError | NoSuchMethodError e) {
                    Y_COLUMN_ROOTS_FLAT_ROWS_FAST_NATIVE_AVAILABLE = false;
                    LatticeNative.logFallbackOnce("density_function_y_column_roots_flat_rows_fast_symbol", e.getMessage());
                }
            }
            nativeEvaluateYColumnRootsFlatRows(
                    batch.handle, batch.cacheHandle, batch.roots, count,
                    x, y0, z0, dy, cellX, firstCellZ, cellWidth, yRows, zRows, out);
            return true;
        } catch (UnsatisfiedLinkError | NoSuchMethodError e) {
            Y_COLUMN_ROOTS_FLAT_ROWS_NATIVE_AVAILABLE = false;
            LatticeNative.logFallbackOnce("density_function_y_column_roots_flat_rows_symbol", e.getMessage());
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
        if (!DIRECT_CELL_ENABLED) return false;
        return tryFillCell(values, function, cellStartBlockX, cellStartBlockY, cellStartBlockZ, cellWidth, cellHeight, cellCountXZ, cellCountY, cellX, cellZ, localCellY, localCellZ, false);
    }

    public static boolean shouldTryFillCellDirect() {
        return ENABLED && DIRECT_CELL_ENABLED;
    }

    public static boolean shouldTryFillCellColumnDirect() {
        return ENABLED && DIRECT_CELL_ENABLED && DIRECT_CELL_COLUMN_ENABLED;
    }

    public static double[] acquireDirectCellColumnBuffer(int requiredLength) {
        ArrayDeque<double[]> pool = DIRECT_CELL_COLUMN_POOL.get();
        for (java.util.Iterator<double[]> iterator = pool.iterator(); iterator.hasNext();) {
            double[] candidate = iterator.next();
            if (candidate.length >= requiredLength) {
                iterator.remove();
                return candidate;
            }
        }
        return new double[requiredLength];
    }

    public static void releaseDirectCellColumnBuffer(double[] buffer) {
        if (buffer == null || buffer.length > MAX_POOLED_DIRECT_CELL_COLUMN_LENGTH) return;
        ArrayDeque<double[]> pool = DIRECT_CELL_COLUMN_POOL.get();
        if (pool.size() < MAX_DIRECT_CELL_COLUMN_POOL_ENTRIES) pool.addFirst(buffer);
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
        return tryFillCellColumn(values, function, cellStartBlockX, firstCellZ, cellNoiseMinY,
                cellWidth, cellHeight, cellCountXZ, cellCountY, cellX, false);
    }

    public static boolean tryFillCellColumnDirect(double[] values,
                                                  DensityFunction function,
                                                  int cellStartBlockX,
                                                  int firstCellZ,
                                                  int cellNoiseMinY,
                                                  int cellWidth,
                                                  int cellHeight,
                                                  int cellCountXZ,
                                                  int cellCountY,
                                                  int cellX) {
        return tryFillCellColumn(values, function, cellStartBlockX, firstCellZ, cellNoiseMinY,
                cellWidth, cellHeight, cellCountXZ, cellCountY, cellX, true);
    }

    private static boolean tryFillCellColumn(double[] values,
                                             DensityFunction function,
                                             int cellStartBlockX,
                                             int firstCellZ,
                                             int cellNoiseMinY,
                                             int cellWidth,
                                             int cellHeight,
                                             int cellCountXZ,
                                             int cellCountY,
                                             int cellX,
                                             boolean direct) {
        logStatusOnce();
        if (!ENABLED || !CELL_ENABLED) return false;
        if (bypassRootNative(function)) return false;
        if (bypassCellNative(function)) return false;
        boolean profiling = PROFILING_ENABLED;
        long start = profiling ? System.nanoTime() : 0L;
        NativeDensityFunction compiled = direct ? tryCompileDirect(function) : tryCompileCell(function);
        if (compiled == null) return false;
        trackExecutionStatsCache(compiled.cacheHandle);
        if (compiled.clearsCachePerCell && !direct) return false;
        int cellValueCount = cellWidth * cellHeight * cellWidth;
        int expected = cellCountXZ * cellCountY * cellValueCount;
        if (values.length < expected) return false;
        double[][] cacheColumns = direct ? compiled.cacheAllInCellColumns(cellX, expected) : null;
        if (direct && compiled.cacheAllInCellValues != null && cacheColumns == null) return false;

        try {
            compiled.syncInterpolatorColumn(cellStartBlockX, cellCountXZ, cellCountY);
            if (!evaluateInterpolatedColumn(
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
                    compiled.clearsCachePerCell,
                    cacheColumns,
                    values)) {
                return false;
            }
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

    public static int fillCellColumns(List<?> cellCaches,
                                      DensityFunction.ContextProvider contextProvider,
                                      Object arenaKey,
                                      int cellStartBlockX,
                                      int firstCellZ,
                                      int cellNoiseMinY,
                                      int cellWidth,
                                      int cellHeight,
                                      int cellCountXZ,
                                      int cellCountY,
                                      int cellX,
                                      int cellOffset) {
        logStatusOnce();
        if (!ENABLED || !CELL_ENABLED || cellCaches == null || cellCaches.isEmpty()) return CELL_COLUMNS_UNHANDLED;
        if (PARITY_ENABLED) return CELL_COLUMNS_UNHANDLED;
        if (arenaKey != null) {
            synchronized (CELL_COLUMN_JAVA_ONLY_ARENAS) {
                if (CELL_COLUMN_JAVA_ONLY_ARENAS.containsKey(arenaKey)) {
                    if (STATS_ENABLED) COLUMN_JAVA_ONLY_BYPASSES.increment();
                    return CELL_COLUMNS_KNOWN_JAVA_ONLY;
                }
            }
        }
        int cellValueCount = cellWidth * cellHeight * cellWidth;
        int columnValueCount = cellCountXZ * cellCountY * cellValueCount;
        ColumnBatchBuffers buffers = COLUMN_BATCH_BUFFERS.get();
        buffers.ensureCapacity(cellCaches.size());
        long[] handles = buffers.handles;
        long[] cacheHandles = buffers.cacheHandles;
        double[][] outputs = buffers.outputs;
        NativeCacheAllInCellAccess[] nativeAccesses = buffers.nativeAccesses;
        NativeCacheAllInCellAccess[] javaAccesses = buffers.javaAccesses;
        int count = 0;
        int javaCount = 0;

        for (Object cache : cellCaches) {
            NativeCacheAllInCellAccess access = (NativeCacheAllInCellAccess) cache;
            double[] column = access.lattice$columnValues();
            if (access.lattice$columnCellX() == cellX && column != null && column.length >= columnValueCount) {
                continue;
            }

            DensityFunction function = access.lattice$noiseFiller();
            if (bypassRootNative(function) || bypassCellNative(function)) {
                javaAccesses[javaCount++] = access;
                continue;
            }
            NativeDensityFunction compiled = tryCompileCell(function);
            if (compiled == null || compiled.clearsCachePerCell) {
                javaAccesses[javaCount++] = access;
                continue;
            }
            trackExecutionStatsCache(compiled.cacheHandle);
            if (column == null || column.length < columnValueCount) {
                releaseDirectCellColumnBuffer(column);
                column = acquireDirectCellColumnBuffer(columnValueCount);
                access.lattice$setColumnValues(column);
            }
            compiled.syncInterpolatorColumn(cellStartBlockX, cellCountXZ, cellCountY);
            handles[count] = compiled.handle;
            cacheHandles[count] = compiled.cacheHandle;
            outputs[count] = column;
            nativeAccesses[count] = access;
            count++;
        }

        boolean profiling = PROFILING_ENABLED;
        long start = profiling ? System.nanoTime() : 0L;
        boolean nativeColumns = false;
        try {
            if (count > 0) {
                boolean filledColumns = evaluateInterpolatedColumns(
                        handles,
                        cacheHandles,
                        count,
                        cellStartBlockX,
                        firstCellZ * cellWidth,
                        cellNoiseMinY * cellHeight,
                        cellX,
                        firstCellZ,
                        cellWidth,
                        cellHeight,
                        cellCountXZ,
                        cellCountY,
                        outputs);
                if (filledColumns) {
                    nativeColumns = true;
                    for (int i = 0; i < count; i++) {
                        nativeAccesses[i].lattice$setColumnCellX(cellX);
                    }
                    if (STATS_ENABLED) {
                        COLUMN_BATCH_CALLS.increment();
                        COLUMN_BATCH_FUNCTIONS.add(count);
                    }
                } else {
                    for (int i = 0; i < count; i++) {
                        javaAccesses[javaCount++] = nativeAccesses[i];
                    }
                }
            }

            for (int i = 0; i < javaCount; i++) {
                NativeCacheAllInCellAccess access = javaAccesses[i];
                access.lattice$noiseFiller().fillArray(access.lattice$values(), contextProvider);
            }

            for (Object cache : cellCaches) {
                NativeCacheAllInCellAccess access = (NativeCacheAllInCellAccess) cache;
                double[] column = access.lattice$columnValues();
                if (access.lattice$columnCellX() == cellX && column != null && column.length >= columnValueCount) {
                    System.arraycopy(column, cellOffset, access.lattice$values(), 0, cellValueCount);
                }
            }
            if (profiling && count > 0) {
                COLUMN_NANOS.add(System.nanoTime() - start);
                COLUMN_COUNT.add(count);
            }
            if (!nativeColumns) {
                if (STATS_ENABLED) COLUMN_JAVA_ONLY_BATCHES.increment();
                if (count == 0 && arenaKey != null) {
                    synchronized (CELL_COLUMN_JAVA_ONLY_ARENAS) {
                        CELL_COLUMN_JAVA_ONLY_ARENAS.put(arenaKey, Boolean.TRUE);
                    }
                }
            }
            return nativeColumns ? 1 : 0;
        } catch (RuntimeException | LinkageError e) {
            LatticeNative.logFallbackOnce("density_function_cell_columns", e.getMessage());
            return CELL_COLUMNS_UNHANDLED;
        }
    }

    private static boolean evaluateInterpolatedColumn(long handle,
                                                      long cacheHandle,
                                                      double x0,
                                                      double z0,
                                                      double yMin,
                                                      int cellX,
                                                      int firstCellZ,
                                                      int cellWidth,
                                                      int cellHeight,
                                                      int cellCountXZ,
                                                      int cellCountY,
                                                      boolean clearPerCell,
                                                      double[][] cacheAllInCellValues,
                                                      double[] out) {
        if (!INTERPOLATED_COLUMN_NATIVE_AVAILABLE) return false;
        try {
            nativeEvaluateInterpolatedColumn(handle, cacheHandle, x0, z0, yMin, cellX, firstCellZ, cellWidth, cellHeight, cellCountXZ, cellCountY, clearPerCell, cacheAllInCellValues, out);
            return true;
        } catch (UnsatisfiedLinkError | NoSuchMethodError e) {
            INTERPOLATED_COLUMN_NATIVE_AVAILABLE = false;
            LatticeNative.logFallbackOnce("density_function_cell_column_symbol", e.getMessage());
            return false;
        }
    }

    private static boolean evaluateInterpolatedColumns(long[] handles,
                                                       long[] cacheHandles,
                                                       int count,
                                                       double x0,
                                                       double z0,
                                                       double yMin,
                                                       int cellX,
                                                       int firstCellZ,
                                                       int cellWidth,
                                                       int cellHeight,
                                                       int cellCountXZ,
                                                       int cellCountY,
                                                       double[][] out) {
        if (INTERPOLATED_COLUMNS_NATIVE_AVAILABLE) {
            try {
                nativeEvaluateInterpolatedColumns(handles, cacheHandles, count, x0, z0, yMin, cellX, firstCellZ, cellWidth, cellHeight, cellCountXZ, cellCountY, out);
                return true;
            } catch (UnsatisfiedLinkError | NoSuchMethodError e) {
                INTERPOLATED_COLUMNS_NATIVE_AVAILABLE = false;
                LatticeNative.logFallbackOnce("density_function_cell_columns_symbol", e.getMessage());
            }
        }
        for (int i = 0; i < count; i++) {
            if (!evaluateInterpolatedColumn(handles[i], cacheHandles[i], x0, z0, yMin, cellX, firstCellZ, cellWidth, cellHeight, cellCountXZ, cellCountY, false, null, out[i])) {
                return false;
            }
        }
        return true;
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
        trackExecutionStatsCache(compiled.cacheHandle);
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
            boolean interpolated = highLevel || !compiled.interpolators.isEmpty();
            if (interpolated) {
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
        ThreadCompileCache threadCache = threadCompileCache();
        Object threadCached = threadCache.get(function);
        if (threadCached != THREAD_COMPILE_CACHE_MISS) return threadCached == FAILED_COMPILE_SENTINEL ? null : (NativeDensityFunction) threadCached;
        LastCompile last = LAST_COMPILE.get();
        if (last != null && last.function() == function) {
            putThreadCompileCache(threadCache, function, compileCacheValue(last.compiled()));
            return last.compiled();
        }
        synchronized (CACHE) {
            if (FAILED_COMPILES.containsKey(function)) {
                LAST_COMPILE.set(new LastCompile(function, null));
                putThreadCompileCache(threadCache, function, FAILED_COMPILE_SENTINEL);
                return null;
            }
        }

        if (STATS_ENABLED) COMPILE_ATTEMPTS.increment();
        long start = PROFILING_ENABLED ? System.nanoTime() : 0L;
        NativeDensityFunction compiled = compileNew(function);
        if (PROFILING_ENABLED) COMPILE_NANOS.add(System.nanoTime() - start);

        synchronized (CACHE) {
            if (FAILED_COMPILES.containsKey(function)) {
                if (compiled != null) compiled.destroyNow();
                LAST_COMPILE.set(new LastCompile(function, null));
                putThreadCompileCache(threadCache, function, FAILED_COMPILE_SENTINEL);
                return null;
            }
            if (compiled != null) {
                if (STATS_ENABLED) COMPILE_SUCCESS.increment();
            } else {
                FAILED_COMPILES.put(function, Boolean.TRUE);
            }
            LAST_COMPILE.set(new LastCompile(function, compiled));
            putThreadCompileCache(threadCache, function, compileCacheValue(compiled));
            return compiled;
        }
    }

    private static NativeDensityFunction tryCompileDirect(DensityFunction function) {
        if (!LatticeNative.isLoaded() || function == null) return null;
        if (DIRECT_CELL_REJECTS.get() > 65536L && CELL_DIRECT_SUCCESS.sum() < 1024L) return null;
        ThreadCompileCache threadCache = threadDirectCompileCache();
        Object threadCached = threadCache.get(function);
        if (threadCached != THREAD_COMPILE_CACHE_MISS) return threadCached == FAILED_COMPILE_SENTINEL ? null : (NativeDensityFunction) threadCached;
        LastCompile last = LAST_DIRECT_COMPILE.get();
        if (last != null && last.function() == function) {
            putThreadCompileCache(threadCache, function, compileCacheValue(last.compiled()));
            return last.compiled();
        }
        synchronized (DIRECT_CACHE) {
            if (FAILED_DIRECT_COMPILES.containsKey(function)) {
                LAST_DIRECT_COMPILE.set(new LastCompile(function, null));
                putThreadCompileCache(threadCache, function, FAILED_COMPILE_SENTINEL);
                return null;
            }
        }

        if (STATS_ENABLED) COMPILE_ATTEMPTS.increment();
        long start = PROFILING_ENABLED ? System.nanoTime() : 0L;
        NativeDensityFunction compiled = compileNew(function, true);
        if (PROFILING_ENABLED) COMPILE_NANOS.add(System.nanoTime() - start);

        synchronized (DIRECT_CACHE) {
            if (FAILED_DIRECT_COMPILES.containsKey(function)) {
                if (compiled != null) compiled.destroyNow();
                LAST_DIRECT_COMPILE.set(new LastCompile(function, null));
                putThreadCompileCache(threadCache, function, FAILED_COMPILE_SENTINEL);
                return null;
            }
            if (compiled != null) {
                if (STATS_ENABLED) COMPILE_SUCCESS.increment();
            } else {
                DIRECT_CELL_REJECTS.incrementAndGet();
                FAILED_DIRECT_COMPILES.put(function, Boolean.TRUE);
            }
            LAST_DIRECT_COMPILE.set(new LastCompile(function, compiled));
            putThreadCompileCache(threadCache, function, compileCacheValue(compiled));
            return compiled;
        }
    }

    private static Object compileCacheValue(NativeDensityFunction compiled) {
        return compiled == null ? FAILED_COMPILE_SENTINEL : compiled;
    }

    private static void putThreadCompileCache(ThreadCompileCache cache,
                                              DensityFunction function,
                                              Object value) {
        cache.put(function, value);
    }

    private static ThreadCompileCache threadCompileCache() {
        ThreadCompileCache cache = THREAD_COMPILE_CACHE.get();
        if (THREAD_COMPILE_CACHE_EPOCH.get() != COMPILE_CACHE_EPOCH) {
            cache.clear();
            THREAD_COMPILE_CACHE_EPOCH.set(COMPILE_CACHE_EPOCH);
        }
        return cache;
    }

    private static ThreadCompileCache threadDirectCompileCache() {
        ThreadCompileCache cache = THREAD_DIRECT_COMPILE_CACHE.get();
        if (THREAD_DIRECT_COMPILE_CACHE_EPOCH.get() != COMPILE_CACHE_EPOCH) {
            cache.clear();
            THREAD_DIRECT_COMPILE_CACHE_EPOCH.set(COMPILE_CACHE_EPOCH);
        }
        return cache;
    }

    private double[][] cacheAllInCellColumns(int cellX, int requiredLength) {
        if (this.cacheAllInCellBindings == null) return null;
        double[][] columns = new double[this.cacheAllInCellBindings.length][];
        for (int i = 0; i < this.cacheAllInCellBindings.length; i++) {
            NativeCacheAllInCellAccess access = this.cacheAllInCellBindings[i];
            if (access == null) continue;
            double[] column = access.lattice$columnValues();
            if (access.lattice$columnCellX() != cellX || column == null || column.length < requiredLength) {
                return null;
            }
            columns[i] = column;
        }
        return columns;
    }

    private static void rejectSliceRows(List<NoiseChunk.NoiseInterpolator> interpolators) {
        LAST_SLICE_ROWS_REJECT.set(new LastSliceRowsReject(interpolators, COMPILE_CACHE_EPOCH));
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
        recordUnsupported(name);
    }

    private static void recordUnsupported(String name) {
        UNSUPPORTED.computeIfAbsent(name, ignored -> new LongAdder()).increment();
    }

    private static NativeDensityFunction compileNew(DensityFunction function) {
        return compileNew(function, false);
    }

    private static NativeDensityFunction compileNew(DensityFunction function, boolean directCell) {
        long handle = 0L;
        long cacheHandle = 0L;
        try {
            handle = createArena();
            if (handle == 0L) {
                recordUnsupported("compile.createArena=0");
                return null;
            }
            Compiler compiler = new Compiler(handle, function, directCell);
            int root = compiler.compile(function);
            if (root < 0) {
                recordUnsupported(function);
                return null;
            }
            if (directCell && !compiler.directCellCandidate()) {
                DIRECT_CELL_CANDIDATE_REJECTS.incrementAndGet();
                recordUnsupported(function);
                return null;
            }
            setRoot(handle, root);
            cacheHandle = createCache(handle);
            if (cacheHandle == 0L) {
                recordUnsupported("compile.createCache=0");
                return null;
            }
            NativeDensityFunction compiled = new NativeDensityFunction(handle, cacheHandle, compiler.interpolators(),
                    compiler.cacheAllInCellValues(), compiler.cacheAllInCellAccesses(), compiler.clearsCachePerCell());
            compiled.bindCacheAllInCellArrays();
            return compiled;
        } catch (RuntimeException | LinkageError e) {
            recordUnsupported("compile.exception." + e.getClass().getName());
            LatticeNative.logFallbackOnce("density_function_compile", e.getMessage());
            return null;
        } finally {
            if (cacheHandle == 0L && handle != 0L) {
                try {
                    destroyArena(handle);
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
                if (handle != 0L) destroyArena(handle);
            } catch (LinkageError ignored) {
            }
        }
    }

    private static long createArena() {
        return nativeCreate();
    }

    private static void destroyArena(long handle) {
        nativeDestroy(handle);
    }

    private static void setRoot(long handle, int root) {
        nativeSetRoot(handle, root);
    }

    private static long createCache(long handle) {
        long cacheHandle = nativeCreateCache(handle);
        if (cacheHandle != 0L && EXECUTION_STATS_ENABLED && enableExecutionStats(cacheHandle)) {
            trackExecutionStatsCache(cacheHandle);
        }
        return cacheHandle;
    }

    private static boolean enableExecutionStats(long cacheHandle) {
        if (!EXECUTION_STATS_NATIVE_AVAILABLE) return false;
        try {
            nativeSetExecutionStatsEnabled(cacheHandle, true);
            return true;
        } catch (UnsatisfiedLinkError | NoSuchMethodError error) {
            EXECUTION_STATS_NATIVE_AVAILABLE = false;
            LatticeNative.logFallbackOnce("density_function_execution_stats_symbol", error.getMessage());
            return false;
        }
    }

    /**
     * Starts one benchmark-owned aggregation window for the current worker.
     * Production evaluation never calls this method, so enabling diagnostics
     * alone does not allocate Java-side aggregation state on the hot path.
     */
    public static void beginExecutionStatsSample() {
        if (!EXECUTION_STATS_ENABLED) return;
        EXECUTION_STATS_SAMPLE.set(new ExecutionStatsSample());
    }

    /**
     * Returns and clears the current worker's aggregation window. Each cache
     * is reset on first observation, so the result describes only this sample.
     */
    public static ExecutionStatsSnapshot finishExecutionStatsSample() {
        if (!EXECUTION_STATS_ENABLED) return ExecutionStatsSnapshot.disabled();
        ExecutionStatsSample sample = EXECUTION_STATS_SAMPLE.get();
        EXECUTION_STATS_SAMPLE.remove();
        return sample == null ? ExecutionStatsSnapshot.empty() : sample.snapshot();
    }

    private static void trackExecutionStatsCache(long cacheHandle) {
        if (!EXECUTION_STATS_ENABLED || !EXECUTION_STATS_NATIVE_AVAILABLE || cacheHandle == 0L) return;
        ExecutionStatsSample sample = EXECUTION_STATS_SAMPLE.get();
        if (sample != null) sample.track(cacheHandle);
    }

    public static final class ExecutionStatsSnapshot {
        private static final ExecutionStatsSnapshot DISABLED = new ExecutionStatsSnapshot(false, 0L, new long[EXECUTION_STATS_LONGS]);
        private static final ExecutionStatsSnapshot EMPTY = new ExecutionStatsSnapshot(true, 0L, new long[EXECUTION_STATS_LONGS]);
        private final boolean enabled;
        private final long cacheCount;
        private final long[] values;

        private ExecutionStatsSnapshot(boolean enabled, long cacheCount, long[] values) {
            this.enabled = enabled;
            this.cacheCount = cacheCount;
            this.values = values;
        }

        public static ExecutionStatsSnapshot disabled() {
            return DISABLED;
        }

        public static ExecutionStatsSnapshot empty() {
            return EMPTY;
        }

        public ExecutionStatsSnapshot plus(ExecutionStatsSnapshot other) {
            if (!this.enabled) return other;
            if (!other.enabled) return this;
            long[] combined = this.values.clone();
            for (int index = 0; index < combined.length; index++) combined[index] += other.values[index];
            combined[6] = Math.max(this.values[6], other.values[6]);
            combined[7] = Math.max(this.values[7], other.values[7]);
            return new ExecutionStatsSnapshot(true, this.cacheCount + other.cacheCount, combined);
        }

        public String benchmarkFields() {
            if (!enabled) return "executionStats=disabled";
            return "executionStats=enabled"
                    + " executionCaches=" + cacheCount
                    + " executionColumnCalls=" + values[0]
                    + " executionAvx2Success=" + values[1]
                    + " executionGenericSuccess=" + values[2]
                    + " executionPointFallback=" + values[3]
                    + " executionCacheClears=" + values[4]
                    + " executionScratchLeases=" + values[5]
                    + " executionScratchPeakDepth=" + values[6]
                    + " executionScratchPeakLeaseBytes=" + values[7]
                    + " executionRangeAllIn=" + values[8]
                    + " executionRangeAllOut=" + values[9]
                    + " executionRangeMixed=" + values[10]
                    + " executionAvx2Rejects=" + nodeKindCounts(EXECUTION_STATS_HEADER_LONGS)
                    + " executionGenericRejects=" + nodeKindCounts(EXECUTION_STATS_HEADER_LONGS + EXECUTION_NODE_KINDS.length);
        }

        private String nodeKindCounts(int offset) {
            StringBuilder result = new StringBuilder("{");
            boolean first = true;
            for (int index = 0; index < EXECUTION_NODE_KINDS.length; index++) {
                long count = values[offset + index];
                if (count == 0L) continue;
                if (!first) result.append(',');
                result.append(EXECUTION_NODE_KINDS[index]).append('=').append(count);
                first = false;
            }
            return result.append('}').toString();
        }
    }

    private static final class ExecutionStatsSample {
        private long[] cacheHandles = new long[4];
        private int size;

        private void track(long cacheHandle) {
            for (int index = 0; index < size; index++) {
                if (cacheHandles[index] == cacheHandle) return;
            }
            if (size == cacheHandles.length) cacheHandles = Arrays.copyOf(cacheHandles, size * 2);
            try {
                nativeResetExecutionStats(cacheHandle);
                cacheHandles[size++] = cacheHandle;
            } catch (UnsatisfiedLinkError | NoSuchMethodError error) {
                EXECUTION_STATS_NATIVE_AVAILABLE = false;
                LatticeNative.logFallbackOnce("density_function_execution_stats_symbol", error.getMessage());
            }
        }

        private ExecutionStatsSnapshot snapshot() {
            if (!EXECUTION_STATS_NATIVE_AVAILABLE) return ExecutionStatsSnapshot.disabled();
            long[] totals = new long[EXECUTION_STATS_LONGS];
            long countedCaches = 0L;
            try {
                for (int index = 0; index < size; index++) {
                    long[] values = nativeGetExecutionStats(cacheHandles[index]);
                    if (values == null || values.length != EXECUTION_STATS_LONGS) {
                        throw new IllegalStateException("Unexpected native density execution stats layout");
                    }
                    long priorDepth = totals[6];
                    long priorBytes = totals[7];
                    for (int field = 0; field < totals.length; field++) totals[field] += values[field];
                    totals[6] = Math.max(priorDepth, values[6]);
                    totals[7] = Math.max(priorBytes, values[7]);
                    countedCaches++;
                }
                return new ExecutionStatsSnapshot(true, countedCaches, totals);
            } catch (UnsatisfiedLinkError | NoSuchMethodError error) {
                EXECUTION_STATS_NATIVE_AVAILABLE = false;
                LatticeNative.logFallbackOnce("density_function_execution_stats_symbol", error.getMessage());
                return ExecutionStatsSnapshot.disabled();
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
            case "directCellColumn" -> DIRECT_CELL_COLUMN_ENABLED = value;
            case "shiftedNoise" -> SHIFTED_NOISE_ENABLED = value;
            case "spline" -> SPLINE_ENABLED = value;
            case "multipointSpline" -> MULTIPOINT_SPLINE_ENABLED = value;
            case "climateBatch" -> CLIMATE_BATCH_ENABLED = value;
            case "stats" -> STATS_ENABLED = value;
            case "executionStats" -> EXECUTION_STATS_ENABLED = value;
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
                + " gridEnabled=" + GRID_ENABLED
                + " cell=" + CELL_ENABLED
                + " directCell=" + DIRECT_CELL_ENABLED
                + " directCellColumn=" + DIRECT_CELL_COLUMN_ENABLED
                + " shiftedNoise=" + SHIFTED_NOISE_ENABLED
                + " spline=" + SPLINE_ENABLED
                + " multipointSpline=" + MULTIPOINT_SPLINE_ENABLED
                + " climateBatch=" + CLIMATE_BATCH_ENABLED
                + " stats=" + STATS_ENABLED
                + " executionStats=" + EXECUTION_STATS_ENABLED
                + " profiling=" + PROFILING_ENABLED
                + " parity=" + PARITY_ENABLED
                + " parityInterval=" + PARITY_INTERVAL
                + NativeWorldgenToggle.status()
                + " compile=" + COMPILE_SUCCESS.sum() + '/' + COMPILE_ATTEMPTS.sum()
                + " slice=" + SLICE_SUCCESS.sum() + '/' + SLICE_ATTEMPTS.sum()
                + " sliceBatch=" + SLICE_BATCH_CALLS.sum() + '/' + SLICE_BATCH_FUNCTIONS.sum()
                + " grid=" + GRID_SUCCESS.sum() + '/' + GRID_ATTEMPTS.sum()
                + " climateBatch=" + CLIMATE_BATCH_SUCCESS.sum() + '/' + CLIMATE_BATCH_ATTEMPTS.sum()
                + " sharedLeaf=" + SHARED_LEAF_MARKS.sum()
                + " sliceTemplate=" + SLICE_TEMPLATE_HITS.sum() + '/' + SLICE_TEMPLATE_MISSES.sum()
                + " climateTemplate=" + CLIMATE_TEMPLATE_HITS.sum() + '/' + CLIMATE_TEMPLATE_MISSES.sum()
                + " columnBatch=" + COLUMN_BATCH_CALLS.sum() + '/' + COLUMN_BATCH_FUNCTIONS.sum()
                + " columnJavaOnly=" + COLUMN_JAVA_ONLY_BATCHES.sum() + '/' + COLUMN_JAVA_ONLY_BYPASSES.sum()
                + " cell=" + CELL_SUCCESS.sum() + '/' + CELL_ATTEMPTS.sum()
                + " cellDirect=" + CELL_DIRECT_SUCCESS.sum() + '/' + CELL_DIRECT_ATTEMPTS.sum()
                + " cellHigh=" + CELL_HIGH_SUCCESS.sum() + '/' + CELL_HIGH_ATTEMPTS.sum()
                + " interpolatedCell=" + CELL_INTERPOLATED.sum()
                + " directReject={unique=" + DIRECT_CELL_REJECTS.get()
                + ", candidate=" + DIRECT_CELL_CANDIDATE_REJECTS.get()
                + ", compiler=" + Math.max(0L, DIRECT_CELL_REJECTS.get() - DIRECT_CELL_CANDIDATE_REJECTS.get()) + '}'
                + " cellSkip={disabled=" + CELL_SKIP_DISABLED.sum()
                + ", root=" + CELL_SKIP_ROOT_BYPASS.sum()
                + ", cell=" + CELL_SKIP_CELL_BYPASS.sum()
                + ", compile=" + CELL_SKIP_COMPILE_NULL.sum()
                + ", output=" + CELL_SKIP_OUTPUT_TOO_SMALL.sum() + '}'
                + " threadCacheReplacement=" + THREAD_COMPILE_CACHE_REPLACEMENTS.sum()
                + " timingsUs={compile=" + avgMicros(COMPILE_NANOS.sum(), COMPILE_ATTEMPTS.sum())
                + ", slice=" + avgMicros(SLICE_NANOS.sum(), SLICE_SUCCESS.sum())
                + ", grid=" + avgMicros(GRID_NANOS.sum(), GRID_SUCCESS.sum())
                + ", climate=" + avgMicros(CLIMATE_BATCH_NANOS.sum(), CLIMATE_BATCH_SUCCESS.sum())
                + ", cell=" + avgMicros(CELL_NANOS.sum(), CELL_SUCCESS.sum())
                + ", column=" + avgMicros(COLUMN_NANOS.sum(), COLUMN_COUNT.sum())
                + ", columnCount=" + COLUMN_COUNT.sum()
                + ", columnTotalMs=" + COLUMN_NANOS.sum() / 1_000_000L
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
        SLICE_BATCH_CALLS.reset();
        SLICE_BATCH_FUNCTIONS.reset();
        GRID_ATTEMPTS.reset();
        GRID_SUCCESS.reset();
        CLIMATE_BATCH_ATTEMPTS.reset();
        CLIMATE_BATCH_SUCCESS.reset();
        SHARED_LEAF_MARKS.reset();
        SLICE_TEMPLATE_HITS.reset();
        SLICE_TEMPLATE_MISSES.reset();
        CLIMATE_TEMPLATE_HITS.reset();
        CLIMATE_TEMPLATE_MISSES.reset();
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
        THREAD_COMPILE_CACHE_REPLACEMENTS.reset();
        COMPILE_NANOS.reset();
        SLICE_NANOS.reset();
        GRID_NANOS.reset();
        CLIMATE_BATCH_NANOS.reset();
        CELL_NANOS.reset();
        COLUMN_NANOS.reset();
        COLUMN_COUNT.reset();
        COLUMN_BATCH_CALLS.reset();
        COLUMN_BATCH_FUNCTIONS.reset();
        COLUMN_JAVA_ONLY_BATCHES.reset();
        COLUMN_JAVA_ONLY_BYPASSES.reset();
        SYNC_NANOS.reset();
        SYNC_COUNT.reset();
        PARITY_CHECKS.reset();
        PARITY_FAILURES.reset();
        PARITY_MAX_ERROR_BITS.set(Double.doubleToRawLongBits(0.0));
        PARITY_SAMPLE_COUNTER.set(0L);
        DIRECT_CELL_REJECTS.set(0L);
        DIRECT_CELL_CANDIDATE_REJECTS.set(0L);
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
        recordParity(path, function, nativeValues, 0, javaValues, javaValues.length);
    }

    public static void recordParity(String path,
                                    DensityFunction function,
                                    double[] nativeValues,
                                    int nativeOffset,
                                    double[] javaValues,
                                    int length) {
        if (!PARITY_ENABLED || nativeOffset < 0 || length < 0
                || nativeOffset + length > nativeValues.length || length > javaValues.length) return;
        double max = 0.0;
        int index = -1;
        for (int i = 0; i < length; i++) {
            double error = Math.abs(nativeValues[nativeOffset + i] - javaValues[i]);
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
                    index >= 0 ? nativeValues[nativeOffset + index] : Double.NaN,
                    index >= 0 ? javaValues[index] : Double.NaN);
        }
    }

    private static ClimateBatchCompilation compileClimateBatch(DensityFunction[] functions, Object arenaKey) {
        if (!LatticeNative.isLoaded() || functions == null || functions.length == 0) return null;
        long cacheHandle = 0L;
        try {
            ClimateBatchTemplate template = climateBatchTemplate(functions, arenaKey);
            if (template == null) return null;
            cacheHandle = createCache(template.handle);
            if (cacheHandle == 0L) return null;
            ClimateBatchCompilation compilation = new ClimateBatchCompilation(
                    arenaKey, COMPILE_CACHE_EPOCH, template, cacheHandle);
            cacheHandle = 0L;
            return compilation;
        } catch (RuntimeException | LinkageError e) {
            recordUnsupported("compile.climateBatch." + e.getClass().getName());
            LatticeNative.logFallbackOnce("density_function_climate_batch_compile", e.getMessage());
            return null;
        } finally {
            if (cacheHandle != 0L) {
                try {
                    nativeDestroyCache(cacheHandle);
                } catch (LinkageError ignored) {
                }
            }
        }
    }

    private static ClimateBatchTemplate climateBatchTemplate(DensityFunction[] functions, Object arenaKey) {
        if (arenaKey == null) return compileClimateBatchTemplate(functions, functions.clone());
        synchronized (CLIMATE_BATCH_TEMPLATES) {
            if (CLIMATE_BATCH_JAVA_ONLY_ARENAS.containsKey(arenaKey)) return null;
            ClimateBatchTemplate cached = CLIMATE_BATCH_TEMPLATES.get(arenaKey);
            if (cached != null && cached.epoch == COMPILE_CACHE_EPOCH && cached.roots.length == functions.length) {
                if (STATS_ENABLED) CLIMATE_TEMPLATE_HITS.increment();
                return cached;
            }

            ClimateBatchTemplate compiled = compileClimateBatchTemplate(functions, arenaKey);
            if (compiled == null) {
                CLIMATE_BATCH_JAVA_ONLY_ARENAS.put(arenaKey, Boolean.TRUE);
                return null;
            }
            CLIMATE_BATCH_TEMPLATES.put(arenaKey, compiled);
            if (STATS_ENABLED) CLIMATE_TEMPLATE_MISSES.increment();
            return compiled;
        }
    }

    private static ClimateBatchTemplate compileClimateBatchTemplate(DensityFunction[] functions, Object lifetimeAnchor) {
        long handle = 0L;
        try {
            DensityFunction first = functions[0];
            if (first == null || bypassRootNative(first)) return null;
            handle = createArena();
            if (handle == 0L) return null;

            Compiler compiler = new Compiler(handle, first, false);
            int[] roots = new int[functions.length];
            for (int i = 0; i < functions.length; i++) {
                DensityFunction function = functions[i];
                if (function == null || bypassRootNative(function)
                        || function.getClass().getName().contains("NoiseChunk$CacheAllInCell")) {
                    recordUnsupported(function);
                    return null;
                }
                if (STATS_ENABLED) COMPILE_ATTEMPTS.increment();
                int root = compiler.compile(function);
                if (root < 0) {
                    recordUnsupported(function);
                    return null;
                }
                roots[i] = root;
            }
            if (!compiler.interpolators().isEmpty() || compiler.cacheAllInCellValues() != null) return null;

            setRoot(handle, roots[0]);
            SHARED_LEAF_MARKS.add(nativeConfigureSharedLeafMemo(handle, roots, roots.length));
            if (STATS_ENABLED) COMPILE_SUCCESS.add(roots.length);
            ClimateBatchTemplate template = new ClimateBatchTemplate(
                    COMPILE_CACHE_EPOCH, handle, roots, lifetimeAnchor);
            handle = 0L;
            return template;
        } finally {
            if (handle != 0L) {
                try {
                    destroyArena(handle);
                } catch (LinkageError ignored) {
                }
            }
        }
    }

    private static SliceBatchCompilation compileSliceBatch(List<NoiseChunk.NoiseInterpolator> interpolators,
                                                            Object arenaKey) {
        if (!LatticeNative.isLoaded() || interpolators == null || interpolators.isEmpty()) return null;
        long cacheHandle = 0L;
        try {
            SliceBatchTemplate template = sliceBatchTemplate(interpolators, arenaKey);
            if (template == null) return null;
            cacheHandle = createCache(template.handle);
            if (cacheHandle == 0L) return null;
            SliceBatchCompilation compilation = new SliceBatchCompilation(
                    interpolators, arenaKey, COMPILE_CACHE_EPOCH, template, cacheHandle);
            cacheHandle = 0L;
            return compilation;
        } catch (RuntimeException | LinkageError e) {
            recordUnsupported("compile.sliceBatch." + e.getClass().getName());
            LatticeNative.logFallbackOnce("density_function_slice_batch_compile", e.getMessage());
            return null;
        } finally {
            if (cacheHandle != 0L) {
                try {
                    nativeDestroyCache(cacheHandle);
                } catch (LinkageError ignored) {
                }
            }
        }
    }

    private static SliceBatchTemplate sliceBatchTemplate(List<NoiseChunk.NoiseInterpolator> interpolators,
                                                          Object arenaKey) {
        if (arenaKey == null) return compileSliceBatchTemplate(interpolators);
        synchronized (SLICE_BATCH_TEMPLATES) {
            Map<Integer, SliceBatchTemplate> variants = SLICE_BATCH_TEMPLATES.get(arenaKey);
            if (variants != null) {
                SliceBatchTemplate cached = variants.get(interpolators.size());
                if (cached != null && cached.epoch == COMPILE_CACHE_EPOCH) {
                    if (STATS_ENABLED) SLICE_TEMPLATE_HITS.increment();
                    return cached;
                }
            }

            SliceBatchTemplate compiled = compileSliceBatchTemplate(interpolators);
            if (compiled == null) return null;
            if (variants == null) {
                variants = new java.util.HashMap<>();
                SLICE_BATCH_TEMPLATES.put(arenaKey, variants);
            }
            variants.put(interpolators.size(), compiled);
            if (STATS_ENABLED) SLICE_TEMPLATE_MISSES.increment();
            return compiled;
        }
    }

    private static SliceBatchTemplate compileSliceBatchTemplate(List<NoiseChunk.NoiseInterpolator> interpolators) {
        long handle = 0L;
        try {
            DensityFunction first = interpolators.get(0).wrapped();
            if (bypassRootNative(first) || first.getClass().getName().contains("NoiseChunk$CacheAllInCell")) return null;
            handle = createArena();
            if (handle == 0L) return null;

            Compiler compiler = new Compiler(handle, first, false);
            int[] roots = new int[interpolators.size()];
            int index = 0;
            for (NoiseChunk.NoiseInterpolator interpolator : interpolators) {
                DensityFunction function = interpolator.wrapped();
                if (bypassRootNative(function) || function.getClass().getName().contains("NoiseChunk$CacheAllInCell")) {
                    recordUnsupported(function);
                    return null;
                }
                if (STATS_ENABLED) COMPILE_ATTEMPTS.increment();
                int root = compiler.compile(function);
                if (root < 0) {
                    recordUnsupported(function);
                    return null;
                }
                roots[index++] = root;
            }

            setRoot(handle, roots[0]);
            SHARED_LEAF_MARKS.add(nativeConfigureSharedLeafMemo(handle, roots, roots.length));
            if (STATS_ENABLED) COMPILE_SUCCESS.add(roots.length);
            SliceBatchTemplate template = new SliceBatchTemplate(COMPILE_CACHE_EPOCH, handle, roots, interpolators);
            handle = 0L;
            return template;
        } finally {
            if (handle != 0L) {
                try {
                    destroyArena(handle);
                } catch (LinkageError ignored) {
                }
            }
        }
    }

    public static void recordParitySliceRow(String path,
                                             DensityFunction function,
                                             double[] nativeValues,
                                             int nativeOffset,
                                             double[] javaValues,
                                             int length) {
        if (!PARITY_ENABLED || nativeOffset < 0 || length < 0
                || nativeOffset + length > nativeValues.length || length > javaValues.length) return;
        double max = 0.0;
        int index = -1;
        for (int i = 0; i < length; i++) {
            double error = Math.abs(nativeValues[nativeOffset + i] - javaValues[i]);
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
                    index >= 0 ? nativeValues[nativeOffset + index] : Double.NaN,
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
        synchronized (SLICE_BATCH_TEMPLATES) {
            SLICE_BATCH_TEMPLATES.clear();
        }
        synchronized (CLIMATE_BATCH_TEMPLATES) {
            CLIMATE_BATCH_TEMPLATES.clear();
            CLIMATE_BATCH_JAVA_ONLY_ARENAS.clear();
        }
        synchronized (CELL_COLUMN_JAVA_ONLY_ARENAS) {
            CELL_COLUMN_JAVA_ONLY_ARENAS.clear();
        }
        BYPASSED_ROOT_CLASSES.clear();
        COMPILE_CACHE_EPOCH++;
        LAST_COMPILE.remove();
        LAST_DIRECT_COMPILE.remove();
        LAST_CELL_COMPILE.remove();
        LAST_CELL_BYPASS.remove();
        LAST_SLICE_ROWS_REJECT.remove();
        ClimateBatchBuffers climateBuffers = CLIMATE_BATCH_BUFFERS.get();
        climateBuffers.destroyNow();
        CLIMATE_BATCH_BUFFERS.remove();
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

    private static final class ThreadCompileCache {
        private final DensityFunction[] keys = new DensityFunction[THREAD_COMPILE_CACHE_CAPACITY];
        private final Object[] values = new Object[THREAD_COMPILE_CACHE_CAPACITY];

        private Object get(DensityFunction function) {
            int slot = System.identityHashCode(function) & (THREAD_COMPILE_CACHE_CAPACITY - 1);
            return keys[slot] == function ? values[slot] : THREAD_COMPILE_CACHE_MISS;
        }

        private void put(DensityFunction function, Object value) {
            int slot = System.identityHashCode(function) & (THREAD_COMPILE_CACHE_CAPACITY - 1);
            DensityFunction previous = keys[slot];
            if (previous != null && previous != function) {
                THREAD_COMPILE_CACHE_REPLACEMENTS.increment();
            }
            keys[slot] = function;
            values[slot] = value;
        }

        private void clear() {
            Arrays.fill(keys, null);
            Arrays.fill(values, null);
        }
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
    private record CacheAllInCellBinding(int slot, NativeCacheAllInCellAccess access) {}
    private record LastSliceRowsReject(List<NoiseChunk.NoiseInterpolator> interpolators, int epoch) {}

    private static final class ClimateBatchTemplate {
        private final int epoch;
        private final long handle;
        private final int[] roots;
        @SuppressWarnings("unused")
        private final Object lifetimeAnchor;
        @SuppressWarnings("unused")
        private final Cleaner.Cleanable cleanable;

        private ClimateBatchTemplate(int epoch,
                                     long handle,
                                     int[] roots,
                                     Object lifetimeAnchor) {
            this.epoch = epoch;
            this.handle = handle;
            this.roots = roots;
            this.lifetimeAnchor = lifetimeAnchor;
            this.cleanable = CLEANER.register(this, new Destroy(handle, 0L));
        }
    }

    private static final class ClimateBatchCompilation {
        private final Object arenaKey;
        private final int epoch;
        @SuppressWarnings("unused")
        private final ClimateBatchTemplate template;
        private final long handle;
        private final long cacheHandle;
        private final int[] roots;
        private final Cleaner.Cleanable cleanable;

        private ClimateBatchCompilation(Object arenaKey,
                                        int epoch,
                                        ClimateBatchTemplate template,
                                        long cacheHandle) {
            this.arenaKey = arenaKey;
            this.epoch = epoch;
            this.template = template;
            this.handle = template.handle;
            this.cacheHandle = cacheHandle;
            this.roots = template.roots;
            this.cleanable = CLEANER.register(this, new Destroy(0L, cacheHandle));
        }

        private boolean matches(Object candidateArenaKey, int rootCount) {
            return this.arenaKey == candidateArenaKey && this.epoch == COMPILE_CACHE_EPOCH
                    && this.roots.length == rootCount;
        }

        private void destroyNow() {
            this.cleanable.clean();
        }
    }

    private static final class SliceBatchTemplate {
        private final int epoch;
        private final long handle;
        private final int[] roots;
        @SuppressWarnings("unused")
        private final List<NoiseChunk.NoiseInterpolator> lifetimeAnchor;
        @SuppressWarnings("unused")
        private final Cleaner.Cleanable cleanable;

        private SliceBatchTemplate(int epoch,
                                   long handle,
                                   int[] roots,
                                   List<NoiseChunk.NoiseInterpolator> lifetimeAnchor) {
            this.epoch = epoch;
            this.handle = handle;
            this.roots = roots;
            this.lifetimeAnchor = lifetimeAnchor;
            this.cleanable = CLEANER.register(this, new Destroy(handle, 0L));
        }
    }

    private static final class SliceBatchCompilation {
        private final List<NoiseChunk.NoiseInterpolator> interpolators;
        private final Object arenaKey;
        private final int epoch;
        @SuppressWarnings("unused")
        private final SliceBatchTemplate template;
        private final long handle;
        private final long cacheHandle;
        private final int[] roots;
        private final Cleaner.Cleanable cleanable;

        private SliceBatchCompilation(List<NoiseChunk.NoiseInterpolator> interpolators,
                                      Object arenaKey,
                                      int epoch,
                                      SliceBatchTemplate template,
                                      long cacheHandle) {
            this.interpolators = interpolators;
            this.arenaKey = arenaKey;
            this.epoch = epoch;
            this.template = template;
            this.handle = template.handle;
            this.cacheHandle = cacheHandle;
            this.roots = template.roots;
            this.cleanable = CLEANER.register(this, new Destroy(0L, cacheHandle));
        }

        private boolean matches(List<NoiseChunk.NoiseInterpolator> candidate, Object candidateArenaKey) {
            return this.interpolators == candidate && this.arenaKey == candidateArenaKey
                    && this.epoch == COMPILE_CACHE_EPOCH;
        }

        private void destroyNow() {
            this.cleanable.clean();
        }
    }

    private static final class SliceBatchBuffers {
        private long[] handles = new long[0];
        private long[] cacheHandles = new long[0];
        private double[][] flatOutputs = new double[0][];
        private double[] packedOutputs = new double[0];
        private NativeNoiseInterpolatorAccess[] accesses = new NativeNoiseInterpolatorAccess[0];
        private NoiseChunk.NoiseInterpolator[] nativeInterpolators = new NoiseChunk.NoiseInterpolator[0];
        private NoiseChunk.NoiseInterpolator[] javaInterpolators = new NoiseChunk.NoiseInterpolator[0];
        private double[][] javaOutputs = new double[0][];
        private NativeNoiseInterpolatorAccess[] javaAccesses = new NativeNoiseInterpolatorAccess[0];
        private final SliceBatchCompilation[] boundSliceCompilations = new SliceBatchCompilation[2];
        private final double[][][] boundSliceOutputs = new double[2][][];
        private SliceBatchCompilation batchCompilation;

        private SliceBatchCompilation compilation(List<NoiseChunk.NoiseInterpolator> interpolators, Object arenaKey) {
            if (batchCompilation != null && batchCompilation.matches(interpolators, arenaKey)) return batchCompilation;
            if (batchCompilation != null) batchCompilation.destroyNow();
            batchCompilation = compileSliceBatch(interpolators, arenaKey);
            return batchCompilation;
        }

        private void ensureCapacity(int size) {
            if (handles.length >= size) return;
            handles = new long[size];
            cacheHandles = new long[size];
            flatOutputs = new double[size][];
            accesses = new NativeNoiseInterpolatorAccess[size];
            nativeInterpolators = new NoiseChunk.NoiseInterpolator[size];
            javaInterpolators = new NoiseChunk.NoiseInterpolator[size];
            javaOutputs = new double[size][];
            javaAccesses = new NativeNoiseInterpolatorAccess[size];
        }

        private void ensurePackedCapacity(int size) {
            if (packedOutputs.length < size) packedOutputs = new double[size];
        }

        private int boundSliceOutputSlot(SliceBatchCompilation batch, int count,
                                         int preferredSlot, double[][] outputs) {
            for (int slot = 0; slot < boundSliceOutputs.length; slot++) {
                double[][] snapshot = boundSliceOutputs[slot];
                if (boundSliceCompilations[slot] != batch || snapshot == null || snapshot.length != count) continue;
                boolean match = true;
                for (int i = 0; i < count; i++) {
                    if (snapshot[i] != outputs[i]) {
                        match = false;
                        break;
                    }
                }
                if (match) return slot;
            }
            if (!Y_COLUMN_ROOTS_FLAT_ROWS_BOUND_NATIVE_AVAILABLE) return -1;
            int slot = preferredSlot & 1;
            try {
                nativeBindSliceOutputs(batch.cacheHandle, slot, count, outputs);
            } catch (UnsatisfiedLinkError | NoSuchMethodError e) {
                Y_COLUMN_ROOTS_FLAT_ROWS_BOUND_NATIVE_AVAILABLE = false;
                LatticeNative.logFallbackOnce("density_function_y_column_roots_flat_rows_bound_symbol", e.getMessage());
                return -1;
            } catch (RuntimeException | LinkageError e) {
                LatticeNative.logFallbackOnce("density_function_y_column_roots_flat_rows_bind", e.getMessage());
                return -1;
            }
            double[][] snapshot = new double[count][];
            System.arraycopy(outputs, 0, snapshot, 0, count);
            boundSliceOutputs[slot] = snapshot;
            boundSliceCompilations[slot] = batch;
            return slot;
        }
    }

    private static final class ColumnBatchBuffers {
        private long[] handles = new long[0];
        private long[] cacheHandles = new long[0];
        private double[][] outputs = new double[0][];
        private NativeCacheAllInCellAccess[] nativeAccesses = new NativeCacheAllInCellAccess[0];
        private NativeCacheAllInCellAccess[] javaAccesses = new NativeCacheAllInCellAccess[0];

        private void ensureCapacity(int size) {
            if (handles.length >= size) return;
            handles = new long[size];
            cacheHandles = new long[size];
            outputs = new double[size][];
            nativeAccesses = new NativeCacheAllInCellAccess[size];
            javaAccesses = new NativeCacheAllInCellAccess[size];
        }
    }

    /// Resolved accessor for one (owner class, member name) pair: either a
    /// zero-arg method or a field. Resolving is done once and cached, because
    /// the previous per-call `getDeclaredMethod` probe threw a
    /// NoSuchMethodException for every field-backed accessor (spline,
    /// coordinate, values, locations, derivatives, ...) and the exception
    /// message construction (`Class.methodToString`) showed up in worldgen
    /// JFR at ~1.3%. The resolved member reads only tree structure, never
    /// computed density values, so this has no parity impact.
    private static final ConcurrentHashMap<Class<?>, ConcurrentHashMap<String, java.lang.reflect.AccessibleObject>> ACCESSOR_CACHE =
            new ConcurrentHashMap<>();

    private static java.lang.reflect.AccessibleObject resolveAccessor(Class<?> owner, String name) {
        try {
            Method method = owner.getDeclaredMethod(name);
            method.setAccessible(true);
            return method;
        } catch (NoSuchMethodException methodFailure) {
            try {
                Field field = owner.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException fieldFailure) {
                throw new IllegalStateException(owner.getName() + "." + name + " changed shape", fieldFailure);
            }
        }
    }

    private static Object invoke(Object owner, String methodName) {
        Class<?> ownerClass = owner.getClass();
        ConcurrentHashMap<String, java.lang.reflect.AccessibleObject> accessors =
                ACCESSOR_CACHE.computeIfAbsent(ownerClass, ignored -> new ConcurrentHashMap<>());
        java.lang.reflect.AccessibleObject accessor =
                accessors.computeIfAbsent(methodName, name -> resolveAccessor(ownerClass, name));
        try {
            if (accessor instanceof Method method) {
                return method.invoke(owner, (Object[]) null);
            }
            return ((Field) accessor).get(owner);
        } catch (ReflectiveOperationException accessFailure) {
            throw new IllegalStateException(ownerClass.getName() + "." + methodName + " access failed", accessFailure);
        }
    }

    private static final class ClimateBatchBuffers {
        private ClimateBatchCompilation compilation;

        private ClimateBatchCompilation compilation(DensityFunction[] functions, Object arenaKey) {
            if (this.compilation != null && this.compilation.matches(arenaKey, functions.length)) {
                return this.compilation;
            }
            destroyNow();
            this.compilation = compileClimateBatch(functions, arenaKey);
            return this.compilation;
        }

        private void destroyNow() {
            if (this.compilation != null) {
                this.compilation.destroyNow();
                this.compilation = null;
            }
        }
    }

    @FunctionalInterface
    private interface UnaryNativeAdd {
        int add(long handle, int input);
    }

    @FunctionalInterface
    private interface BinaryNativeAdd {
        int add(long handle, int left, int right);
    }

    @FunctionalInterface
    private interface NoiseShiftNativeAdd {
        int add(long handle, long noiseHandle);
    }

    private static int addConstant(long handle, double value) {
        return nativeAddConstant(handle, value);
    }

    private static int addUnary(long handle, int ffmKind, int input, UnaryNativeAdd fallback) {
        return fallback.add(handle, input);
    }

    private static int addBinary(long handle, int ffmKind, int left, int right, BinaryNativeAdd fallback) {
        return fallback.add(handle, left, right);
    }

    private static int addYClampedGradient(long handle, int fromY, int toY, double fromValue, double toValue) {
        return nativeAddYClampedGradient(handle, fromY, toY, fromValue, toValue);
    }

    private static int addClamp(long handle, int input, double minValue, double maxValue) {
        return nativeAddClamp(handle, input, minValue, maxValue);
    }

    private static int addBlendAlpha(long handle) {
        return nativeAddBlendAlpha(handle);
    }

    private static int addBlendOffset(long handle) {
        return nativeAddBlendOffset(handle);
    }

    private static int addBlendDensity(long handle, int input) {
        return nativeAddBlendDensity(handle, input);
    }

    private static int addNoise(long handle, long noiseHandle, double scaleXZ, double scaleY) {
        return nativeAddNoise(handle, noiseHandle, scaleXZ, scaleY);
    }

    private static int addShiftedNoise(long handle, int shiftX, int shiftY, int shiftZ, long noiseHandle, double xzScale, double yScale) {
        return nativeAddShiftedNoise(handle, shiftX, shiftY, shiftZ, noiseHandle, xzScale, yScale);
    }

    private static int addShift(long handle, int ffmKind, long noiseHandle, NoiseShiftNativeAdd fallback) {
        return fallback.add(handle, noiseHandle);
    }

    private static int addRangeChoice(long handle, int input, double minInclusive, double maxExclusive, int whenIn, int whenOut) {
        return nativeAddRangeChoice(handle, input, minInclusive, maxExclusive, whenIn, whenOut);
    }

    private static int addMapRange(long handle, int input, double fromLow, double fromHigh, double toLow, double toHigh) {
        return nativeAddMapRange(handle, input, fromLow, fromHigh, toLow, toHigh);
    }

    private static int addCache(long handle, int ffmKind, int input, UnaryNativeAdd fallback) {
        return fallback.add(handle, input);
    }

    private static int addCacheAllInCellValue(long handle) {
        return nativeAddCacheAllInCellValue(handle);
    }

    private static int cacheSlot(long handle, int nodeRef) {
        return nativeCacheSlot(handle, nodeRef);
    }

    private static int addWeirdScaledSampler(long handle, int input, long noiseHandle, int type) {
        return nativeAddWeirdScaledSampler(handle, input, noiseHandle, type);
    }

    private static int addInterpolatedNoise(long handle, long samplerHandle) {
        return nativeAddInterpolatedNoise(handle, samplerHandle);
    }

    private static int addSpline(long handle, int splineRef) {
        return nativeAddSpline(handle, splineRef);
    }

    private static int addFindTopSurface(long handle, int density, int upperBound, int lowerBound, int cellHeight) {
        return nativeAddFindTopSurface(handle, density, upperBound, lowerBound, cellHeight);
    }

    private static int addBeardifier(long handle, long beardifierHandle) {
        return nativeAddBeardifier(handle, beardifierHandle);
    }

    private static final class Compiler {
        private final long handle;
        private final DensityFunction root;
        private final boolean directCell;
        private final Map<DensityFunction, Integer> refs = new IdentityHashMap<>();
        private final Map<ExpensiveLeafKey, Integer> expensiveLeafRefs = new HashMap<>();
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

        private int compileExpensiveLeaf(ExpensiveLeafKey key, java.util.function.IntSupplier factory) {
            Integer cached = expensiveLeafRefs.get(key);
            if (cached != null) return cached.intValue();
            int ref = factory.getAsInt();
            if (ref >= 0) expensiveLeafRefs.put(key, ref);
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
                values[binding.slot()] = binding.access().lattice$values();
            }
            return values;
        }

        private NativeCacheAllInCellAccess[] cacheAllInCellAccesses() {
            int size = 0;
            for (CacheAllInCellBinding binding : cacheAllInCellBindings) {
                size = Math.max(size, binding.slot() + 1);
            }
            if (size == 0) return null;
            NativeCacheAllInCellAccess[] accesses = new NativeCacheAllInCellAccess[size];
            for (CacheAllInCellBinding binding : cacheAllInCellBindings) {
                accesses[binding.slot()] = binding.access();
            }
            return accesses;
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
                    || name.contains("NoiseChunk$NoiseInterpolator")
                    || name.contains("InterpolatedNoise")) {
                directExpensiveNodeCount++;
            }
        }

        private int compileUncached(DensityFunction function) {
            if (function instanceof NativeInterpolatedNoiseAccess access) {
                NativeInterpolatedNoise nativeNoise = access.lattice$getNativeInterpolatedNoise();
                if (nativeNoise == null) return -1;
                long samplerHandle = nativeNoise.handle();
                return compileExpensiveLeaf(
                        ExpensiveLeafKey.of(6, samplerHandle, -1, -1, -1, 0.0, 0.0),
                        () -> addInterpolatedNoise(handle, samplerHandle));
            }

            String name = function.getClass().getName();
            if (name.endsWith("DensityFunctions$Constant")) {
                return addConstant(handle, (Double) invoke(function, "value"));
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
                long samplerHandle = noise.handle();
                double xzScale = (Double) invoke(function, "xzScale");
                double yScale = (Double) invoke(function, "yScale");
                return compileExpensiveLeaf(
                        ExpensiveLeafKey.of(1, samplerHandle, -1, -1, -1, xzScale, yScale),
                        () -> addNoise(handle, samplerHandle, xzScale, yScale));
            }
            if (name.endsWith("DensityFunctions$ShiftA")) {
                NativeDoublePerlinNoise noise = nativeNoiseHolderNoise(invoke(function, "offsetNoise"));
                if (noise == null) return -1;
                long samplerHandle = noise.handle();
                return compileExpensiveLeaf(
                        ExpensiveLeafKey.of(2, samplerHandle, -1, -1, -1, 0.0, 0.0),
                        () -> addShift(handle, 1, samplerHandle, NativeDensityFunction::nativeAddShiftA));
            }
            if (name.endsWith("DensityFunctions$ShiftB")) {
                NativeDoublePerlinNoise noise = nativeNoiseHolderNoise(invoke(function, "offsetNoise"));
                if (noise == null) return -1;
                long samplerHandle = noise.handle();
                return compileExpensiveLeaf(
                        ExpensiveLeafKey.of(3, samplerHandle, -1, -1, -1, 0.0, 0.0),
                        () -> addShift(handle, 2, samplerHandle, NativeDensityFunction::nativeAddShiftB));
            }
            if (name.endsWith("DensityFunctions$Shift")) {
                NativeDoublePerlinNoise noise = nativeNoiseHolderNoise(invoke(function, "offsetNoise"));
                if (noise == null) return -1;
                long samplerHandle = noise.handle();
                return compileExpensiveLeaf(
                        ExpensiveLeafKey.of(4, samplerHandle, -1, -1, -1, 0.0, 0.0),
                        () -> addShift(handle, 3, samplerHandle, NativeDensityFunction::nativeAddShift));
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
                long samplerHandle = noise.handle();
                double xzScale = (Double) invoke(function, "xzScale");
                double yScale = (Double) invoke(function, "yScale");
                return compileExpensiveLeaf(
                        ExpensiveLeafKey.of(5, samplerHandle, shiftX, shiftY, shiftZ, xzScale, yScale),
                        () -> addShiftedNoise(handle, shiftX, shiftY, shiftZ, samplerHandle, xzScale, yScale));
            }
            if (name.contains("NoiseChunk$NoiseInterpolator")) {
                int input = compile((DensityFunction) invoke(function, "wrapped"));
                if (input < 0) return -1;
                if (!(function instanceof NativeNoiseInterpolatorAccess access)) return -1;
                int ref = addCache(handle, 5, input, NativeDensityFunction::nativeAddInterpolated);
                if (ref >= 0) {
                    int slot = cacheSlot(handle, ref);
                    if (slot < 0) return -1;
                    interpolators.add(new InterpolatorBinding(access, slot));
                    access.lattice$setNativeSlot(slot);
                }
                return ref;
            }
            if (name.endsWith("DensityFunctions$Mapped")) {
                int input = compile((DensityFunction) invoke(function, "input"));
                if (input < 0) return -1;
                return switch (((Enum<?>) invoke(function, "type")).name()) {
                    case "ABS" -> addUnary(handle, 1, input, NativeDensityFunction::nativeAddAbs);
                    case "SQUARE" -> addUnary(handle, 2, input, NativeDensityFunction::nativeAddSquare);
                    case "CUBE" -> addUnary(handle, 3, input, NativeDensityFunction::nativeAddCube);
                    case "HALF_NEGATIVE" -> addUnary(handle, 4, input, NativeDensityFunction::nativeAddHalfNegative);
                    case "QUARTER_NEGATIVE" -> addUnary(handle, 5, input, NativeDensityFunction::nativeAddQuarterNegative);
                    case "INVERT" -> addUnary(handle, 6, input, NativeDensityFunction::nativeAddInvert);
                    case "SQUEEZE" -> addUnary(handle, 7, input, NativeDensityFunction::nativeAddSqueeze);
                    default -> -1;
                };
            }
            if (name.endsWith("DensityFunctions$Ap2") || name.endsWith("DensityFunctions$MulOrAdd")) {
                int left = compile((DensityFunction) invoke(function, "argument1"));
                int right = compile((DensityFunction) invoke(function, "argument2"));
                if (left < 0 || right < 0) return -1;
                return switch (((Enum<?>) invoke(function, "type")).name()) {
                    case "ADD" -> addBinary(handle, 1, left, right, NativeDensityFunction::nativeAddAdd);
                    case "MUL" -> addBinary(handle, 2, left, right, NativeDensityFunction::nativeAddMul);
                    case "MIN" -> addBinary(handle, 3, left, right, NativeDensityFunction::nativeAddMin);
                    case "MAX" -> addBinary(handle, 4, left, right, NativeDensityFunction::nativeAddMax);
                    default -> -1;
                };
            }
            if (name.endsWith("DensityFunctions$RangeChoice")) {
                int input = compile((DensityFunction) invoke(function, "input"));
                int whenIn = compile((DensityFunction) invoke(function, "whenInRange"));
                int whenOut = compile((DensityFunction) invoke(function, "whenOutOfRange"));
                if (input < 0 || whenIn < 0 || whenOut < 0) return -1;
                return addRangeChoice(handle, input, (Double) invoke(function, "minInclusive"), (Double) invoke(function, "maxExclusive"), whenIn, whenOut);
            }
            if (name.endsWith("DensityFunctions$MapRange")) {
                int input = compile((DensityFunction) invoke(function, "input"));
                if (input < 0) return -1;
                return addMapRange(handle, input,
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
                return addWeirdScaledSampler(handle, input, noise.handle(), type);
            }
            if (name.endsWith("DensityFunctions$Clamp")) {
                int input = compile((DensityFunction) invoke(function, "input"));
                if (input < 0) return -1;
                return addClamp(handle, input, (Double) invoke(function, "minValue"), (Double) invoke(function, "maxValue"));
            }
            if (name.endsWith("DensityFunctions$FindTopSurface")) {
                int density = compile((DensityFunction) invoke(function, "density"));
                int upperBound = compile((DensityFunction) invoke(function, "upperBound"));
                if (density < 0 || upperBound < 0) return -1;
                return addFindTopSurface(
                        handle,
                        density,
                        upperBound,
                        (Integer) invoke(function, "lowerBound"),
                        (Integer) invoke(function, "cellHeight"));
            }
            if (name.endsWith("DensityFunctions$Spline")) {
                if (!SPLINE_ENABLED) return -1;
                int spline = compileSpline(invoke(function, "spline"));
                return spline < 0 ? -1 : addSpline(handle, spline);
            }
            if (name.equals("net.minecraft.world.level.levelgen.Beardifier")) {
                long beardifierHandle = ((Beardifier) function).lattice$nativeBeardifierHandle();
                return beardifierHandle == 0L ? -1 : addBeardifier(handle, beardifierHandle);
            }
            if (name.endsWith("DensityFunctions$BlendAlpha")) return addBlendAlpha(handle);
            if (name.endsWith("DensityFunctions$BlendOffset")) return addBlendOffset(handle);
            if (name.contains("NoiseChunk$BlendAlpha") || name.contains("NoiseChunk$BlendOffset")) {
                recordUnsupported(function);
                return -1;
            }
            if (name.endsWith("DensityFunctions$BlendDensity")) {
                int input = compile((DensityFunction) invoke(function, "input"));
                return input < 0 ? -1 : addBlendDensity(handle, input);
            }
            if (name.endsWith("DensityFunctions$YClampedGradient")) {
                return addYClampedGradient(handle,
                        (Integer) invoke(function, "fromY"),
                        (Integer) invoke(function, "toY"),
                        (Double) invoke(function, "fromValue"),
                        (Double) invoke(function, "toValue"));
            }
            if (name.contains("NoiseChunk$FlatCache")) {
                int input = compile((DensityFunction) invoke(function, "wrapped"));
                clearsCachePerCell = true;
                return input < 0 ? -1 : addCache(handle, 4, input, NativeDensityFunction::nativeAddFlatCache);
            }
            if (name.contains("NoiseChunk$Cache2D")) {
                int input = compile((DensityFunction) invoke(function, "wrapped"));
                clearsCachePerCell = true;
                return input < 0 ? -1 : addCache(handle, 1, input, NativeDensityFunction::nativeAddCache2D);
            }
            if (name.contains("NoiseChunk$CacheOnce")) {
                int input = compile((DensityFunction) invoke(function, "wrapped"));
                clearsCachePerCell = true;
                return input < 0 ? -1 : addCache(handle, 2, input, NativeDensityFunction::nativeAddCacheOnce);
            }
            if (name.contains("NoiseChunk$CacheAllInCell")) {
                if (function == root) return -1;
                int ref = addCacheAllInCellValue(handle);
                int slot = ref < 0 ? -1 : cacheSlot(handle, ref);
                if (slot >= 0) {
                    cacheAllInCellBindings.add(new CacheAllInCellBinding(slot, (NativeCacheAllInCellAccess) function));
                }
                return ref;
            }

            recordUnsupported(function);
            return -1;
        }

        private record ExpensiveLeafKey(int kind,
                                        long samplerHandle,
                                        int a,
                                        int b,
                                        int c,
                                        long parameter0,
                                        long parameter1) {
            private static ExpensiveLeafKey of(int kind,
                                               long samplerHandle,
                                               int a,
                                               int b,
                                               int c,
                                               double parameter0,
                                               double parameter1) {
                return new ExpensiveLeafKey(
                        kind, samplerHandle, a, b, c,
                        Double.doubleToRawLongBits(parameter0),
                        Double.doubleToRawLongBits(parameter1));
            }
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
    private static native int nativeConfigureSharedLeafMemo(long handle, int[] roots, int count);
    private static native long nativeCreateCache(long handle);
    private static native void nativeDestroyCache(long cacheHandle);
    private static native void nativeBindCacheAllInCellArrays(long cacheHandle, double[][] arrays);
    private static native void nativeClearCache(long cacheHandle);
    private static native void nativeSetExecutionStatsEnabled(long cacheHandle, boolean enabled);
    private static native void nativeResetExecutionStats(long cacheHandle);
    private static native long[] nativeGetExecutionStats(long cacheHandle);
    private static native void nativeEvaluateGrid(long handle, long cacheHandle, double x0, double y0, double z0, double dx, double dy, double dz, int cellX0, int cellZ0, int nx, int ny, int nz, double[] out);
    private static native void nativeEvaluateGridRoots(long handle, long cacheHandle, int[] roots, int count, double x0, double y0, double z0, double dx, double dy, double dz, int cellX0, int cellZ0, int nx, int ny, int nz, double[] out);
    private static native void nativeEvaluateYColumn(long handle, long cacheHandle, double x, double y0, double z, double dy, int cellX, int cellZ, int ny, double[] out);
    private static native void nativeEvaluateYColumnRootsFlatRows(long handle, long cacheHandle, int[] roots, int count, double x, double y0, double z0, double dy, int cellX, int firstCellZ, int cellWidth, int yRows, int zRows, double[][] out);
    private static native void nativeEvaluateYColumnRootsFlatRowsFast(long handle, long cacheHandle, int count, double x, double y0, double z0, double dy, int cellX, int firstCellZ, int cellWidth, int yRows, int zRows, double[][] out);
    private static native void nativeBindSliceOutputs(long cacheHandle, int bindingSlot, int count, double[][] out);
    private static native void nativeEvaluateYColumnRootsFlatRowsBound(long handle, long cacheHandle, int bindingSlot, int count, double x, double y0, double z0, double dy, int cellX, int firstCellZ, int cellWidth, int yRows, int zRows);
    private static native void nativeEvaluateYColumnsFlatRows(long[] handles, long[] cacheHandles, int count, double x, double y0, double z0, double dy, int cellX, int firstCellZ, int cellWidth, int yRows, int zRows, double[][] out);
    private static native void nativeEvaluateYColumnsFlat(long[] handles, long[] cacheHandles, int count, double x, double y0, double z, double dy, int cellX, int cellZ, int ny, double[][] out, int outputOffset);
    private static native void nativeEvaluateYColumnsPacked(long[] handles, long[] cacheHandles, int count, double x, double y0, double z, double dy, int cellX, int cellZ, int ny, double[] outPacked);
    private static native void nativeEvaluateCell(long handle, long cacheHandle, double x0, double yTop, double z0, int cellX, int cellZ, int cellWidth, int cellHeight, double[][] cacheAllInCellValues, double[] out);
    private static native void nativeEvaluateInterpolatedCell(long handle, long cacheHandle, double x0, double yTop, double z0, int cellX, int cellZ, int localCellY, int localCellZ, int cellWidth, int cellHeight, double[][] cacheAllInCellValues, double[] out);
    private static native void nativeEvaluateInterpolatedColumn(long handle, long cacheHandle, double x0, double z0, double yMin, int cellX, int firstCellZ, int cellWidth, int cellHeight, int cellCountXZ, int cellCountY, boolean clearPerCell, double[][] cacheAllInCellValues, double[] out);
    private static native void nativeEvaluateInterpolatedColumns(long[] handles, long[] cacheHandles, int count, double x0, double z0, double yMin, int cellX, int firstCellZ, int cellWidth, int cellHeight, int cellCountXZ, int cellCountY, double[][] out);
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
    private static native int nativeAddFindTopSurface(long handle, int density, int upperBound, int lowerBound, int cellHeight);
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
