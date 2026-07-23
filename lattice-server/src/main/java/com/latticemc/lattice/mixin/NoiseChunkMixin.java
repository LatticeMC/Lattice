package com.latticemc.lattice.mixin;

import com.latticemc.lattice.nativelib.NativeCacheAllInCellAccess;
import com.latticemc.lattice.nativelib.DirectCellColumnCache;
import com.latticemc.lattice.nativelib.NativeDensityFunction;
import com.latticemc.lattice.nativelib.NativeNoiseChunkAccess;
import com.latticemc.lattice.nativelib.NativeNoiseInterpolatorAccess;
import com.latticemc.lattice.nativelib.WorldgenProfiler;
import java.util.IdentityHashMap;
import java.util.List;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(NoiseChunk.class)
public abstract class NoiseChunkMixin implements NativeNoiseChunkAccess {
    @Shadow @Final private int firstCellZ;
    @Shadow @Final int cellWidth;
    @Shadow @Final int cellHeight;
    @Shadow @Final int cellCountXZ;
    @Shadow @Final int cellCountY;
    @Shadow @Final int cellNoiseMinY;
    @Shadow @Final List<NoiseChunk.NoiseInterpolator> interpolators;
    @Shadow @Final List<?> cellCaches;
    @Shadow @Final private DensityFunction.ContextProvider sliceFillingContextProvider;
    @Shadow private int cellStartBlockX;
    @Shadow int cellStartBlockY;
    @Shadow private int cellStartBlockZ;
    @Shadow boolean fillingCell;
    @Shadow boolean interpolating;
    @Shadow private int inCellX;
    @Shadow private int inCellY;
    @Shadow private int inCellZ;
    @Shadow long interpolationCounter;
    @Shadow long arrayInterpolationCounter;
    @Unique private long lattice$initializeForFirstCellXStart;
    @Unique private long lattice$advanceCellXStart;
    @Unique private long lattice$fillAllDirectlyStart;
    @Unique private final IdentityHashMap<DensityFunction, DirectCellColumnCache> lattice$directCellColumns = new IdentityHashMap<>();
    @Unique private Object lattice$sliceArenaKey;
    @Shadow @Final private NoiseChunk.NoiseInterpolator[] interpolatorArray;
    @Unique private int lattice$preparedCacheColumnCellX = Integer.MIN_VALUE;
    @Unique private boolean lattice$cellColumnsJavaOnly;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void lattice$captureSliceArenaKey(int cellCountXZ,
                                               RandomState random,
                                               int firstNoiseX,
                                               int firstNoiseZ,
                                               NoiseSettings noiseSettings,
                                               DensityFunctions.BeardifierOrMarker beardifier,
                                               NoiseGeneratorSettings noiseGeneratorSettings,
                                               Aquifer.FluidPicker fluidPicker,
                                               Blender blender,
                                               CallbackInfo ci) {
        this.lattice$sliceArenaKey = random.router();
    }

    @Inject(method = "initializeForFirstCellX", at = @At("HEAD"))
    private void lattice$profileInitializeForFirstCellXStart(CallbackInfo ci) {
        if (!WorldgenProfiler.available()) return;
        this.lattice$initializeForFirstCellXStart = WorldgenProfiler.start();
    }

    @Inject(method = "initializeForFirstCellX", at = @At("RETURN"))
    private void lattice$profileInitializeForFirstCellXEnd(CallbackInfo ci) {
        if (!WorldgenProfiler.available()) return;
        WorldgenProfiler.end("noise.initializeForFirstCellX", this.lattice$initializeForFirstCellXStart);
    }

    @Inject(method = "advanceCellX", at = @At("HEAD"))
    private void lattice$profileAdvanceCellXStart(int increment, CallbackInfo ci) {
        if (!WorldgenProfiler.available()) return;
        this.lattice$advanceCellXStart = WorldgenProfiler.start();
    }

    @Inject(method = "advanceCellX", at = @At("RETURN"))
    private void lattice$profileAdvanceCellXEnd(int increment, CallbackInfo ci) {
        if (!WorldgenProfiler.available()) return;
        WorldgenProfiler.end("noise.advanceCellX", this.lattice$advanceCellXStart);
    }

