package com.latticemc.lattice.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.targeting.TargetingConditions;
import net.minecraft.world.entity.animal.dolphin.Dolphin;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "net.minecraft.world.entity.animal.dolphin.Dolphin$DolphinSwimWithPlayerGoal")
public abstract class DolphinSwimWithPlayerGoalMixin {
    private static final TargetingConditions lattice$SWIM_WITH_PLAYER_TARGETING = TargetingConditions.forNonCombat().range(10.0).ignoreLineOfSight();

    @Shadow private Dolphin dolphin;
    @Shadow private @Nullable Player player;

    @Inject(method = "canUse", at = @At("HEAD"), cancellable = true)
    private void lattice$canUse(CallbackInfoReturnable<Boolean> cir) {
        final ServerLevel level = (ServerLevel) this.dolphin.level();
        final AABB area = this.dolphin.getBoundingBox().inflate(10.0, 10.0, 10.0);
        this.player = NativeGoalQuerySupport.findNearestEntity(
                this.dolphin,
                level,
                level.players(),
                lattice$SWIM_WITH_PLAYER_TARGETING,
                area,
                this.dolphin.getX(),
                this.dolphin.getY(),
                this.dolphin.getZ());
        cir.setReturnValue(this.player != null && this.player.isSwimming() && this.dolphin.getTarget() != this.player);
    }
}
