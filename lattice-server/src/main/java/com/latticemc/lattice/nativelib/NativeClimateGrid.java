package com.latticemc.lattice.nativelib;

import net.minecraft.core.QuartPos;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;

/** A thread-local native climate grid with exact Java fallback semantics. */
public final class NativeClimateGrid implements DensityFunction.SimpleFunction {
    private static final ThreadLocal<Buffer> BUFFERS = ThreadLocal.withInitial(Buffer::new);

    private final DensityFunction delegate;
    private final Buffer buffer;
    private final Thread owner;
    private final int generation;
    private final int rootOffset;
    private final int firstQuartX;
    private final int firstQuartY;
    private final int firstQuartZ;
    private final int sizeX;
    private final int sizeY;
    private final int sizeZ;

    NativeClimateGrid(DensityFunction delegate,
                      Buffer buffer,
                      int generation,
                      int rootOffset,
                      int firstQuartX,
                      int firstQuartY,
                      int firstQuartZ,
                      int sizeX,
                      int sizeY,
                      int sizeZ) {
        this.delegate = delegate;
        this.buffer = buffer;
        this.owner = Thread.currentThread();
        this.generation = generation;
        this.rootOffset = rootOffset;
        this.firstQuartX = firstQuartX;
        this.firstQuartY = firstQuartY;
        this.firstQuartZ = firstQuartZ;
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
    }

    public static DensityFunction[] tryCreate(DensityFunction[] functions,
                                               Object arenaKey,
                                               int firstQuartX,
                                               int firstQuartY,
                                               int firstQuartZ,
                                               int sizeX,
                                               int sizeY,
                                               int sizeZ) {
        if (functions == null || functions.length == 0 || sizeX <= 0 || sizeY <= 0 || sizeZ <= 0) return null;
        long rootStrideLong = (long) sizeX * sizeY * sizeZ;
        long requiredLong = rootStrideLong * functions.length;
        if (rootStrideLong > Integer.MAX_VALUE || requiredLong > Integer.MAX_VALUE) return null;

        Buffer buffer = BUFFERS.get();
        int required = (int) requiredLong;
        buffer.ensureCapacity(required);
        int generation = buffer.nextGeneration();
        double[] values = buffer.values;
        if (!NativeDensityFunction.tryFillGridRoots(
                values,
                functions,
                arenaKey,
                QuartPos.toBlock(firstQuartX),
                QuartPos.toBlock(firstQuartY),
                QuartPos.toBlock(firstQuartZ),
                4.0,
                4.0,
                4.0,
                firstQuartX,
                firstQuartZ,
                sizeX,
                sizeY,
                sizeZ)) {
            return null;
        }

        if (NativeDensityFunction.shouldCheckParity()) {
            verifyParity(values, functions, firstQuartX, firstQuartY, firstQuartZ, sizeX, sizeY, sizeZ);
        }

        int rootStride = (int) rootStrideLong;
        DensityFunction[] cached = new DensityFunction[functions.length];
        for (int i = 0; i < functions.length; i++) {
            cached[i] = new NativeClimateGrid(
                    functions[i], buffer, generation, i * rootStride,
                    firstQuartX, firstQuartY, firstQuartZ, sizeX, sizeY, sizeZ);
        }
        return cached;
    }

    private static void verifyParity(double[] nativeValues,
                                     DensityFunction[] functions,
                                     int firstQuartX,
                                     int firstQuartY,
                                     int firstQuartZ,
                                     int sizeX,
                                     int sizeY,
                                     int sizeZ) {
        int rootStride = sizeX * sizeY * sizeZ;
        double[] javaValues = new double[rootStride];
        for (int root = 0; root < functions.length; root++) {
            DensityFunction function = functions[root];
            int index = 0;
            for (int x = 0; x < sizeX; x++) {
                int blockX = QuartPos.toBlock(firstQuartX + x);
                for (int z = 0; z < sizeZ; z++) {
                    int blockZ = QuartPos.toBlock(firstQuartZ + z);
                    for (int y = 0; y < sizeY; y++) {
                        int blockY = QuartPos.toBlock(firstQuartY + y);
                        javaValues[index++] = function.compute(new DensityFunction.SinglePointContext(blockX, blockY, blockZ));
                    }
                }
            }
            NativeDensityFunction.recordParity(
                    "climateBatch[" + root + ']',
                    function,
                    nativeValues,
                    root * rootStride,
                    javaValues,
                    rootStride);
        }
    }

    @Override
    public double compute(DensityFunction.FunctionContext context) {
        if (Thread.currentThread() != this.owner || this.buffer.generation != this.generation) {
            return this.delegate.compute(context);
        }

        int deltaX = context.blockX() - QuartPos.toBlock(this.firstQuartX);
        int deltaY = context.blockY() - QuartPos.toBlock(this.firstQuartY);
        int deltaZ = context.blockZ() - QuartPos.toBlock(this.firstQuartZ);
        if ((deltaX & 3) != 0 || (deltaY & 3) != 0 || (deltaZ & 3) != 0) {
            return this.delegate.compute(context);
        }

        int x = deltaX >> 2;
        int y = deltaY >> 2;
        int z = deltaZ >> 2;
        if (x < 0 || x >= this.sizeX || y < 0 || y >= this.sizeY || z < 0 || z >= this.sizeZ) {
            return this.delegate.compute(context);
        }
        return this.buffer.values[this.rootOffset + (x * this.sizeZ + z) * this.sizeY + y];
    }

    @Override
    public double minValue() {
        return this.delegate.minValue();
    }

    @Override
    public double maxValue() {
        return this.delegate.maxValue();
    }

    @Override
    public KeyDispatchDataCodec<? extends DensityFunction> codec() {
        return this.delegate.codec();
    }

    static final class Buffer {
        double[] values = new double[0];
        int generation;

        void ensureCapacity(int required) {
            if (this.values.length < required) this.values = new double[required];
        }

        int nextGeneration() {
            this.generation++;
            if (this.generation == 0) this.generation = 1;
            return this.generation;
        }
    }
}