    @Inject(method = "fillAllDirectly", at = @At("HEAD"))
    private void lattice$profileFillAllDirectlyStart(double[] values, DensityFunction function, CallbackInfo ci) {
        this.lattice$fillAllDirectlyStart = WorldgenProfiler.hotLoopStart();
    }

    @Inject(method = "fillAllDirectly", at = @At("RETURN"))
    private void lattice$profileFillAllDirectlyEnd(double[] values, DensityFunction function, CallbackInfo ci) {
        WorldgenProfiler.end("noise.fillAllDirectly", this.lattice$fillAllDirectlyStart);
    }

    @Inject(method = "fillAllDirectly", at = @At("HEAD"), cancellable = true)
    private void lattice$fillCellNative(double[] values, DensityFunction function, CallbackInfo ci) {
        if (NativeDensityFunction.bypassFillAllDirectly()) return;
        if (!this.fillingCell) return;
        if (!NativeDensityFunction.shouldTryFillCellDirect()) return;
        int startX = this.cellStartBlockX;
        int startZ = this.cellStartBlockZ;
        int cellX = Math.floorDiv(startX, this.cellWidth);
        int cellZ = Math.floorDiv(startZ, this.cellWidth);
        int localCellY = Math.floorDiv(this.cellStartBlockY, this.cellHeight) - this.cellNoiseMinY;
        int localCellZ = cellZ - this.firstCellZ;
        int cellValueCount = this.cellWidth * this.cellHeight * this.cellWidth;
        int columnValueCount = this.cellCountXZ * this.cellCountY * cellValueCount;
        boolean parityCheck = NativeDensityFunction.shouldCheckParity();
        if (NativeDensityFunction.shouldTryFillCellColumnDirect()) {
            DirectCellColumnCache column = this.lattice$directCellColumns.get(function);
            if (column != null && column.rejected) return;
            if (column == null) {
                column = new DirectCellColumnCache();
                this.lattice$directCellColumns.put(function, column);
            }
            if (column.cellX != cellX) {
                if (column.values == null || column.values.length < columnValueCount) {
                    NativeDensityFunction.releaseDirectCellColumnBuffer(column.values);
                    column.values = NativeDensityFunction.acquireDirectCellColumnBuffer(columnValueCount);
                }
                column.available = NativeDensityFunction.tryFillCellColumnDirect(
                        column.values,
                        function,
                        startX,
                        this.firstCellZ,
                        this.cellNoiseMinY,
                        this.cellWidth,
                        this.cellHeight,
                        this.cellCountXZ,
                        this.cellCountY,
                        cellX);
                column.rejected = !column.available;
                column.cellX = cellX;
            }
            if (column.available) {
                int cellOffset = (localCellZ * this.cellCountY + localCellY) * cellValueCount;
                System.arraycopy(column.values, cellOffset, values, 0, cellValueCount);
                if (parityCheck) {
                    double[] javaValues = new double[values.length];
                    NativeDensityFunction.runWithFillAllDirectlyBypass(() -> function.fillArray(javaValues, (DensityFunction.ContextProvider) (Object) this));
                    NativeDensityFunction.recordParity("directCellColumn", function, values, javaValues);
                }
                WorldgenProfiler.end("noise.fillAllDirectly", this.lattice$fillAllDirectlyStart);
                ci.cancel();
                return;
            }
            if (column.rejected) return;
        }
        if (NativeDensityFunction.tryFillCellDirect(
                values,
                function,
                startX,
                this.cellStartBlockY,
                startZ,
                this.cellWidth,
                this.cellHeight,
                this.cellCountXZ,
                this.cellCountY,
                cellX,
                cellZ,
                localCellY,
                localCellZ)) {
            if (parityCheck) {
                double[] javaValues = new double[values.length];
                NativeDensityFunction.runWithFillAllDirectlyBypass(() -> function.fillArray(javaValues, (DensityFunction.ContextProvider) (Object) this));
                NativeDensityFunction.recordParity("directCell", function, values, javaValues);
            }
            WorldgenProfiler.end("noise.fillAllDirectly", this.lattice$fillAllDirectlyStart);
            ci.cancel();
        }
    }

