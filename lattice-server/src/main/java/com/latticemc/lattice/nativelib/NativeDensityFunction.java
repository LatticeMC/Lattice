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
import java.util.concurrent.atomic.LongAdder;
import java.util.WeakHashMap;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class NativeDensityFunction {
    private static final Logger LOGGER = LoggerFactory.getLogger("Lattice");
    private static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("lattice.nativeDensityFunction", "false"));
    private static final boolean CELL_ENABLED = Boolean.parseBoolean(System.getProperty("lattice.nativeDensityFunctionCell", "false"))
            && Boolean.parseBoolean(System.getProperty("lattice.nativeDensityFunctionCellUnsafe", "false"));
    private static final boolean DIRECT_CELL_ENABLED = Boolean.parseBoolean(System.getProperty("lattice.nativeDensityFunctionDirectCell", "false"));
    private static final boolean SPLINE_ENABLED = Boolean.parseBoolean(System.getProperty("lattice.nativeDensityFunctionSpline", "false"));
    private static final boolean STATS_ENABLED = Boolean.getBoolean("lattice.nativeDensityFunctionStats");
    private static final Cleaner CLEANER = Cleaner.create();
    private static final Map<DensityFunction, NativeDensityFunction> CACHE = new WeakHashMap<>();
    private static final Map<DensityFunction, Boolean> FAILED_COMPILES = new WeakHashMap<>();
    private static final ThreadLocal<LastCompile> LAST_COMPILE = new ThreadLocal<>();
    private static final ThreadLocal<LastCompile> LAST_CELL_COMPILE = new ThreadLocal<>();
    private static final ThreadLocal<LastCellBypass> LAST_CELL_BYPASS = new ThreadLocal<>();
    private static final LongAdder COMPILE_ATTEMPTS = new LongAdder();
    private static final LongAdder COMPILE_SUCCESS = new LongAdder();
    private static final LongAdder SLICE_ATTEMPTS = new LongAdder();
    private static final LongAdder SLICE_SUCCESS = new LongAdder();
    private static final LongAdder CELL_ATTEMPTS = new LongAdder();
    private static final LongAdder CELL_SUCCESS = new LongAdder();
    private static final LongAdder CELL_INTERPOLATED = new LongAdder();
    private static final ConcurrentHashMap<String, LongAdder> UNSUPPORTED = new ConcurrentHashMap<>();
    private static final int LOG_INTERVAL = 4096;
    private static final AtomicBoolean STATUS_LOGGED = new AtomicBoolean(false);
    private static final AtomicBoolean FIRST_SLICE_LOGGED = new AtomicBoolean(false);
    private static final AtomicBoolean FIRST_CELL_LOGGED = new AtomicBoolean(false);

    private final long handle;
    private final long cacheHandle;
    private final List<InterpolatorBinding> interpolators;
    private final double[][] cacheAllInCellValues;
    private final boolean clearsCachePerCell;
    private int preparedHorizontalCellCount = -1;
    private int preparedVerticalCellCount = -1;
    private int syncedCellStartBlockX = Integer.MIN_VALUE;
    @SuppressWarnings("unused")
    private final Cleaner.Cleanable cleanable;

    private NativeDensityFunction(long handle, long cacheHandle, List<InterpolatorBinding> interpolators, double[][] cacheAllInCellValues, boolean clearsCachePerCell) {
        this.handle = handle;
        this.cacheHandle = cacheHandle;
        this.interpolators = List.copyOf(interpolators);
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
        if (FIRST_SLICE_LOGGED.compareAndSet(false, true)) {
            LOGGER.info("NativeDensityFunction first slice attempt");
        }
        if (!ENABLED) return false;
        if (STATS_ENABLED) SLICE_ATTEMPTS.increment();
        NativeDensityFunction compiled = tryCompile(function);
        if (compiled == null) return false;
        try {
            nativeClearCache(compiled.cacheHandle);
            nativeEvaluateGrid(compiled.handle, compiled.cacheHandle, x, y0, z, 1.0, dy, 1.0, cellX, cellZ, 1, values.length, 1, values);
            if (STATS_ENABLED) SLICE_SUCCESS.increment();
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
        if (!DIRECT_CELL_ENABLED) return false;
        return tryFillCell(values, function, cellStartBlockX, cellStartBlockY, cellStartBlockZ, cellWidth, cellHeight, cellCountXZ, cellCountY, cellX, cellZ, localCellY, localCellZ, false);
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
        if (bypassCellNative(function)) return false;
        NativeDensityFunction compiled = tryCompileCell(function);
        if (compiled == null) return false;
        if (compiled.clearsCachePerCell) return false;
        int cellValueCount = cellWidth * cellHeight * cellWidth;
        int expected = cellCountXZ * cellCountY * cellValueCount;
        if (values.length < expected) return false;

        try {
            if (compiled.clearsCachePerCell) nativeClearCache(compiled.cacheHandle);
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
                    compiled.cacheAllInCellValues,
                    values);
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
        if (FIRST_CELL_LOGGED.compareAndSet(false, true)) {
            LOGGER.info("NativeDensityFunction first cell attempt");
        }
        if (!ENABLED) return false;
        if (!CELL_ENABLED) return false;
        if (bypassCellNative(function)) return false;
        if (STATS_ENABLED) {
            CELL_ATTEMPTS.increment();
            maybeLogStats();
        }
        NativeDensityFunction compiled = tryCompileCell(function);
        if (compiled == null) return false;
        int expected = cellWidth * cellHeight * cellWidth;
        if (values.length < expected) return false;

        try {
            if (compiled.clearsCachePerCell) nativeClearCache(compiled.cacheHandle);
            if (STATS_ENABLED) CELL_INTERPOLATED.increment();
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
                    compiled.cacheAllInCellValues,
                    values);
            if (STATS_ENABLED) CELL_SUCCESS.increment();
            return true;
        } catch (RuntimeException | LinkageError e) {
            LatticeNative.logFallbackOnce("density_function_cell_grid", e.getMessage());
            return false;
        }
    }

    private static NativeDensityFunction tryCompile(DensityFunction function) {
        if (!LatticeNative.isLoaded() || function == null) return null;
        LastCompile last = LAST_COMPILE.get();
        if (last != null && last.function() == function) return last.compiled();
        synchronized (CACHE) {
            NativeDensityFunction cached = CACHE.get(function);
            if (cached != null) {
                LAST_COMPILE.set(new LastCompile(function, cached));
                return cached;
            }
            if (FAILED_COMPILES.containsKey(function)) {
                LAST_COMPILE.set(new LastCompile(function, null));
                return null;
            }
            if (STATS_ENABLED) COMPILE_ATTEMPTS.increment();
            NativeDensityFunction compiled = compileNew(function);
            if (compiled != null) {
                if (STATS_ENABLED) COMPILE_SUCCESS.increment();
                CACHE.put(function, compiled);
            } else {
                FAILED_COMPILES.put(function, Boolean.TRUE);
            }
            LAST_COMPILE.set(new LastCompile(function, compiled));
            return compiled;
        }
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
        if (STATUS_LOGGED.compareAndSet(false, true)) {
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
        long handle = 0L;
        long cacheHandle = 0L;
        try {
            handle = nativeCreate();
            if (handle == 0L) return null;
            Compiler compiler = new Compiler(handle, function);
            int root = compiler.compile(function);
            if (root < 0) return null;
            nativeSetRoot(handle, root);
            cacheHandle = nativeCreateCache(handle);
            if (cacheHandle == 0L) return null;
            return new NativeDensityFunction(handle, cacheHandle, compiler.interpolators(), compiler.cacheAllInCellValues(), compiler.clearsCachePerCell());
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

        for (InterpolatorBinding binding : interpolators) {
            nativeSetInterpolatorColumn(cacheHandle, binding.slot(), binding.function().lattice$slice0(), binding.function().lattice$slice1(), cellCountXZ + 1, cellCountY + 1);
        }
        syncedCellStartBlockX = cellStartBlockX;
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
        private final Map<DensityFunction, Integer> refs = new IdentityHashMap<>();
        private final List<InterpolatorBinding> interpolators = new ArrayList<>();
        private final List<CacheAllInCellBinding> cacheAllInCellBindings = new ArrayList<>();
        private boolean clearsCachePerCell;

        private Compiler(long handle, DensityFunction root) {
            this.handle = handle;
            this.root = root;
        }

        private int compile(DensityFunction function) {
            Integer cached = refs.get(function);
            if (cached != null) return cached.intValue();

            int ref = compileUncached(function);
            if (ref >= 0) refs.put(function, ref);
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

        private int compileUncached(DensityFunction function) {
            if (function instanceof NativeInterpolatedNoiseAccess access) {
                NativeInterpolatedNoise nativeNoise = access.lattice$getNativeInterpolatedNoise();
                return nativeNoise == null ? -1 : nativeAddInterpolatedNoise(handle, nativeNoise.handle());
            }

            String name = function.getClass().getName();
            if (name.endsWith("DensityFunctions$Constant")) {
                return nativeAddConstant(handle, (Double) invoke(function, "value"));
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
    private static native void nativeClearCache(long cacheHandle);
    private static native void nativeEvaluateGrid(long handle, long cacheHandle, double x0, double y0, double z0, double dx, double dy, double dz, int cellX0, int cellZ0, int nx, int ny, int nz, double[] out);
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
    private static native void nativeSetDensityRow(long cacheHandle, int slot, int cellZ, boolean toEndBuffer, double[] values);

}
