package com.latticemc.lattice.mixin;

import com.latticemc.lattice.nativelib.NativeNoiseInterpolatorAccess;
import com.latticemc.lattice.nativelib.NativeNoiseChunkAccess;
import com.latticemc.lattice.nativelib.WorldgenProfiler;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseChunk;
import org.spongepowered.asm.mixin.Final;
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
    @Shadow private double noise000;
    @Shadow private double noise001;
    @Shadow private double noise100;
    @Shadow private double noise101;
    @Shadow private double noise010;
    @Shadow private double noise011;
    @Shadow private double noise110;
    @Shadow private double noise111;
    @Shadow private double valueXZ00;
    @Shadow private double valueXZ10;
    @Shadow private double valueXZ01;
    @Shadow private double valueXZ11;
    @Shadow private double valueZ0;
    @Shadow private double valueZ1;
    @Shadow private double value;
    @Shadow @Final private NoiseChunk this$0;
    @Unique private int lattice$nativeSlot = -1;
    @Unique private double[] lattice$flatSlice0;
    @Unique private double[] lattice$flatSlice1;
    @Unique private boolean lattice$flatReadable;

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
        this.lattice$flatReadable = true;
    }

    @Override
    public double[] lattice$sliceRow(boolean slice0, int zRow) {
        double[][] slice = slice0 ? this.slice0 : this.slice1;
        return zRow >= 0 && zRow < slice.length ? slice[zRow] : null;
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

    @Inject(method = "selectCellYZ", at = @At("HEAD"), cancellable = true)
    private void lattice$selectCellYZFlat(int y, int z, CallbackInfo ci) {
        if (!this.lattice$flatReadable) return;
        NativeNoiseChunkAccess chunk = (NativeNoiseChunkAccess) (Object) this.this$0;
        int yRows = chunk.lattice$cellCountY() + 1;
        int zRows = chunk.lattice$cellCountXZ() + 1;
        double[] start = this.lattice$ensureFlatSlice(true, yRows * zRows);
        double[] end = this.lattice$ensureFlatSlice(false, yRows * zRows);
        int i00 = z * yRows + y;
        int i10 = (z + 1) * yRows + y;
        int i01 = z * yRows + y + 1;
        int i11 = (z + 1) * yRows + y + 1;
        if (i11 >= start.length || i11 >= end.length) return;
        long profileStart = WorldgenProfiler.start();
        this.noise000 = start[i00];
        this.noise001 = start[i10];
        this.noise100 = end[i00];
        this.noise101 = end[i10];
        this.noise010 = start[i01];
        this.noise011 = start[i11];
        this.noise110 = end[i01];
        this.noise111 = end[i11];
        WorldgenProfiler.end("noise.interpolator.selectCellYZ.flat", profileStart);
        ci.cancel();
    }

    @Inject(method = "updateForY", at = @At("HEAD"), cancellable = true)
    private void lattice$updateForYFlat(double y, CallbackInfo ci) {
        if (!this.lattice$flatReadable) return;
        long profileStart = WorldgenProfiler.start();
        this.valueXZ00 = Mth.lerp(y, this.noise000, this.noise010);
        this.valueXZ10 = Mth.lerp(y, this.noise100, this.noise110);
        this.valueXZ01 = Mth.lerp(y, this.noise001, this.noise011);
        this.valueXZ11 = Mth.lerp(y, this.noise101, this.noise111);
        WorldgenProfiler.end("noise.interpolator.updateForY.flat", profileStart);
        ci.cancel();
    }

    @Inject(method = "updateForX", at = @At("HEAD"), cancellable = true)
    private void lattice$updateForXFlat(double x, CallbackInfo ci) {
        if (!this.lattice$flatReadable) return;
        long profileStart = WorldgenProfiler.start();
        this.valueZ0 = Mth.lerp(x, this.valueXZ00, this.valueXZ10);
        this.valueZ1 = Mth.lerp(x, this.valueXZ01, this.valueXZ11);
        WorldgenProfiler.end("noise.interpolator.updateForX.flat", profileStart);
        ci.cancel();
    }

    @Inject(method = "updateForZ", at = @At("HEAD"), cancellable = true)
    private void lattice$updateForZFlat(double z, CallbackInfo ci) {
        if (!this.lattice$flatReadable) return;
        long profileStart = WorldgenProfiler.start();
        this.value = Mth.lerp(z, this.valueZ0, this.valueZ1);
        WorldgenProfiler.end("noise.interpolator.updateForZ.flat", profileStart);
        ci.cancel();
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