    @Redirect(
            method = "fillSlice",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/levelgen/NoiseChunk$NoiseInterpolator;fillArray([DLnet/minecraft/world/level/levelgen/DensityFunction$ContextProvider;)V"
            )
    )
    private void lattice$fillInterpolatorSliceNative(NoiseChunk.NoiseInterpolator interpolator,
                                                        double[] values,
                                                        DensityFunction.ContextProvider contextProvider,
                                                        boolean isSlice0,
                                                        int start) {
        NativeNoiseInterpolatorAccess access = (NativeNoiseInterpolatorAccess) (Object) interpolator;
        int startX = this.cellStartBlockX;
        int startZ = this.cellStartBlockZ;
        int cellX = Math.floorDiv(startX, this.cellWidth);
        int cellZ = Math.floorDiv(startZ, this.cellWidth);
        int zRow = cellZ - this.firstCellZ;
        int yRows = this.cellCountY + 1;
        int zRows = this.cellCountXZ + 1;
        if (NativeDensityFunction.tryFillSlice(
                values,
                interpolator.wrapped(),
                startX,
                this.cellNoiseMinY * this.cellHeight,
                startZ,
                this.cellHeight,
                cellX,
                cellZ)) {
            if (NativeDensityFunction.shouldCheckParity()) {
                double[] javaValues = new double[values.length];
                interpolator.fillArray(javaValues, contextProvider);
                NativeDensityFunction.recordParity("slice", interpolator.wrapped(), values, javaValues);
            }
            access.lattice$copyFlatRow(isSlice0, zRow, values, yRows, zRows);
            return;
        }
        interpolator.fillArray(values, contextProvider);
        access.lattice$copyFlatRow(isSlice0, zRow, values, yRows, zRows);
    }

    @Inject(method = "fillSlice", at = @At("HEAD"), cancellable = true)
    private void lattice$fillSliceBatchNative(boolean isSlice0, int start, CallbackInfo ci) {
        boolean parityCheck = NativeDensityFunction.shouldCheckParity();
        long profileStart = WorldgenProfiler.start();
        int yRows = this.cellCountY + 1;
        int zRows = this.cellCountXZ + 1;
        this.cellStartBlockX = start * this.cellWidth;
        this.inCellX = 0;
        if (NativeDensityFunction.tryFillSliceRows(
                this.interpolators,
                this.lattice$sliceArenaKey,
                isSlice0,
                this.cellStartBlockX,
                this.cellNoiseMinY * this.cellHeight,
                this.firstCellZ * this.cellWidth,
                this.cellHeight,
                start,
                this.firstCellZ,
                this.cellWidth,
                yRows,
                zRows)) {
            for (int zRow = 0; zRow < zRows; zRow++) {
                this.cellStartBlockZ = (this.firstCellZ + zRow) * this.cellWidth;
                this.inCellZ = 0;
                this.arrayInterpolationCounter++;
                if (parityCheck) {
                    for (NoiseChunk.NoiseInterpolator interpolator : this.interpolators) {
                        NativeNoiseInterpolatorAccess access = (NativeNoiseInterpolatorAccess) (Object) interpolator;
                        double[] javaValues = access.lattice$sliceRow(isSlice0, zRow);
                        interpolator.fillArray(javaValues, this.sliceFillingContextProvider);
                        double[] nativeValues = isSlice0 ? access.lattice$flatSlice0() : access.lattice$flatSlice1();
                        NativeDensityFunction.recordParitySliceRow(
                                "sliceSharedJava",
                                interpolator.wrapped(),
                                nativeValues,
                                zRow * yRows,
                                javaValues,
                                yRows);
                    }
                }
            }
            this.arrayInterpolationCounter++;
            WorldgenProfiler.end("noise.fillSlice", profileStart);
            ci.cancel();
            return;
        }
        for (int zRow = 0; zRow < zRows; zRow++) {
            int cellZ = this.firstCellZ + zRow;
            this.cellStartBlockZ = cellZ * this.cellWidth;
            this.inCellZ = 0;
            this.arrayInterpolationCounter++;
            if (NativeDensityFunction.tryFillSlices(
                    this.interpolators,
                    this.sliceFillingContextProvider,
                    isSlice0,
                    zRow,
                    this.cellStartBlockX,
                    this.cellNoiseMinY * this.cellHeight,
                    this.cellStartBlockZ,
                    this.cellHeight,
                    start,
                    cellZ,
                    yRows,
                    zRows)) {
                continue;
            }
            for (NoiseChunk.NoiseInterpolator noiseInterpolator : this.interpolators) {
                NativeNoiseInterpolatorAccess access = (NativeNoiseInterpolatorAccess) (Object) noiseInterpolator;
                double[] values = access.lattice$sliceRow(isSlice0, zRow);
                noiseInterpolator.fillArray(values, this.sliceFillingContextProvider);
                access.lattice$copyFlatRow(isSlice0, zRow, values, yRows, zRows);
            }
        }
        this.arrayInterpolationCounter++;
        WorldgenProfiler.end("noise.fillSlice", profileStart);
        ci.cancel();
    }

