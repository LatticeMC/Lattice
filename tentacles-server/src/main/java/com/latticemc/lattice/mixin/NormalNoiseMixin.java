package com.latticemc.lattice.mixin;

import com.latticemc.lattice.nativelib.NativeDoublePerlinNoise;
import it.unimi.dsi.fastutil.doubles.DoubleList;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.synth.ImprovedNoise;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import net.minecraft.world.level.levelgen.synth.PerlinNoise;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(NormalNoise.class)
public abstract class NormalNoiseMixin {
    @Shadow @Final private double valueFactor;
    @Shadow @Final private PerlinNoise first;
    @Shadow @Final private PerlinNoise second;

    @Unique private NativeDoublePerlinNoise lattice$native;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void lattice$initNative(RandomSource random,
                                    NormalNoise.NoiseParameters parameters,
                                    boolean useNewFactory,
                                    CallbackInfo ci) {
        PerlinSnapshot firstSnapshot = lattice$snapshot(this.first);
        PerlinSnapshot secondSnapshot = lattice$snapshot(this.second);
        if (firstSnapshot == null || secondSnapshot == null) return;

        this.lattice$native = NativeDoublePerlinNoise.tryCreate(
                firstSnapshot.origins,
                firstSnapshot.permutations,
                firstSnapshot.amplitudes,
                firstSnapshot.lacunarity,
                firstSnapshot.persistence,
                secondSnapshot.origins,
                secondSnapshot.permutations,
                secondSnapshot.amplitudes,
                secondSnapshot.lacunarity,
                secondSnapshot.persistence,
                this.valueFactor
        );
    }

    @Inject(method = "getValue", at = @At("HEAD"), cancellable = true)
    private void lattice$getValue(double x, double y, double z, CallbackInfoReturnable<Double> cir) {
        NativeDoublePerlinNoise nativeSampler = this.lattice$native;
        if (nativeSampler == null) return;
        cir.setReturnValue(nativeSampler.sample(x, y, z));
    }

    @Unique
    private static PerlinSnapshot lattice$snapshot(PerlinNoise noise) {
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

        return new PerlinSnapshot(
                origins,
                permutations,
                amplitudes,
                accessor.lattice$getLowestFreqInputFactor(),
                accessor.lattice$getLowestFreqValueFactor()
        );
    }

    @Unique
    private record PerlinSnapshot(double[] origins,
                                  byte[] permutations,
                                  double[] amplitudes,
                                  double lacunarity,
                                  double persistence) {}
}
