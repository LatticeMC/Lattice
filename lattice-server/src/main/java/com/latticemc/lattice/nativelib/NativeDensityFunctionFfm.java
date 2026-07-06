package com.latticemc.lattice.nativelib;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;

import static com.latticemc.lattice.nativelib.NativeFfm.C_DOUBLE;
import static com.latticemc.lattice.nativelib.NativeFfm.C_INT;
import static com.latticemc.lattice.nativelib.NativeFfm.C_LONG;

final class NativeDensityFunctionFfm {
    private static final FunctionDescriptor CREATE_DESC = FunctionDescriptor.of(C_LONG);
    private static final FunctionDescriptor DESTROY_DESC = FunctionDescriptor.ofVoid(C_LONG);
    private static final FunctionDescriptor SET_ROOT_DESC = FunctionDescriptor.ofVoid(C_LONG, C_INT);
    private static final FunctionDescriptor CREATE_CACHE_DESC = FunctionDescriptor.of(C_LONG, C_LONG);
    private static final FunctionDescriptor DESTROY_CACHE_DESC = FunctionDescriptor.ofVoid(C_LONG);
    private static final FunctionDescriptor CLEAR_CACHE_DESC = FunctionDescriptor.ofVoid(C_LONG);
    private static final FunctionDescriptor ADD_CONSTANT_DESC = FunctionDescriptor.of(C_INT, C_LONG, C_DOUBLE);
    private static final FunctionDescriptor ADD_UNARY_DESC = FunctionDescriptor.of(C_INT, C_LONG, C_INT, C_INT);
    private static final FunctionDescriptor ADD_BINARY_DESC = FunctionDescriptor.of(C_INT, C_LONG, C_INT, C_INT, C_INT);
    private static final FunctionDescriptor ADD_Y_GRADIENT_DESC = FunctionDescriptor.of(C_INT, C_LONG, C_INT, C_INT, C_DOUBLE, C_DOUBLE);
    private static final FunctionDescriptor ADD_CLAMP_DESC = FunctionDescriptor.of(C_INT, C_LONG, C_INT, C_DOUBLE, C_DOUBLE);
    private static final FunctionDescriptor ADD_BLEND_ALPHA_DESC = FunctionDescriptor.of(C_INT, C_LONG);
    private static final FunctionDescriptor ADD_BLEND_OFFSET_DESC = FunctionDescriptor.of(C_INT, C_LONG);
    private static final FunctionDescriptor ADD_BLEND_DENSITY_DESC = FunctionDescriptor.of(C_INT, C_LONG, C_INT);
    private static final FunctionDescriptor ADD_NOISE_DESC = FunctionDescriptor.of(C_INT, C_LONG, C_LONG, C_DOUBLE, C_DOUBLE);
    private static final FunctionDescriptor ADD_SHIFTED_NOISE_DESC = FunctionDescriptor.of(C_INT, C_LONG, C_INT, C_INT, C_INT, C_LONG, C_DOUBLE, C_DOUBLE);
    private static final FunctionDescriptor ADD_SHIFT_DESC = FunctionDescriptor.of(C_INT, C_LONG, C_INT, C_LONG);
    private static final FunctionDescriptor ADD_RANGE_CHOICE_DESC = FunctionDescriptor.of(C_INT, C_LONG, C_INT, C_DOUBLE, C_DOUBLE, C_INT, C_INT);
    private static final FunctionDescriptor ADD_MAP_RANGE_DESC = FunctionDescriptor.of(C_INT, C_LONG, C_INT, C_DOUBLE, C_DOUBLE, C_DOUBLE, C_DOUBLE);
    private static final FunctionDescriptor ADD_CACHE_DESC = FunctionDescriptor.of(C_INT, C_LONG, C_INT, C_INT);
    private static final FunctionDescriptor ADD_CACHE_ALL_IN_CELL_VALUE_DESC = FunctionDescriptor.of(C_INT, C_LONG);
    private static final FunctionDescriptor CACHE_SLOT_DESC = FunctionDescriptor.of(C_INT, C_LONG, C_INT);
    private static final FunctionDescriptor ADD_WEIRD_SCALED_SAMPLER_DESC = FunctionDescriptor.of(C_INT, C_LONG, C_INT, C_LONG, C_INT);
    private static final FunctionDescriptor ADD_INTERPOLATED_NOISE_DESC = FunctionDescriptor.of(C_INT, C_LONG, C_LONG);
    private static final FunctionDescriptor ADD_SPLINE_DESC = FunctionDescriptor.of(C_INT, C_LONG, C_INT);
    private static final FunctionDescriptor ADD_BEARDIFIER_DESC = FunctionDescriptor.of(C_INT, C_LONG, C_LONG);
    private static final FunctionDescriptor EVALUATE_Y_COLUMN_DESC = FunctionDescriptor.ofVoid(C_LONG, C_LONG, C_DOUBLE, C_DOUBLE, C_DOUBLE, C_DOUBLE, C_INT, C_INT, C_INT, NativeFfm.C_POINTER);
    private static final FunctionDescriptor EVALUATE_Y_COLUMNS_DESC = FunctionDescriptor.ofVoid(NativeFfm.C_POINTER, NativeFfm.C_POINTER, C_INT, C_DOUBLE, C_DOUBLE, C_DOUBLE, C_DOUBLE, C_INT, C_INT, C_INT, NativeFfm.C_POINTER);
    private static final FunctionDescriptor EVALUATE_INTERPOLATED_COLUMNS_DESC = FunctionDescriptor.ofVoid(NativeFfm.C_POINTER, NativeFfm.C_POINTER, C_INT, C_DOUBLE, C_DOUBLE, C_DOUBLE, C_INT, C_INT, C_INT, C_INT, C_INT, C_INT, NativeFfm.C_POINTER, NativeFfm.C_POINTER, NativeFfm.C_POINTER, NativeFfm.C_POINTER, C_INT);

