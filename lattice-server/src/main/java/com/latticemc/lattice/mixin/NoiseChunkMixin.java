package com.latticemc.lattice.mixin;

import com.latticemc.lattice.nativelib.NativeDensityFunction;
import com.latticemc.lattice.nativelib.NativeNoiseChunkAccess;
import com.latticemc.lattice.nativelib.NativeOreVeinBlockStateFiller;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
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
    @Shadow private int cellStartBlockX;
    @Shadow int cellStartBlockY;
    @Shadow private int cellStartBlockZ;

    @Inject(method = "fillAllDirectly", at = @At("HEAD"), cancellable = true)
    private void lattice$fillCellNative(double[] values, DensityFunction function, CallbackInfo ci) {
        int cellX = Math.floorDiv(this.cellStartBlockX, this.cellWidth);
        int cellZ = Math.floorDiv(this.cellStartBlockZ, this.cellWidth);
        if (NativeDensityFunction.tryFillCellDirect(
                values,
                function,
                this.cellStartBlockX,
                this.cellStartBlockY,
                this.cellStartBlockZ,
                this.cellWidth,
                this.cellHeight,
                this.cellCountXZ,
                this.cellCountY,
                cellX,
                cellZ,
                Math.floorDiv(this.cellStartBlockY, this.cellHeight) - this.cellNoiseMinY,
                cellZ - this.firstCellZ)) {
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
                                                      DensityFunction.ContextProvider contextProvider) {
        if (NativeDensityFunction.tryFillSlice(
                values,
                interpolator.wrapped(),
                this.cellStartBlockX,
                this.cellNoiseMinY * this.cellHeight,
                this.cellStartBlockZ,
                this.cellHeight,
                Math.floorDiv(this.cellStartBlockX, this.cellWidth),
                Math.floorDiv(this.cellStartBlockZ, this.cellWidth))) {
            return;
        }
        interpolator.fillArray(values, contextProvider);
    }

    @Redirect(
            method = "selectCellYZ",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/levelgen/DensityFunction;fillArray([DLnet/minecraft/world/level/levelgen/DensityFunction$ContextProvider;)V"
            )
    )
    private void lattice$fillCellCacheNative(DensityFunction function,
                                             double[] values,
                                             DensityFunction.ContextProvider contextProvider) {
        int cellX = Math.floorDiv(this.cellStartBlockX, this.cellWidth);
        int cellZ = Math.floorDiv(this.cellStartBlockZ, this.cellWidth);
        if (NativeDensityFunction.tryFillCell(
                values,
                function,
                this.cellStartBlockX,
                this.cellStartBlockY,
                this.cellStartBlockZ,
                this.cellWidth,
                this.cellHeight,
                this.cellCountXZ,
                this.cellCountY,
                cellX,
                cellZ,
                Math.floorDiv(this.cellStartBlockY, this.cellHeight) - this.cellNoiseMinY,
                cellZ - this.firstCellZ)) {
            return;
        }
        function.fillArray(values, contextProvider);
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
