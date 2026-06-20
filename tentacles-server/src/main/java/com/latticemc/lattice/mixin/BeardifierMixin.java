package com.latticemc.lattice.mixin;

import com.latticemc.lattice.nativelib.NativeBeardifier;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Beardifier.class)
public abstract class BeardifierMixin {
    @Inject(method = "compute", at = @At("HEAD"), cancellable = true)
    private void lattice$nativeCompute(DensityFunction.FunctionContext context,
                                       CallbackInfoReturnable<Double> cir) {
        if (!NativeBeardifier.isAvailable()) return;
        try {
            final BeardifierAccessor self = (BeardifierAccessor) this;
            final BoundingBox affected = self.lattice$affectedBox();
            if (affected == null) {
                cir.setReturnValue(0.0);
                return;
            }

            final int x = context.blockX();
            final int y = context.blockY();
            final int z = context.blockZ();
            if (!affected.isInside(x, y, z)) {
                cir.setReturnValue(0.0);
                return;
            }

            cir.setReturnValue(NativeBeardifier.compute(self.lattice$pieces(), self.lattice$junctions(), x, y, z));
        } catch (Exception ignored) {
            // Fall back to vanilla Beardifier.compute.
        }
    }
}
