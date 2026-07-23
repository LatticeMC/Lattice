package com.latticemc.lattice.mixin;

import com.latticemc.lattice.nativelib.NativePerlinNoise;
import com.latticemc.lattice.nativelib.NativeScalarNoiseControl;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.synth.ImprovedNoise;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ImprovedNoise.class)
public abstract class ImprovedNoiseMixin {
    @Shadow @Final private byte[] p;
    @Shadow @Final public double xo;
    @Shadow @Final public double yo;
    @Shadow @Final public double zo;

    @Unique private NativePerlinNoise lattice$native;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void lattice$initNative(RandomSource random, CallbackInfo ci) {
        this.lattice$native = NativePerlinNoise.tryCreate(this.p, this.xo, this.yo, this.zo);
    }

    @Inject(method = "noise(DDD)D", at = @At("HEAD"), cancellable = true)
    private void lattice$noise(double x, double y, double z, CallbackInfoReturnable<Double> cir) {
        if (!NativeScalarNoiseControl.perlinEnabled()) return;
        NativePerlinNoise nativeSampler = this.lattice$native;
        if (nativeSampler == null) return;
        cir.setReturnValue(nativeSampler.sample(x, y, z));
    }

    @Inject(method = "noise(DDDDD)D", at = @At("HEAD"), cancellable = true)
    private void lattice$noiseYScaled(double x,
                                      double y,
                                      double z,
                                      double yScale,
                                      double yMax,
                                      CallbackInfoReturnable<Double> cir) {
        if (!NativeScalarNoiseControl.perlinEnabled()) return;
        NativePerlinNoise nativeSampler = this.lattice$native;
        if (nativeSampler == null) return;
        cir.setReturnValue(nativeSampler.sampleYScaled(x, y, z, yScale, yMax));
    }

    @Inject(method = "noiseWithDerivative", at = @At("HEAD"), cancellable = true)
    private void lattice$noiseWithDerivative(double x,
                                             double y,
                                             double z,
                                             double[] values,
                                             CallbackInfoReturnable<Double> cir) {
        if (!NativeScalarNoiseControl.perlinEnabled()) return;
        NativePerlinNoise nativeSampler = this.lattice$native;
        if (nativeSampler == null || values == null || values.length < 3) return;
        double[] derivative = new double[3];
        double value = nativeSampler.sampleDerivative(x, y, z, derivative);
        values[0] += derivative[0];
        values[1] += derivative[1];
        values[2] += derivative[2];
        cir.setReturnValue(value);
    }
}
