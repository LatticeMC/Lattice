package com.latticemc.lattice.mixin;

import com.latticemc.lattice.nativelib.NativeBeardifier;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.pools.JigsawJunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Beardifier.class)
public abstract class BeardifierMixin {
    @Unique private static final Logger lattice$logger = LoggerFactory.getLogger("Lattice");
    @Unique private static final AtomicBoolean lattice$initLogged = new AtomicBoolean(false);
    @Unique private static final AtomicBoolean lattice$computeLogged = new AtomicBoolean(false);
    @Unique private NativeBeardifier lattice$nativeHandle;
    @Unique private BoundingBox lattice$affectedBox;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void lattice$init(CallbackInfo ci) {
        if (!NativeBeardifier.isAvailable()) return;
        try {
            final BeardifierAccessor self = (BeardifierAccessor) this;
            final NativeBeardifier nativeHandle = NativeBeardifier.create(self.lattice$pieces(), self.lattice$junctions());
            if (nativeHandle == null) {
                if (lattice$initLogged.compareAndSet(false, true)) {
                    lattice$logger.warn("[Lattice] Beardifier native create returned null — falling back to vanilla");
                }
                return;
            }
            this.lattice$nativeHandle = nativeHandle;
            this.lattice$affectedBox = lattice$affectedBox(self.lattice$pieces());
            if (this.lattice$affectedBox == null) {
                nativeHandle.close();
                this.lattice$nativeHandle = null;
                if (lattice$initLogged.compareAndSet(false, true)) {
                    lattice$logger.warn("[Lattice] Beardifier affectedBox is null — falling back to vanilla");
                }
            }
        } catch (RuntimeException | LinkageError e) {
            if (lattice$initLogged.compareAndSet(false, true)) {
                lattice$logger.error("[Lattice] Beardifier init FAILED — falling back to vanilla", e);
            }
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
        } catch (RuntimeException | LinkageError e) {
            if (lattice$computeLogged.compareAndSet(false, true)) {
                lattice$logger.error("[Lattice] Beardifier compute FAILED — falling back to vanilla", e);
            }
        }
    }

    @SuppressWarnings("ConstantValue")
    private static BoundingBox lattice$affectedBox(List<Beardifier.Rigid> pieces) {
        if (pieces.isEmpty()) return null;

        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;

        for (Beardifier.Rigid piece : pieces) {
            BoundingBox box = piece.box();
            if (box.minX() < minX) minX = box.minX();
            if (box.minY() < minY) minY = box.minY();
            if (box.minZ() < minZ) minZ = box.minZ();
            if (box.maxX() > maxX) maxX = box.maxX();
            if (box.maxY() > maxY) maxY = box.maxY();
            if (box.maxZ() > maxZ) maxZ = box.maxZ();
        }

        final int radius = 12;
        return new BoundingBox(minX - radius, minY - radius, minZ - radius,
                               maxX + radius, maxY + radius, maxZ + radius);
    }
}
