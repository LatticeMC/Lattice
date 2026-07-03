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
    private static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("lattice.nativeDensityFunction", "true"));
    private static final boolean STATS_ENABLED = Boolean.getBoolean("lattice.nativeDensityFunctionStats");
    private static final Cleaner CLEANER = Cleaner.create();
    private static final Map<DensityFunction, NativeDensityFunction> CACHE = new WeakHashMap<>();
    private static final Map<DensityFunction, Boolean> FAILED_COMPILES = new WeakHashMap<>();
    private static final ThreadLocal<double[]> CORNER_ROW = ThreadLocal.withInitial(() -> new double[2]);
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
    @SuppressWarnings("unused")
    private final Cleaner.Cleanable cleanable;

    private NativeDensityFunction(long handle, long cacheHandle, List<InterpolatorBinding> interpolators) {
        this.handle = handle;
        this.cacheHandle = cacheHandle;
        this.interpolators = List.copyOf(interpolators);
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
            LOGGER.info("[Lattice] NativeDensityFunction first slice attempt");
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
                                      int cellX,
                                      int cellZ,
                                      int localCellY,
                                      int localCellZ) {
        logStatusOnce();
        if (FIRST_CELL_LOGGED.compareAndSet(false, true)) {
            LOGGER.info("[Lattice] NativeDensityFunction first cell attempt");
        }
        if (!ENABLED) return false;
        if (STATS_ENABLED) {
            CELL_ATTEMPTS.increment();
            maybeLogStats();
        }
        NativeDensityFunction compiled = tryCompile(function);
        if (compiled == null) return false;
        int expected = cellWidth * cellHeight * cellWidth;
        if (values.length < expected) return false;

        try {
            nativeClearCache(compiled.cacheHandle);
            if (!compiled.interpolators.isEmpty()) {
                if (STATS_ENABLED) CELL_INTERPOLATED.increment();
                compiled.syncInterpolators(localCellY, localCellZ);
                nativeEvaluateInterpolatedCell(
                        compiled.handle,
                        compiled.cacheHandle,
                        cellStartBlockX,
                        cellStartBlockY + cellHeight - 1,
                        cellStartBlockZ,
                        cellX,
                        cellZ,
                        cellWidth,
                        cellHeight,
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
                        values);
            }
            if (STATS_ENABLED) CELL_SUCCESS.increment();
            return true;
        } catch (RuntimeException | LinkageError e) {
            LatticeNative.logFallbackOnce("density_function_cell_grid", e.getMessage());
            return false;
        }
    }

    private static NativeDensityFunction tryCompile(DensityFunction function) {
        if (!LatticeNative.isLoaded() || function == null) return null;
        synchronized (CACHE) {
            NativeDensityFunction cached = CACHE.get(function);
            if (cached != null) return cached;
            if (FAILED_COMPILES.containsKey(function)) return null;
            if (STATS_ENABLED) COMPILE_ATTEMPTS.increment();
            NativeDensityFunction compiled = compileNew(function);
            if (compiled != null) {
                if (STATS_ENABLED) COMPILE_SUCCESS.increment();
                CACHE.put(function, compiled);
            } else {
                FAILED_COMPILES.put(function, Boolean.TRUE);
            }
            return compiled;
        }
    }

    private static void maybeLogStats() {
        long attempts = CELL_ATTEMPTS.sum();
        if (attempts <= 0 || attempts % LOG_INTERVAL != 0) return;
        LOGGER.info(
                "[Lattice] NativeDensityFunction stats: compile={}/{}, slice={}/{}, cell={}/{}, interpolatedCell={}, unsupported={}",
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
            LOGGER.info("[Lattice] NativeDensityFunction enabled={} stats={}", ENABLED, STATS_ENABLED);
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
            Compiler compiler = new Compiler(handle);
            int root = compiler.compile(function);
            if (root < 0) return null;
            nativeSetRoot(handle, root);
            cacheHandle = nativeCreateCache(handle);
            if (cacheHandle == 0L) return null;
            NativeDensityFunction compiled = new NativeDensityFunction(handle, cacheHandle, compiler.interpolators());
            if (!compiled.interpolators.isEmpty()) {
                nativePrepareInterpolators(compiled.cacheHandle, 1, 1);
            }
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

    private void syncInterpolators(int localCellY, int localCellZ) {
        for (InterpolatorBinding binding : interpolators) {
            double[][] slice0 = (double[][]) invoke(binding.function(), "lattice$slice0");
            double[][] slice1 = (double[][]) invoke(binding.function(), "lattice$slice1");
            setCornerRow(cacheHandle, binding.slot(), 0, false, slice0[localCellZ], localCellY);
            setCornerRow(cacheHandle, binding.slot(), 1, false, slice0[localCellZ + 1], localCellY);
            setCornerRow(cacheHandle, binding.slot(), 0, true, slice1[localCellZ], localCellY);
            setCornerRow(cacheHandle, binding.slot(), 1, true, slice1[localCellZ + 1], localCellY);
        }
    }

    private static void setCornerRow(long cacheHandle, int slot, int cellZ, boolean toEndBuffer, double[] source, int cellY) {
        double[] row = CORNER_ROW.get();
        row[0] = source[cellY];
        row[1] = source[cellY + 1];
        nativeSetDensityRow(cacheHandle, slot, cellZ, toEndBuffer, row);
    }

    private static DensityFunction child(DensityFunction function, String name) {
        try {
            Object value = invoke(function, name);
            return value instanceof DensityFunction densityFunction ? densityFunction : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private record InterpolatorBinding(DensityFunction function, int slot) {}

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
        private final Map<DensityFunction, Integer> refs = new IdentityHashMap<>();
        private final List<InterpolatorBinding> interpolators = new ArrayList<>();

        private Compiler(long handle) {
            this.handle = handle;
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
                return -1;
            }
            if (name.contains("NoiseChunk$NoiseInterpolator")) {
                int input = compile((DensityFunction) invoke(function, "wrapped"));
                if (input < 0) return -1;
                int ref = nativeAddInterpolated(handle, input);
                if (ref >= 0) {
                    interpolators.add(new InterpolatorBinding(function, ref));
                    invokeInt(function, "lattice$setNativeSlot", ref);
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
                int spline = compileSpline(invoke(function, "spline"));
                return spline < 0 ? -1 : nativeAddSpline(handle, spline);
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
                return input < 0 ? -1 : nativeAddFlatCache(handle, input);
            }
            if (name.contains("NoiseChunk$Cache2D")) {
                int input = compile((DensityFunction) invoke(function, "wrapped"));
                return input < 0 ? -1 : nativeAddCache2D(handle, input);
            }
            if (name.contains("NoiseChunk$CacheOnce")) {
                int input = compile((DensityFunction) invoke(function, "wrapped"));
                return input < 0 ? -1 : nativeAddCacheOnce(handle, input);
            }
            if (name.contains("NoiseChunk$CacheAllInCell")) {
                int input = compile((DensityFunction) invoke(function, "wrapped"));
                return input < 0 ? -1 : nativeAddCacheAllInCell(handle, input);
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
    private static native void nativeEvaluateCell(long handle, long cacheHandle, double x0, double yTop, double z0, int cellX, int cellZ, int cellWidth, int cellHeight, double[] out);
    private static native void nativeEvaluateInterpolatedCell(long handle, long cacheHandle, double x0, double yTop, double z0, int cellX, int cellZ, int cellWidth, int cellHeight, double[] out);
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
    private static native int nativeAddShiftedNoise(long handle, int shiftX, int shiftY, int shiftZ, long noiseHandle, double scale);
    private static native int nativeAddShiftA(long handle, long noiseHandle);
    private static native int nativeAddShiftB(long handle, long noiseHandle);
    private static native int nativeAddShift(long handle, long noiseHandle);
    private static native int nativeAddCache2D(long handle, int input);
    private static native int nativeAddCacheOnce(long handle, int input);
    private static native int nativeAddCacheAllInCell(long handle, int input);
    private static native int nativeAddFlatCache(long handle, int input);
    private static native int nativeAddInterpolated(long handle, int input);
    private static native int nativeAddBlendAlpha(long handle);
    private static native int nativeAddBlendOffset(long handle);
    private static native int nativeAddBlendDensity(long handle, int input);
    private static native int nativeAddClamp(long handle, int input, double minValue, double maxValue);
    private static native int nativeAddInterpolatedNoise(long handle, long samplerHandle);
    private static native int nativeAddWeirdScaledSampler(long handle, int input, long noiseHandle, int type);
    private static native int nativeAddFixedFloatSpline(long handle, float value);
    private static native int nativeAddImplSpline(long handle, int locationFunctionNodeRef, float[] locations, float[] derivatives, int[] valueSplineRefs);
    private static native int nativeAddSpline(long handle, int splineRef);
    private static native void nativePrepareInterpolators(long cacheHandle, int horizontalCellCount, int verticalCellCount);
    private static native void nativeSetDensityRow(long cacheHandle, int slot, int cellZ, boolean toEndBuffer, double[] values);

    private static void invokeInt(Object owner, String methodName, int value) {
        try {
            Method method = owner.getClass().getDeclaredMethod(methodName, int.class);
            method.setAccessible(true);
            method.invoke(owner, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(owner.getClass().getName() + "." + methodName + " changed shape", e);
        }
    }
}
