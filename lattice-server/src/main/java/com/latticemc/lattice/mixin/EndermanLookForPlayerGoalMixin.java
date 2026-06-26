package com.latticemc.lattice.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.targeting.TargetingConditions;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "net.minecraft.world.entity.monster.EnderMan$EndermanLookForPlayerGoal")
public abstract class EndermanLookForPlayerGoalMixin {
    @Shadow private EnderMan enderman;
    @Shadow private @Nullable Player pendingTarget;
    @Shadow private TargetingConditions startAggroTargetConditions;
    @Shadow protected abstract double getFollowDistance();

    @Inject(method = "canUse", at = @At("HEAD"), cancellable = true)
    private void lattice$canUse(CallbackInfoReturnable<Boolean> cir) {
        final ServerLevel level = (ServerLevel) this.enderman.level();
        final double followDistance = this.getFollowDistance();
        final AABB area = this.enderman.getBoundingBox().inflate(followDistance, followDistance, followDistance);
        this.pendingTarget = NativeGoalQuerySupport.findNearestEntity(
                this.enderman,
                level,
                level.players(),
                this.startAggroTargetConditions.range(followDistance),
                area,
                this.enderman.getX(),
                this.enderman.getY(),
                this.enderman.getZ());
        cir.setReturnValue(this.pendingTarget != null);
    }
}
