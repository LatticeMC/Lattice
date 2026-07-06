package com.latticemc.lattice.mixin;

import com.latticemc.lattice.nativelib.NativeNoiseInterpolatorAccess;
import net.minecraft.world.level.levelgen.NoiseChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(NoiseChunk.NoiseInterpolator.class)
public abstract class NoiseInterpolatorMixin implements NativeNoiseInterpolatorAccess {
    @Shadow double[][] slice0;
    @Shadow double[][] slice1;
    @Unique private int lattice$nativeSlot = -1;
    @Unique private double[] lattice$flatSlice0;
    @Unique private double[] lattice$flatSlice1;

    @Override
    public void lattice$setNativeSlot(int slot) {
        this.lattice$nativeSlot = slot;
    }

    @Override
    public int lattice$nativeSlot() {
        return this.lattice$nativeSlot;
    }

    @Override
    public double[] lattice$flatSlice0() {
        return this.lattice$ensureFlatSlice(true);
    }

    @Override
    public double[] lattice$flatSlice1() {
        return this.lattice$ensureFlatSlice(false);
    }

    @Override
    public void lattice$copyFlatRow(boolean slice0, int zRow, double[] values, int yRows, int zRows) {
        double[] flat = this.lattice$ensureFlatSlice(slice0, yRows * zRows);
        System.arraycopy(values, 0, flat, zRow * yRows, yRows);
    }

    @Override
    public void lattice$selectCellYZ(int y, int z) {
        this.lattice$invokeSelectCellYZ(y, z);
    }

    @Invoker("selectCellYZ")
    protected abstract void lattice$invokeSelectCellYZ(int y, int z);

    @Inject(method = "swapSlices", at = @At("TAIL"))
    private void lattice$swapFlatSlices(CallbackInfo ci) {
        double[] flat = this.lattice$flatSlice0;
        this.lattice$flatSlice0 = this.lattice$flatSlice1;
        this.lattice$flatSlice1 = flat;
    }

    @Unique
    private double[] lattice$ensureFlatSlice(boolean slice0) {
        double[][] slice = slice0 ? this.slice0 : this.slice1;
        int zRows = slice.length;
        int yRows = zRows == 0 ? 0 : slice[0].length;
        return this.lattice$ensureFlatSlice(slice0, zRows * yRows);
    }

    @Unique
    private double[] lattice$ensureFlatSlice(boolean slice0, int required) {
        double[] flat = slice0 ? this.lattice$flatSlice0 : this.lattice$flatSlice1;
        if (flat == null || flat.length < required) {
            flat = new double[required];
            if (slice0) {
                this.lattice$flatSlice0 = flat;
            } else {
                this.lattice$flatSlice1 = flat;
            }
        }
        return flat;
    }
}
