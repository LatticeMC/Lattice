package com.latticemc.lattice.mixin;

import com.latticemc.lattice.nativelib.NativeOctavePerlinNoise;
import com.mojang.datafixers.util.Pair;
import it.unimi.dsi.fastutil.doubles.DoubleList;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.synth.ImprovedNoise;
import net.minecraft.world.level.levelgen.synth.PerlinNoise;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PerlinNoise.class)
public abstract class PerlinNoiseMixin {
    @Shadow @Final private ImprovedNoise[] noiseLevels;
    @Shadow @Final private DoubleList amplitudes;
    @Shadow @Final private double lowestFreqValueFactor;
    @Shadow @Final private double lowestFreqInputFactor;

    @Unique private NativeOctavePerlinNoise lattice$native;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void lattice$initNative(RandomSource random,
                                    Pair<Integer, DoubleList> octavesAndAmplitudes,
                                    boolean useNewFactory,
                                    CallbackInfo ci) {
        int count = this.amplitudes.size();
        if (count <= 0 || this.noiseLevels.length != count) return;

        double[] origins = new double[count * 3];
        byte[] permutations = new byte[count * 256];
        double[] amps = new double[count];
        for (int i = 0; i < count; ++i) {
            amps[i] = this.amplitudes.getDouble(i);
            ImprovedNoise octave = this.noiseLevels[i];
            if (octave == null) continue;

            origins[i * 3] = octave.xo;
            origins[i * 3 + 1] = octave.yo;
            origins[i * 3 + 2] = octave.zo;
            byte[] permutation = ((ImprovedNoiseAccessor) (Object) octave).lattice$getPermutation();
            System.arraycopy(permutation, 0, permutations, i * 256, 256);
        }

        this.lattice$native = NativeOctavePerlinNoise.tryCreate(
                origins,
                permutations,
                amps,
                this.lowestFreqInputFactor,
                this.lowestFreqValueFactor
        );
    }

    @Inject(method = "getValue(DDD)D", at = @At("HEAD"), cancellable = true)
    private void lattice$getValue(double x, double y, double z, CallbackInfoReturnable<Double> cir) {
        NativeOctavePerlinNoise nativeSampler = this.lattice$native;
        if (nativeSampler == null) return;
        cir.setReturnValue(nativeSampler.sample(x, y, z));
    }

    @Inject(method = "getValue(DDDDDZ)D", at = @At("HEAD"), cancellable = true)
    private void lattice$getValueFull(double x,
                                      double y,
                                      double z,
                                      double yScale,
                                      double yMax,
                                      boolean useFixedY,
                                      CallbackInfoReturnable<Double> cir) {
        NativeOctavePerlinNoise nativeSampler = this.lattice$native;
        if (nativeSampler == null) return;
        cir.setReturnValue(nativeSampler.sampleFull(x, y, z, yScale, yMax, useFixedY));
    }
}
