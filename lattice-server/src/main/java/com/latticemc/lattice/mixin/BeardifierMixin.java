package com.latticemc.lattice.mixin;

import com.latticemc.lattice.nativelib.NativeBeardifier;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Beardifier.class)
public abstract class BeardifierMixin {
    @Unique private NativeBeardifier lattice$nativeHandle;
    @Unique private BoundingBox lattice$affectedBox;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void lattice$init(CallbackInfo ci) {
        if (!NativeBeardifier.isAvailable()) return;
        try {
            final BeardifierAccessor self = (BeardifierAccessor) this;
            final NativeBeardifier nativeHandle = NativeBeardifier.create(self.lattice$pieces(), self.lattice$junctions());
            if (nativeHandle == null) return;
            this.lattice$nativeHandle = nativeHandle;
            this.lattice$affectedBox = self.lattice$affectedBox();
            if (this.lattice$affectedBox == null) {
                nativeHandle.close();
                this.lattice$nativeHandle = null;
            }
        } catch (Exception ignored) {
        }
    }

    @Inject(method = "compute", at = @At("HEAD"), cancellable = true)
    private void lattice$nativeCompute(DensityFunction.FunctionContext context,
                                       CallbackInfoReturnable<Double> cir) {
        if (lattice$nativeHandle == null || lattice$affectedBox == null) return;
        try {
            final int x = context.blockX();
            final int y = context.blockY();
            final int z = context.blockZ();
            if (!lattice$affectedBox.isInside(x, y, z)) {
                cir.setReturnValue(0.0);
                return;
            }

            cir.setReturnValue(lattice$nativeHandle.compute(x, y, z));
        } catch (Exception ignored) {
            // Fall back to vanilla Beardifier.compute.
        }
    }
}
