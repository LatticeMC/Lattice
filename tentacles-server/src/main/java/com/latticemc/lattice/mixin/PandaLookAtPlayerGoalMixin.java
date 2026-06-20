package com.latticemc.lattice.mixin;

import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.targeting.TargetingConditions;
import net.minecraft.world.entity.animal.panda.Panda;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "net.minecraft.world.entity.animal.panda.Panda$PandaLookAtPlayerGoal")
public abstract class PandaLookAtPlayerGoalMixin {
    @Shadow private Panda panda;
    @Shadow protected Mob mob;
    @Shadow protected @Nullable Entity lookAt;
    @Shadow protected float lookDistance;
    @Shadow protected float probability;
    @Shadow protected Class<? extends LivingEntity> lookAtType;
    @Shadow protected TargetingConditions lookAtContext;

    @Inject(method = "canUse", at = @At("HEAD"), cancellable = true)
    private void lattice$canUse(CallbackInfoReturnable<Boolean> cir) {
        if (this.mob.getRandom().nextFloat() >= this.probability) {
            cir.setReturnValue(false);
            return;
        }

        if (this.lookAt == null) {
            final ServerLevel level = (ServerLevel) this.mob.level();
            final AABB area = this.mob.getBoundingBox().inflate(this.lookDistance, 3.0, this.lookDistance);
            if (this.lookAtType == Player.class) {
                this.lookAt = NativeGoalQuerySupport.findNearestEntity(
                        this.mob,
                        level,
                        level.players(),
                        this.lookAtContext,
                        area,
                        this.mob.getX(),
                        this.mob.getEyeY(),
                        this.mob.getZ());
            } else {
                final List<? extends LivingEntity> candidates = this.mob.level().getEntitiesOfClass(this.lookAtType, area, entity -> true);
                this.lookAt = NativeGoalQuerySupport.findNearestEntity(
                        this.mob,
                        level,
                        candidates,
                        this.lookAtContext,
                        area,
                        this.mob.getX(),
                        this.mob.getEyeY(),
                        this.mob.getZ());
            }
        }

        cir.setReturnValue(this.panda.canPerformAction() && this.lookAt != null);
    }
}