    private static volatile MethodHandle create;
    private static volatile MethodHandle destroy;
    private static volatile MethodHandle setRoot;
    private static volatile MethodHandle createCache;
    private static volatile MethodHandle destroyCache;
    private static volatile MethodHandle clearCache;
    private static volatile MethodHandle addConstant;
    private static volatile MethodHandle addUnary;
    private static volatile MethodHandle addBinary;
    private static volatile MethodHandle addYGradient;
    private static volatile MethodHandle addClamp;
    private static volatile MethodHandle addBlendAlpha;
    private static volatile MethodHandle addBlendOffset;
    private static volatile MethodHandle addBlendDensity;
    private static volatile MethodHandle addNoise;
    private static volatile MethodHandle addShiftedNoise;
    private static volatile MethodHandle addShift;
    private static volatile MethodHandle addRangeChoice;
    private static volatile MethodHandle addMapRange;
    private static volatile MethodHandle addCache;
    private static volatile MethodHandle addCacheAllInCellValue;
    private static volatile MethodHandle cacheSlot;
    private static volatile MethodHandle addWeirdScaledSampler;
    private static volatile MethodHandle addInterpolatedNoise;
    private static volatile MethodHandle addSpline;
    private static volatile MethodHandle addBeardifier;
    private static volatile MethodHandle evaluateYColumn;
    private static volatile MethodHandle evaluateYColumns;
    private static volatile MethodHandle evaluateInterpolatedColumns;
    private static final ThreadLocal<NativeFfm.DoubleBuffer> Y_COLUMN_BUFFER = ThreadLocal.withInitial(NativeFfm.DoubleBuffer::new);
    private static final ThreadLocal<BatchBuffers> Y_COLUMNS_BUFFERS = ThreadLocal.withInitial(BatchBuffers::new);
    private static final ThreadLocal<BatchBuffers> INTERPOLATED_COLUMNS_BUFFERS = ThreadLocal.withInitial(BatchBuffers::new);

    private NativeDensityFunctionFfm() {}

