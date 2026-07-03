package com.latticemc.lattice.mixin;

import com.latticemc.lattice.nativelib.NativeBeardifier;
import com.latticemc.lattice.nativelib.NativeBeardifierAccess;
import java.util.List;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.pools.JigsawJunction;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Beardifier.class)
public abstract class BeardifierMixin implements NativeBeardifierAccess {
    @Shadow @Final private List<Beardifier.Rigid> pieces;
    @Shadow @Final private List<JigsawJunction> junctions;
    @Shadow @Final private @Nullable BoundingBox affectedBox;
    @Unique private @Nullable NativeBeardifier lattice$nativeBeardifier;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void lattice$initNative(List<Beardifier.Rigid> pieces,
                                    List<JigsawJunction> junctions,
                                    @Nullable BoundingBox affectedBox,
                                    CallbackInfo ci) {
        if (NativeBeardifier.isAvailable()) {
            this.lattice$nativeBeardifier = NativeBeardifier.create(this.pieces, this.junctions);
        }
    }

    @Inject(method = "compute", at = @At("HEAD"), cancellable = true)
    private void lattice$computeNative(DensityFunction.FunctionContext context, CallbackInfoReturnable<Double> cir) {
        NativeBeardifier nativeBeardifier = this.lattice$nativeBeardifier;
        if (nativeBeardifier == null) return;
        int x = context.blockX();
        int y = context.blockY();
        int z = context.blockZ();
        if (this.affectedBox != null && !this.affectedBox.isInside(x, y, z)) {
            cir.setReturnValue(0.0);
            return;
        }
        try {
            cir.setReturnValue(nativeBeardifier.compute(x, y, z));
        } catch (RuntimeException | LinkageError ignored) {
        }
    }

    @Override
    public long lattice$nativeBeardifierHandleFromMixin() {
        NativeBeardifier nativeBeardifier = this.lattice$nativeBeardifier;
        return nativeBeardifier == null ? 0L : nativeBeardifier.handle();
    }
}