    /**
     * @author Lattice
     * @reason Fill CacheAllInCell columns through one native call per cache/X column.
     */
    @Overwrite
    public void selectCellYZ(int y, int z) {
        long profileStart = WorldgenProfiler.hotLoopStart();
        try {
            NoiseChunk.NoiseInterpolator[] interpolators = this.interpolatorArray;
            for (int i = 0; i < interpolators.length; i++) {
                ((NativeNoiseInterpolatorAccess) (Object) interpolators[i]).lattice$selectCellYZ(y, z);
            }

            this.fillingCell = true;
            this.cellStartBlockY = (y + this.cellNoiseMinY) * this.cellHeight;
            this.cellStartBlockZ = (this.firstCellZ + z) * this.cellWidth;
            this.arrayInterpolationCounter++;

            int cellX = Math.floorDiv(this.cellStartBlockX, this.cellWidth);
            int cellValueCount = this.cellWidth * this.cellHeight * this.cellWidth;
            int columnValueCount = this.cellCountXZ * this.cellCountY * cellValueCount;
            int cellOffset = (z * this.cellCountY + y) * cellValueCount;
            long cacheStart = WorldgenProfiler.hotLoopStart();
            if (this.lattice$preparedCacheColumnCellX == cellX) {
                for (Object cache : this.cellCaches) {
                    NativeCacheAllInCellAccess access = (NativeCacheAllInCellAccess) cache;
                    double[] column = access.lattice$columnValues();
                    if (access.lattice$columnCellX() == cellX && column != null && column.length >= columnValueCount) {
                        System.arraycopy(column, cellOffset, access.lattice$values(), 0, cellValueCount);
                    } else {
                        access.lattice$noiseFiller().fillArray(
                                access.lattice$values(), (DensityFunction.ContextProvider) (Object) this);
                    }
                }
                WorldgenProfiler.end("noise.selectCellYZ.cache", cacheStart);
                this.arrayInterpolationCounter++;
                this.fillingCell = false;
                return;
            }
            if (!this.lattice$cellColumnsJavaOnly) {
                int columnResult = NativeDensityFunction.fillCellColumns(
                        this.cellCaches,
                        (DensityFunction.ContextProvider) (Object) this,
                        this.lattice$sliceArenaKey,
                        this.cellStartBlockX,
                        this.firstCellZ,
                        this.cellNoiseMinY,
                        this.cellWidth,
                        this.cellHeight,
                        this.cellCountXZ,
                        this.cellCountY,
                        cellX,
                        cellOffset);
                if (columnResult == NativeDensityFunction.CELL_COLUMNS_KNOWN_JAVA_ONLY) {
                    this.lattice$cellColumnsJavaOnly = true;
                } else if (columnResult >= 0) {
                    this.lattice$cellColumnsJavaOnly = columnResult == 0;
                    this.lattice$preparedCacheColumnCellX = cellX;
                    WorldgenProfiler.end("noise.selectCellYZ.cache", cacheStart);
                    this.arrayInterpolationCounter++;
                    this.fillingCell = false;
                    return;
                }
            }
            if (this.lattice$cellColumnsJavaOnly) {
                for (Object cache : this.cellCaches) {
                    NativeCacheAllInCellAccess access = (NativeCacheAllInCellAccess) cache;
                    access.lattice$noiseFiller().fillArray(
                            access.lattice$values(), (DensityFunction.ContextProvider) (Object) this);
                }
                WorldgenProfiler.end("noise.selectCellYZ.cache", cacheStart);
                this.arrayInterpolationCounter++;
                this.fillingCell = false;
                return;
            }
            for (Object cache : this.cellCaches) {
                NativeCacheAllInCellAccess access = (NativeCacheAllInCellAccess) cache;
                double[] column = access.lattice$columnValues();
                if (access.lattice$columnCellX() != cellX) {
                    if (column == null || column.length < columnValueCount) {
                        NativeDensityFunction.releaseDirectCellColumnBuffer(column);
                        column = NativeDensityFunction.acquireDirectCellColumnBuffer(columnValueCount);
                        access.lattice$setColumnValues(column);
                    }
                    if (NativeDensityFunction.tryFillCellColumn(
                            column,
                            access.lattice$noiseFiller(),
                            this.cellStartBlockX,
                            this.firstCellZ,
                            this.cellNoiseMinY,
                            this.cellWidth,
                            this.cellHeight,
                            this.cellCountXZ,
                            this.cellCountY,
                            cellX)) {
                        access.lattice$setColumnCellX(cellX);
                    } else {
                        access.lattice$noiseFiller().fillArray(access.lattice$values(), (DensityFunction.ContextProvider) (Object) this);
                        continue;
                    }
                }
                if (column != null && column.length >= columnValueCount) {
                    System.arraycopy(column, cellOffset, access.lattice$values(), 0, cellValueCount);
                } else {
                    access.lattice$noiseFiller().fillArray(access.lattice$values(), (DensityFunction.ContextProvider) (Object) this);
                }
            }
            WorldgenProfiler.end("noise.selectCellYZ.cache", cacheStart);

            this.arrayInterpolationCounter++;
            this.fillingCell = false;
        } finally {
            WorldgenProfiler.end("noise.selectCellYZ", profileStart);
        }
    }