    static long create() {
        try {
            MethodHandle handle = createHandle();
            return handle == null ? 0L : (long) handle.invokeExact();
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    static boolean destroy(long handle) {
        try {
            MethodHandle method = destroyHandle();
            if (method == null) return false;
            method.invokeExact(handle);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    static boolean setRoot(long handle, int root) {
        try {
            MethodHandle method = setRootHandle();
            if (method == null) return false;
            method.invokeExact(handle, root);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    static long createCache(long handle) {
        try {
            MethodHandle method = createCacheHandle();
            return method == null ? 0L : (long) method.invokeExact(handle);
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    static boolean destroyCache(long cacheHandle) {
        try {
            MethodHandle method = destroyCacheHandle();
            if (method == null) return false;
            method.invokeExact(cacheHandle);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    static boolean clearCache(long cacheHandle) {
        try {
            MethodHandle method = clearCacheHandle();
            if (method == null) return false;
            method.invokeExact(cacheHandle);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    static int addConstant(long handle, double value) {
        try {
            MethodHandle method = addConstantHandle();
            return method == null ? -1 : (int) method.invokeExact(handle, value);
        } catch (Throwable ignored) {
            return -1;
        }
    }

    static int addUnary(long handle, int kind, int input) {
        try {
            MethodHandle method = addUnaryHandle();
            return method == null ? -1 : (int) method.invokeExact(handle, kind, input);
        } catch (Throwable ignored) {
            return -1;
        }
    }

    static int addBinary(long handle, int kind, int left, int right) {
        try {
            MethodHandle method = addBinaryHandle();
            return method == null ? -1 : (int) method.invokeExact(handle, kind, left, right);
        } catch (Throwable ignored) {
            return -1;
        }
    }

    static int addYGradient(long handle, int fromY, int toY, double fromValue, double toValue) {
        try {
            MethodHandle method = addYGradientHandle();
            return method == null ? -1 : (int) method.invokeExact(handle, fromY, toY, fromValue, toValue);
        } catch (Throwable ignored) {
            return -1;
        }
    }

    static int addClamp(long handle, int input, double minValue, double maxValue) {
        try {
            MethodHandle method = addClampHandle();
            return method == null ? -1 : (int) method.invokeExact(handle, input, minValue, maxValue);
        } catch (Throwable ignored) {
            return -1;
        }
    }

    static int addBlendAlpha(long handle) {
        return invokeInt(addBlendAlphaHandle(), handle);
    }

    static int addBlendOffset(long handle) {
        return invokeInt(addBlendOffsetHandle(), handle);
    }

    static int addBlendDensity(long handle, int input) {
        try {
            MethodHandle method = addBlendDensityHandle();
            return method == null ? -1 : (int) method.invokeExact(handle, input);
        } catch (Throwable ignored) {
            return -1;
        }
    }

    static int addNoise(long handle, long noiseHandle, double scaleXZ, double scaleY) {
        try {
            MethodHandle method = addNoiseHandle();
            return method == null ? -1 : (int) method.invokeExact(handle, noiseHandle, scaleXZ, scaleY);
        } catch (Throwable ignored) {
            return -1;
        }
    }

    static int addShiftedNoise(long handle, int shiftX, int shiftY, int shiftZ, long noiseHandle, double xzScale, double yScale) {
        try {
            MethodHandle method = addShiftedNoiseHandle();
            return method == null ? -1 : (int) method.invokeExact(handle, shiftX, shiftY, shiftZ, noiseHandle, xzScale, yScale);
        } catch (Throwable ignored) {
            return -1;
        }
    }

    static int addShift(long handle, int kind, long noiseHandle) {
        try {
            MethodHandle method = addShiftHandle();
            return method == null ? -1 : (int) method.invokeExact(handle, kind, noiseHandle);
        } catch (Throwable ignored) {
            return -1;
        }
    }

    static int addRangeChoice(long handle, int input, double minInclusive, double maxExclusive, int whenIn, int whenOut) {
        try {
            MethodHandle method = addRangeChoiceHandle();
            return method == null ? -1 : (int) method.invokeExact(handle, input, minInclusive, maxExclusive, whenIn, whenOut);
        } catch (Throwable ignored) {
            return -1;
        }
    }

    static int addMapRange(long handle, int input, double fromLow, double fromHigh, double toLow, double toHigh) {
        try {
            MethodHandle method = addMapRangeHandle();
            return method == null ? -1 : (int) method.invokeExact(handle, input, fromLow, fromHigh, toLow, toHigh);
        } catch (Throwable ignored) {
            return -1;
        }
    }

    static int addCache(long handle, int kind, int input) {
        try {
            MethodHandle method = addCacheHandle();
            return method == null ? -1 : (int) method.invokeExact(handle, kind, input);
        } catch (Throwable ignored) {
            return -1;
        }
    }

    static int addCacheAllInCellValue(long handle) {
        return invokeInt(addCacheAllInCellValueHandle(), handle);
    }

    static int cacheSlot(long handle, int nodeRef) {
        try {
            MethodHandle method = cacheSlotHandle();
            return method == null ? -1 : (int) method.invokeExact(handle, nodeRef);
        } catch (Throwable ignored) {
            return -1;
        }
    }

    static int addWeirdScaledSampler(long handle, int input, long noiseHandle, int type) {
        try {
            MethodHandle method = addWeirdScaledSamplerHandle();
            return method == null ? -1 : (int) method.invokeExact(handle, input, noiseHandle, type);
        } catch (Throwable ignored) {
            return -1;
        }
    }

    static int addInterpolatedNoise(long handle, long samplerHandle) {
        try {
            MethodHandle method = addInterpolatedNoiseHandle();
            return method == null ? -1 : (int) method.invokeExact(handle, samplerHandle);
        } catch (Throwable ignored) {
            return -1;
        }
    }

    static int addSpline(long handle, int splineRef) {
        try {
            MethodHandle method = addSplineHandle();
            return method == null ? -1 : (int) method.invokeExact(handle, splineRef);
        } catch (Throwable ignored) {
            return -1;
        }
    }

    static int addBeardifier(long handle, long beardifierHandle) {
        try {
            MethodHandle method = addBeardifierHandle();
            return method == null ? -1 : (int) method.invokeExact(handle, beardifierHandle);
        } catch (Throwable ignored) {
            return -1;
        }
    }

    static boolean evaluateYColumn(long handle, long cacheHandle, double x, double y0, double z, double dy, int cellX, int cellZ, double[] out) {
        if (out == null) return false;
        try {
            MethodHandle method = evaluateYColumnHandle();
            if (method == null) return false;
            MemorySegment buffer = Y_COLUMN_BUFFER.get().ensure(out.length);
            method.invokeExact(handle, cacheHandle, x, y0, z, dy, cellX, cellZ, out.length, buffer);
            MemorySegment.copy(buffer, NativeFfm.C_DOUBLE, 0, out, 0, out.length);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    static boolean evaluateYColumnToSegment(long handle, long cacheHandle, double x, double y0, double z, double dy, int cellX, int cellZ, int length, MemorySegment out) {
        if (out == null || out == MemorySegment.NULL) return false;
        try {
            MethodHandle method = evaluateYColumnHandle();
            if (method == null) return false;
            method.invokeExact(handle, cacheHandle, x, y0, z, dy, cellX, cellZ, length, out);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    static boolean evaluateYColumns(long[] handles, long[] cacheHandles, int count, double x, double y0, double z, double dy, int cellX, int cellZ, int ny, double[][] out) {
        if (handles == null || cacheHandles == null || out == null || count <= 0 || ny <= 0) return false;
        if (handles.length < count || cacheHandles.length < count || out.length < count) return false;
        try {
            MethodHandle method = evaluateYColumnsHandle();
            if (method == null) return false;
            BatchBuffers buffers = Y_COLUMNS_BUFFERS.get();
            MemorySegment handlesSegment = buffers.handles.ensure(count);
            MemorySegment cacheHandlesSegment = buffers.cacheHandles.ensure(count);
            MemorySegment outSegment = buffers.outputs.ensure(count * ny);
            MemorySegment.copy(handles, 0, handlesSegment, NativeFfm.C_LONG, 0, count);
            MemorySegment.copy(cacheHandles, 0, cacheHandlesSegment, NativeFfm.C_LONG, 0, count);
            method.invokeExact(handlesSegment, cacheHandlesSegment, count, x, y0, z, dy, cellX, cellZ, ny, outSegment);
            for (int i = 0; i < count; i++) {
                double[] row = out[i];
                if (row == null || row.length < ny) return false;
                MemorySegment.copy(outSegment, NativeFfm.C_DOUBLE, (long) i * (long) ny * NativeFfm.C_DOUBLE.byteSize(), row, 0, ny);
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    static boolean evaluateInterpolatedColumns(long[] handles,
                                               long[] cacheHandles,
                                               double[][][] cacheAllInCellValues,
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
        if (handles == null || cacheHandles == null || out == null || count <= 0) return false;
        if (handles.length < count || cacheHandles.length < count || out.length < count) return false;
        int cellValueCount = cellWidth * cellHeight * cellWidth;
        int columnValueCount = cellCountXZ * cellCountY * cellValueCount;
        try {
            MethodHandle method = evaluateInterpolatedColumnsHandle();
            if (method == null) return false;
            BatchBuffers buffers = INTERPOLATED_COLUMNS_BUFFERS.get();
            MemorySegment handlesSegment = buffers.handles.ensure(count);
            MemorySegment cacheHandlesSegment = buffers.cacheHandles.ensure(count);
            MemorySegment outSegment = buffers.outputs.ensure(count * columnValueCount);
            MemorySegment.copy(handles, 0, handlesSegment, NativeFfm.C_LONG, 0, count);
            MemorySegment.copy(cacheHandles, 0, cacheHandlesSegment, NativeFfm.C_LONG, 0, count);

            int maxCacheSlots = maxCacheSlots(cacheAllInCellValues, count);
            int slotCount = Math.max(1, maxCacheSlots) * count;
            MemorySegment offsetsSegment = buffers.cacheOffsets.ensure(slotCount);
            MemorySegment lengthsSegment = buffers.cacheLengths.ensure(slotCount);
            long packedValueCount = prepareCacheValues(cacheAllInCellValues, count, maxCacheSlots, buffers.cacheValues, offsetsSegment, lengthsSegment);
            MemorySegment cacheValuesSegment = packedValueCount == 0L ? MemorySegment.NULL : buffers.cacheValues.ensure((int) packedValueCount);

            method.invokeExact(
                    handlesSegment,
                    cacheHandlesSegment,
                    count,
                    x0,
                    z0,
                    yMin,
                    cellX,
                    firstCellZ,
                    cellWidth,
                    cellHeight,
                    cellCountXZ,
                    cellCountY,
                    outSegment,
                    cacheValuesSegment,
                    offsetsSegment,
                    lengthsSegment,
                    maxCacheSlots);
            for (int i = 0; i < count; i++) {
                double[] row = out[i];
                if (row == null || row.length < columnValueCount) return false;
                MemorySegment.copy(outSegment, NativeFfm.C_DOUBLE, (long) i * (long) columnValueCount * NativeFfm.C_DOUBLE.byteSize(), row, 0, columnValueCount);
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static int invokeInt(MethodHandle method, long value) {
        try {
            return method == null ? -1 : (int) method.invokeExact(value);
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static MethodHandle createHandle() { return resolve(create, "lattice_density_create", CREATE_DESC, value -> create = value); }
    private static MethodHandle destroyHandle() { return resolve(destroy, "lattice_density_destroy", DESTROY_DESC, value -> destroy = value); }
    private static MethodHandle setRootHandle() { return resolve(setRoot, "lattice_density_set_root", SET_ROOT_DESC, value -> setRoot = value); }
    private static MethodHandle createCacheHandle() { return resolve(createCache, "lattice_density_create_cache", CREATE_CACHE_DESC, value -> createCache = value); }
    private static MethodHandle destroyCacheHandle() { return resolve(destroyCache, "lattice_density_destroy_cache", DESTROY_CACHE_DESC, value -> destroyCache = value); }
    private static MethodHandle clearCacheHandle() { return resolve(clearCache, "lattice_density_clear_cache", CLEAR_CACHE_DESC, value -> clearCache = value); }
    private static MethodHandle addConstantHandle() { return resolve(addConstant, "lattice_density_add_constant", ADD_CONSTANT_DESC, value -> addConstant = value); }
    private static MethodHandle addUnaryHandle() { return resolve(addUnary, "lattice_density_add_unary", ADD_UNARY_DESC, value -> addUnary = value); }
    private static MethodHandle addBinaryHandle() { return resolve(addBinary, "lattice_density_add_binary", ADD_BINARY_DESC, value -> addBinary = value); }
    private static MethodHandle addYGradientHandle() { return resolve(addYGradient, "lattice_density_add_y_clamped_gradient", ADD_Y_GRADIENT_DESC, value -> addYGradient = value); }
    private static MethodHandle addClampHandle() { return resolve(addClamp, "lattice_density_add_clamp", ADD_CLAMP_DESC, value -> addClamp = value); }
    private static MethodHandle addBlendAlphaHandle() { return resolve(addBlendAlpha, "lattice_density_add_blend_alpha", ADD_BLEND_ALPHA_DESC, value -> addBlendAlpha = value); }
    private static MethodHandle addBlendOffsetHandle() { return resolve(addBlendOffset, "lattice_density_add_blend_offset", ADD_BLEND_OFFSET_DESC, value -> addBlendOffset = value); }
    private static MethodHandle addBlendDensityHandle() { return resolve(addBlendDensity, "lattice_density_add_blend_density", ADD_BLEND_DENSITY_DESC, value -> addBlendDensity = value); }
    private static MethodHandle addNoiseHandle() { return resolve(addNoise, "lattice_density_add_noise", ADD_NOISE_DESC, value -> addNoise = value); }
    private static MethodHandle addShiftedNoiseHandle() { return resolve(addShiftedNoise, "lattice_density_add_shifted_noise", ADD_SHIFTED_NOISE_DESC, value -> addShiftedNoise = value); }
    private static MethodHandle addShiftHandle() { return resolve(addShift, "lattice_density_add_shift", ADD_SHIFT_DESC, value -> addShift = value); }
    private static MethodHandle addRangeChoiceHandle() { return resolve(addRangeChoice, "lattice_density_add_range_choice", ADD_RANGE_CHOICE_DESC, value -> addRangeChoice = value); }
    private static MethodHandle addMapRangeHandle() { return resolve(addMapRange, "lattice_density_add_map_range", ADD_MAP_RANGE_DESC, value -> addMapRange = value); }
    private static MethodHandle addCacheHandle() { return resolve(addCache, "lattice_density_add_cache", ADD_CACHE_DESC, value -> addCache = value); }
    private static MethodHandle addCacheAllInCellValueHandle() { return resolve(addCacheAllInCellValue, "lattice_density_add_cache_all_in_cell_value", ADD_CACHE_ALL_IN_CELL_VALUE_DESC, value -> addCacheAllInCellValue = value); }
    private static MethodHandle cacheSlotHandle() { return resolve(cacheSlot, "lattice_density_cache_slot", CACHE_SLOT_DESC, value -> cacheSlot = value); }
    private static MethodHandle addWeirdScaledSamplerHandle() { return resolve(addWeirdScaledSampler, "lattice_density_add_weird_scaled_sampler", ADD_WEIRD_SCALED_SAMPLER_DESC, value -> addWeirdScaledSampler = value); }
    private static MethodHandle addInterpolatedNoiseHandle() { return resolve(addInterpolatedNoise, "lattice_density_add_interpolated_noise", ADD_INTERPOLATED_NOISE_DESC, value -> addInterpolatedNoise = value); }
    private static MethodHandle addSplineHandle() { return resolve(addSpline, "lattice_density_add_spline", ADD_SPLINE_DESC, value -> addSpline = value); }
    private static MethodHandle addBeardifierHandle() { return resolve(addBeardifier, "lattice_density_add_beardifier", ADD_BEARDIFIER_DESC, value -> addBeardifier = value); }
    private static MethodHandle evaluateYColumnHandle() { return resolve(evaluateYColumn, "lattice_density_evaluate_y_column", EVALUATE_Y_COLUMN_DESC, value -> evaluateYColumn = value); }
    private static MethodHandle evaluateYColumnsHandle() { return resolve(evaluateYColumns, "lattice_density_evaluate_y_columns", EVALUATE_Y_COLUMNS_DESC, value -> evaluateYColumns = value); }
    private static MethodHandle evaluateInterpolatedColumnsHandle() { return resolve(evaluateInterpolatedColumns, "lattice_density_evaluate_interpolated_columns", EVALUATE_INTERPOLATED_COLUMNS_DESC, value -> evaluateInterpolatedColumns = value); }

    private static int maxCacheSlots(double[][][] cacheAllInCellValues, int count) {
        int max = 0;
        if (cacheAllInCellValues == null) return 0;
        for (int i = 0; i < count && i < cacheAllInCellValues.length; i++) {
            double[][] values = cacheAllInCellValues[i];
            if (values != null) max = Math.max(max, values.length);
        }
        return max;
    }

    private static long prepareCacheValues(double[][][] cacheAllInCellValues,
                                           int count,
                                           int maxCacheSlots,
                                           NativeFfm.DoubleBuffer cacheValueBuffer,
                                           MemorySegment offsetsSegment,
                                           MemorySegment lengthsSegment) {
        int slots = Math.max(1, maxCacheSlots);
        for (int i = 0; i < count * slots; i++) {
            offsetsSegment.set(NativeFfm.C_LONG, (long) i * NativeFfm.C_LONG.byteSize(), 0L);
            lengthsSegment.set(NativeFfm.C_LONG, (long) i * NativeFfm.C_LONG.byteSize(), 0L);
        }
        if (cacheAllInCellValues == null || maxCacheSlots <= 0) return 0L;
        long total = 0L;
        for (int i = 0; i < count && i < cacheAllInCellValues.length; i++) {
            double[][] values = cacheAllInCellValues[i];
            if (values == null) continue;
            for (double[] value : values) {
                if (value != null) total += value.length;
            }
        }
        if (total <= 0L || total > Integer.MAX_VALUE) return 0L;
        MemorySegment cacheValuesSegment = cacheValueBuffer.ensure((int) total);
        long offset = 0L;
        for (int i = 0; i < count && i < cacheAllInCellValues.length; i++) {
            double[][] values = cacheAllInCellValues[i];
            if (values == null) continue;
            for (int slot = 0; slot < Math.min(values.length, maxCacheSlots); slot++) {
                double[] value = values[slot];
                if (value == null || value.length == 0) continue;
                int index = i * maxCacheSlots + slot;
                offsetsSegment.set(NativeFfm.C_LONG, (long) index * NativeFfm.C_LONG.byteSize(), offset);
                lengthsSegment.set(NativeFfm.C_LONG, (long) index * NativeFfm.C_LONG.byteSize(), (long) value.length);
                MemorySegment.copy(value, 0, cacheValuesSegment, NativeFfm.C_DOUBLE, offset * NativeFfm.C_DOUBLE.byteSize(), value.length);
                offset += value.length;
            }
        }
        return total;
    }

    private static MethodHandle resolve(MethodHandle cached, String symbol, FunctionDescriptor descriptor, HandleSetter setter) {
        if (cached != null) return cached;
        MethodHandle resolved = NativeFfm.downcall(symbol, descriptor);
        if (resolved != null) setter.set(resolved);
        return resolved;
    }

    @FunctionalInterface
    private interface HandleSetter {
        void set(MethodHandle handle);
    }

    private static final class BatchBuffers {
        private final NativeFfm.LongBuffer handles = new NativeFfm.LongBuffer();
        private final NativeFfm.LongBuffer cacheHandles = new NativeFfm.LongBuffer();
        private final NativeFfm.DoubleBuffer outputs = new NativeFfm.DoubleBuffer();
        private final NativeFfm.DoubleBuffer cacheValues = new NativeFfm.DoubleBuffer();
        private final NativeFfm.LongBuffer cacheOffsets = new NativeFfm.LongBuffer();
        private final NativeFfm.LongBuffer cacheLengths = new NativeFfm.LongBuffer();
    }
}
