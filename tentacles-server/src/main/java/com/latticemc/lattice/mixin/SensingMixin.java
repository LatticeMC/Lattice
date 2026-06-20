package com.latticemc.lattice.mixin;

import com.latticemc.lattice.nativelib.LatticeNative;
import com.latticemc.lattice.nativelib.NativeLineOfSight;
import it.unimi.dsi.fastutil.ints.IntSet;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.sensing.Sensing;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Sensing.class)
public abstract class SensingMixin {
    @Shadow @Final private Mob mob;
    @Shadow @Final private IntSet seen;
    @Shadow @Final private IntSet unseen;

    @Inject(method = "hasLineOfSight", at = @At("HEAD"), cancellable = true)
    private void lattice$nativeHasLineOfSight(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        int id = entity.getId();
        if (this.seen.contains(id) || this.unseen.contains(id)) return;

        try {
            Boolean result = NativeLineOfSight.tryHasLineOfSight(this.mob, entity);
            if (result == null) return;
            if (LatticeNative.VERIFY) {
                boolean vanilla = this.mob.hasLineOfSight(entity);
                if (vanilla != result) {
                    throw new AssertionError("lattice.verify: native_los mismatch mob="
                            + this.mob.getType() + " target=" + entity.getType()
                            + " vanilla=" + vanilla + " native=" + result);
                }
            }
            if (result) {
                this.seen.add(id);
            } else {
                this.unseen.add(id);
            }
            cir.setReturnValue(result);
        } catch (Exception e) {
            LatticeNative.logFallbackOnce("native_los", e.getMessage());
        }
    }
}
