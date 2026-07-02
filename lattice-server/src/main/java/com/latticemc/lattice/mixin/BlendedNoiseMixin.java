package com.latticemc.lattice.mixin;

import com.latticemc.lattice.nativelib.NativeInterpolatedNoise;
import com.latticemc.lattice.nativelib.NativeOctavePerlinNoise;
import it.unimi.dsi.fastutil.doubles.DoubleList;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.synth.BlendedNoise;
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

@Mixin(BlendedNoise.class)
public abstract class BlendedNoiseMixin implements NativeInterpolatedNoiseAccess {
    @Shadow @Final private PerlinNoise minLimitNoise;
    @Shadow @Final private PerlinNoise maxLimitNoise;
    @Shadow @Final private PerlinNoise mainNoise;
    @Shadow @Final private double xzScale;
    @Shadow @Final private double yScale;
    @Shadow @Final private double xzFactor;
    @Shadow @Final private double yFactor;
    @Shadow @Final private double smearScaleMultiplier;

    @Unique private NativeOctavePerlinNoise lattice$lowerNative;
    @Unique private NativeOctavePerlinNoise lattice$upperNative;
    @Unique private NativeOctavePerlinNoise lattice$interpolationNative;
    @Unique private NativeInterpolatedNoise lattice$native;

    @Inject(method = "<init>(Lnet/minecraft/util/RandomSource;DDDDD)V", at = @At("RETURN"))
    private void lattice$initNative(RandomSource random,
                                    double xzScale,
                                    double yScale,
                                    double xzFactor,
                                    double yFactor,
                                    double smearScaleMultiplier,
                                    CallbackInfo ci) {
        this.lattice$initNativeSamplers();
    }

    @Inject(method = "<init>(Lnet/minecraft/world/level/levelgen/synth/PerlinNoise;Lnet/minecraft/world/level/levelgen/synth/PerlinNoise;Lnet/minecraft/world/level/levelgen/synth/PerlinNoise;DDDDD)V", at = @At("RETURN"))
    private void lattice$initNativeFromNoises(PerlinNoise minLimitNoise,
                                              PerlinNoise maxLimitNoise,
                                              PerlinNoise mainNoise,
                                              double xzScale,
                                              double yScale,
                                              double xzFactor,
                                              double yFactor,
                                              double smearScaleMultiplier,
                                              CallbackInfo ci) {
        this.lattice$initNativeSamplers();
    }

    @Unique
    private void lattice$initNativeSamplers() {
        this.lattice$lowerNative = lattice$createOctave(this.minLimitNoise);
        this.lattice$upperNative = lattice$createOctave(this.maxLimitNoise);
        this.lattice$interpolationNative = lattice$createOctave(this.mainNoise);
        this.lattice$native = NativeInterpolatedNoise.tryCreate(
                this.lattice$lowerNative,
                this.lattice$upperNative,
                this.lattice$interpolationNative,
                this.xzScale,
                this.yScale,
                this.xzFactor,
                this.yFactor,
                this.smearScaleMultiplier
        );
    }

    @Inject(method = "compute", at = @At("HEAD"), cancellable = true)
    private void lattice$compute(DensityFunction.FunctionContext context, CallbackInfoReturnable<Double> cir) {
        NativeInterpolatedNoise nativeSampler = this.lattice$native;
        if (nativeSampler == null) return;
        cir.setReturnValue(nativeSampler.sample(context.blockX(), context.blockY(), context.blockZ()));
    }

    @Override
    public NativeInterpolatedNoise lattice$getNativeInterpolatedNoise() {
        return this.lattice$native;
    }

    @Unique
    private static NativeOctavePerlinNoise lattice$createOctave(PerlinNoise noise) {
        PerlinNoiseAccessor accessor = (PerlinNoiseAccessor) (Object) noise;
        ImprovedNoise[] levels = accessor.lattice$getNoiseLevels();
        DoubleList amplitudeList = accessor.lattice$getAmplitudes();
        int count = amplitudeList.size();
        if (count <= 0 || levels.length != count) return null;

        double[] origins = new double[count * 3];
        byte[] permutations = new byte[count * 256];
        double[] amplitudes = new double[count];
        for (int i = 0; i < count; ++i) {
            amplitudes[i] = amplitudeList.getDouble(i);
            ImprovedNoise octave = levels[i];
            if (octave == null) continue;

            origins[i * 3] = octave.xo;
            origins[i * 3 + 1] = octave.yo;
            origins[i * 3 + 2] = octave.zo;
            byte[] permutation = ((ImprovedNoiseAccessor) (Object) octave).lattice$getPermutation();
            System.arraycopy(permutation, 0, permutations, i * 256, 256);
        }

        return NativeOctavePerlinNoise.tryCreate(
                origins,
                permutations,
                amplitudes,
                accessor.lattice$getLowestFreqInputFactor(),
                accessor.lattice$getLowestFreqValueFactor()
        );
    }
}