    @Inject(method = "stopInterpolation", at = @At("RETURN"))
    private void lattice$releaseDirectCellColumns(CallbackInfo ci) {
        for (DirectCellColumnCache column : this.lattice$directCellColumns.values()) {
            NativeDensityFunction.releaseDirectCellColumnBuffer(column.values);
            column.values = null;
        }
        this.lattice$directCellColumns.clear();
        this.lattice$preparedCacheColumnCellX = Integer.MIN_VALUE;
        for (Object cache : this.cellCaches) {
            NativeCacheAllInCellAccess access = (NativeCacheAllInCellAccess) cache;
            NativeDensityFunction.releaseDirectCellColumnBuffer(access.lattice$columnValues());
            access.lattice$setColumnValues(null);
            access.lattice$setColumnCellX(Integer.MIN_VALUE);
        }
    }

    @Override
    public int lattice$cellStartBlockX() {
        return this.cellStartBlockX;
    }

    @Override
    public int lattice$cellStartBlockY() {
        return this.cellStartBlockY;
    }

    @Override
    public int lattice$cellStartBlockZ() {
        return this.cellStartBlockZ;
    }

    @Override
    public int lattice$cellWidth() {
        return this.cellWidth;
    }

    @Override
    public int lattice$cellHeight() {
        return this.cellHeight;
    }

    @Override
    public int lattice$cellNoiseMinY() {
        return this.cellNoiseMinY;
    }

    @Override
    public int lattice$firstCellZ() {
        return this.firstCellZ;
    }

    @Override
    public int lattice$cellCountY() {
        return this.cellCountY;
    }

    @Override
    public int lattice$cellCountXZ() {
        return this.cellCountXZ;
    }

    @Override
    public int lattice$inCellX() {
        return this.inCellX;
    }

    @Override
    public int lattice$inCellY() {
        return this.inCellY;
    }

    @Override
    public int lattice$inCellZ() {
        return this.inCellZ;
    }

    @Override
    public boolean lattice$interpolating() {
        return this.interpolating;
    }

    @Override
    public boolean lattice$fillingCell() {
        return this.fillingCell;
    }

}
