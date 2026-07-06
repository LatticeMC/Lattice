package com.latticemc.lattice.mixin;

import com.latticemc.lattice.nativelib.NativeDensityFunction;
import com.latticemc.lattice.nativelib.NativeNoiseChunkAccess;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.minecraft.world.level.levelgen.NoiseChunk$1")
public abstract class NoiseChunkSliceProviderMixin {
    @Shadow @Final private NoiseChunk this$0;

    @Inject(method = "fillAllDirectly", at = @At("HEAD"), cancellable = true)
    private void lattice$fillSliceNative(double[] values, DensityFunction function, CallbackInfo ci) {
        NativeNoiseChunkAccess chunk = (NativeNoiseChunkAccess) this.this$0;
        int cellWidth = chunk.lattice$cellWidth();
        int cellHeight = chunk.lattice$cellHeight();
        int startX = chunk.lattice$cellStartBlockX();
        int startZ = chunk.lattice$cellStartBlockZ();
        if (NativeDensityFunction.tryFillSlice(
                values,
                function,
                startX,
                chunk.lattice$cellNoiseMinY() * cellHeight,
                startZ,
                cellHeight,
                Math.floorDiv(startX, cellWidth),
                Math.floorDiv(startZ, cellWidth))) {
            ci.cancel();
        }
    }
}
