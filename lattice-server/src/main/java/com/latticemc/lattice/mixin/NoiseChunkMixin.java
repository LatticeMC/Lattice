package com.latticemc.lattice.mixin;

import com.latticemc.lattice.nativelib.NativeCacheAllInCellAccess;
import com.latticemc.lattice.nativelib.NativeDensityFunction;
import com.latticemc.lattice.nativelib.NativeNoiseChunkAccess;
import com.latticemc.lattice.nativelib.NativeNoiseInterpolatorAccess;
import com.latticemc.lattice.nativelib.NativeOreVeinBlockStateFiller;
import com.latticemc.lattice.nativelib.WorldgenProfiler;
import java.util.List;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
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
    @Shadow long arrayInterpolationCounter;
    @Unique private final ThreadLocal<Long> lattice$initializeForFirstCellXStart = ThreadLocal.withInitial(() -> 0L);
    @Unique private final ThreadLocal<Long> lattice$advanceCellXStart = ThreadLocal.withInitial(() -> 0L);
    @Unique private final ThreadLocal<Long> lattice$fillAllDirectlyStart = ThreadLocal.withInitial(() -> 0L);
    @Unique private final ThreadLocal<Long> lattice$updateForYStart = ThreadLocal.withInitial(() -> 0L);
    @Unique private final ThreadLocal<Long> lattice$updateForXStart = ThreadLocal.withInitial(() -> 0L);
    @Unique private final ThreadLocal<Long> lattice$updateForZStart = ThreadLocal.withInitial(() -> 0L);

    @Inject(method = "initializeForFirstCellX", at = @At("HEAD"))
    private void lattice$profileInitializeForFirstCellXStart(CallbackInfo ci) {
        this.lattice$initializeForFirstCellXStart.set(WorldgenProfiler.start());
    }

    @Inject(method = "initializeForFirstCellX", at = @At("RETURN"))
    private void lattice$profileInitializeForFirstCellXEnd(CallbackInfo ci) {
        WorldgenProfiler.end("noise.initializeForFirstCellX", this.lattice$initializeForFirstCellXStart.get().longValue());
    }

    @Inject(method = "advanceCellX", at = @At("HEAD"))
    private void lattice$profileAdvanceCellXStart(int increment, CallbackInfo ci) {
        this.lattice$advanceCellXStart.set(WorldgenProfiler.start());
    }

    @Inject(method = "advanceCellX", at = @At("RETURN"))
    private void lattice$profileAdvanceCellXEnd(int increment, CallbackInfo ci) {
        WorldgenProfiler.end("noise.advanceCellX", this.lattice$advanceCellXStart.get().longValue());
    }

    @Inject(method = "fillAllDirectly", at = @At("HEAD"))
    private void lattice$profileFillAllDirectlyStart(double[] values, DensityFunction function, CallbackInfo ci) {
        this.lattice$fillAllDirectlyStart.set(WorldgenProfiler.start());
    }

    @Inject(method = "fillAllDirectly", at = @At("RETURN"))
    private void lattice$profileFillAllDirectlyEnd(double[] values, DensityFunction function, CallbackInfo ci) {
        WorldgenProfiler.end("noise.fillAllDirectly", this.lattice$fillAllDirectlyStart.get().longValue());
    }

    @Inject(method = "updateForY", at = @At("HEAD"))
    private void lattice$profileUpdateForYStart(int cellEndBlockY, double y, CallbackInfo ci) {
        this.lattice$updateForYStart.set(WorldgenProfiler.start());
    }

    @Inject(method = "updateForY", at = @At("RETURN"))
    private void lattice$profileUpdateForYEnd(int cellEndBlockY, double y, CallbackInfo ci) {
        WorldgenProfiler.end("noise.updateForY", this.lattice$updateForYStart.get().longValue());
    }

    @Inject(method = "updateForX", at = @At("HEAD"))
    private void lattice$profileUpdateForXStart(int cellEndBlockX, double x, CallbackInfo ci) {
        this.lattice$updateForXStart.set(WorldgenProfiler.start());
    }

    @Inject(method = "updateForX", at = @At("RETURN"))
    private void lattice$profileUpdateForXEnd(int cellEndBlockX, double x, CallbackInfo ci) {
        WorldgenProfiler.end("noise.updateForX", this.lattice$updateForXStart.get().longValue());
    }

    @Inject(method = "updateForZ", at = @At("HEAD"))
    private void lattice$profileUpdateForZStart(int cellEndBlockZ, double z, CallbackInfo ci) {
        this.lattice$updateForZStart.set(WorldgenProfiler.start());
    }

    @Inject(method = "updateForZ", at = @At("RETURN"))
    private void lattice$profileUpdateForZEnd(int cellEndBlockZ, double z, CallbackInfo ci) {
        WorldgenProfiler.end("noise.updateForZ", this.lattice$updateForZStart.get().longValue());
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
                Math.floorDiv(this.cellStartBlockY, this.cellHeight) - this.cellNoiseMinY,
                cellZ - this.firstCellZ)) {
            if (NativeDensityFunction.shouldCheckParity()) {
                double[] javaValues = new double[values.length];
                NativeDensityFunction.runWithFillAllDirectlyBypass(() -> function.fillArray(javaValues, (DensityFunction.ContextProvider) (Object) this));
                NativeDensityFunction.recordParity("directCell", function, values, javaValues);
            }
            WorldgenProfiler.end("noise.fillAllDirectly", this.lattice$fillAllDirectlyStart.get().longValue());
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
        if (NativeDensityFunction.shouldCheckParity()) return;
        long profileStart = WorldgenProfiler.start();
        int yRows = this.cellCountY + 1;
        int zRows = this.cellCountXZ + 1;
        this.cellStartBlockX = start * this.cellWidth;
        this.inCellX = 0;
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
        long profileStart = WorldgenProfiler.start();
        try {
            for (NoiseChunk.NoiseInterpolator noiseInterpolator : this.interpolators) {
                ((NativeNoiseInterpolatorAccess) (Object) noiseInterpolator).lattice$selectCellYZ(y, z);
            }

            this.fillingCell = true;
            this.cellStartBlockY = (y + this.cellNoiseMinY) * this.cellHeight;
            this.cellStartBlockZ = (this.firstCellZ + z) * this.cellWidth;
            this.arrayInterpolationCounter++;

            int cellX = Math.floorDiv(this.cellStartBlockX, this.cellWidth);
            int cellValueCount = this.cellWidth * this.cellHeight * this.cellWidth;
            int columnValueCount = this.cellCountXZ * this.cellCountY * cellValueCount;
            int cellOffset = (z * this.cellCountY + y) * cellValueCount;
            long cacheStart = WorldgenProfiler.start();
            if (NativeDensityFunction.tryFillCellColumns(
                    this.cellCaches,
                    (DensityFunction.ContextProvider) (Object) this,
                    this.cellStartBlockX,
                    this.firstCellZ,
                    this.cellNoiseMinY,
                    this.cellWidth,
                    this.cellHeight,
                    this.cellCountXZ,
                    this.cellCountY,
                    cellX,
                    cellOffset)) {
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
                        column = new double[columnValueCount];
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

    @Redirect(
            method = "<init>",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/levelgen/OreVeinifier;create(Lnet/minecraft/world/level/levelgen/DensityFunction;Lnet/minecraft/world/level/levelgen/DensityFunction;Lnet/minecraft/world/level/levelgen/DensityFunction;Lnet/minecraft/world/level/levelgen/PositionalRandomFactory;)Lnet/minecraft/world/level/levelgen/NoiseChunk$BlockStateFiller;"
            )
    )
    private NoiseChunk.BlockStateFiller lattice$useNativeOreVeinFiller(DensityFunction veinToggle,
                                                                       DensityFunction veinRidged,
                                                                       DensityFunction veinGap,
                                                                       PositionalRandomFactory random) {
        final NoiseChunk.BlockStateFiller nativeFiller = NativeOreVeinBlockStateFiller.tryCreate(
                veinToggle, veinRidged, veinGap, random);
        return nativeFiller != null
                ? nativeFiller
                : OreVeinifierAccessor.lattice$create(veinToggle, veinRidged, veinGap, random);
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
