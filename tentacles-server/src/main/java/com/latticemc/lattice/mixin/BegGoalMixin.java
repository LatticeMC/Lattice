package com.latticemc.lattice.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.BegGoal;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.targeting.TargetingConditions;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BegGoal.class)
public abstract class BegGoalMixin extends Goal {
    @Shadow private Wolf wolf;
    @Shadow private @Nullable Player player;
    @Shadow private ServerLevel level;
    @Shadow private float lookDistance;
    @Shadow private TargetingConditions begTargeting;
    @Shadow protected abstract boolean playerHoldingInteresting(Player player);

    @Inject(method = "canUse", at = @At("HEAD"), cancellable = true)
    private void lattice$canUse(CallbackInfoReturnable<Boolean> cir) {
        final AABB area = this.wolf.getBoundingBox().inflate(this.lookDistance, this.lookDistance, this.lookDistance);
        this.player = NativeGoalQuerySupport.findNearestEntity(
                this.wolf,
                this.level,
                this.level.players(),
                this.begTargeting,
                area,
                this.wolf.getX(),
                this.wolf.getY(),
                this.wolf.getZ());
        cir.setReturnValue(this.player != null && this.playerHoldingInteresting(this.player));
    }
}
