package com.latticemc.lattice.mixin;

import com.latticemc.lattice.nativelib.NativeCacheAllInCellAccess;
import com.latticemc.lattice.nativelib.NativeDensityFunction;
import com.latticemc.lattice.nativelib.NativeNoiseChunkAccess;
import com.latticemc.lattice.nativelib.NativeNoiseInterpolatorAccess;
import com.latticemc.lattice.nativelib.NativeOreVeinBlockStateFiller;
import java.util.List;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
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
    @Shadow private int cellStartBlockX;
    @Shadow int cellStartBlockY;
    @Shadow private int cellStartBlockZ;
    @Shadow boolean fillingCell;
    @Shadow long arrayInterpolationCounter;

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

    /**
     * @author Lattice
     * @reason Fill CacheAllInCell columns through one native call per cache/X column.
     */
    @Overwrite
    public void selectCellYZ(int y, int z) {
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

        this.arrayInterpolationCounter++;
        this.fillingCell = false;
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
}
