package com.latticemc.lattice.mixin;

import com.latticemc.lattice.nativelib.NativeSimplexNoise;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.synth.SimplexNoise;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(SimplexNoise.class)
public abstract class SimplexNoiseMixin {
    @Shadow @Final private int[] p;
    @Shadow @Final public double xo;
    @Shadow @Final public double yo;
    @Shadow @Final public double zo;

    @Unique private NativeSimplexNoise lattice$native;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void lattice$initNative(RandomSource random, CallbackInfo ci) {
        this.lattice$native = NativeSimplexNoise.tryCreate(this.p, this.xo, this.yo, this.zo);
    }

    @Inject(method = "getValue(DD)D", at = @At("HEAD"), cancellable = true)
    private void lattice$getValue2d(double x, double y, CallbackInfoReturnable<Double> cir) {
        NativeSimplexNoise nativeSampler = this.lattice$native;
        if (nativeSampler == null) return;
        double nativeValue = nativeSampler.sample2d(x, y);
        cir.setReturnValue(nativeValue);
    }

    @Inject(method = "getValue(DDD)D", at = @At("HEAD"), cancellable = true)
    private void lattice$getValue3d(double x, double y, double z, CallbackInfoReturnable<Double> cir) {
        NativeSimplexNoise nativeSampler = this.lattice$native;
        if (nativeSampler == null) return;
        double nativeValue = nativeSampler.sample3d(x, y, z);
        cir.setReturnValue(nativeValue);
    }
}
