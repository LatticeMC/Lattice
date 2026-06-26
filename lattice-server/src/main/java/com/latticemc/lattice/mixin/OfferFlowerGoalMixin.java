package com.latticemc.lattice.mixin;

import java.util.List;
import net.minecraft.tags.EntityTypeTags;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.OfferFlowerGoal;
import net.minecraft.world.entity.ai.targeting.TargetingConditions;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(OfferFlowerGoal.class)
public abstract class OfferFlowerGoalMixin extends Goal {
    @Shadow private IronGolem golem;
    @Shadow private @Nullable LivingEntity entity;
    @Shadow protected abstract AABB getGolemBoundingBox();

    @Inject(method = "canUse", at = @At("HEAD"), cancellable = true)
    private void lattice$canUse(CallbackInfoReturnable<Boolean> cir) {
        if (!this.golem.level().isBrightOutside()) {
            cir.setReturnValue(false);
            return;
        }
        if (this.golem.getRandom().nextInt(8000) != 0) {
            cir.setReturnValue(false);
            return;
        }

        final AABB area = this.getGolemBoundingBox();
        final List<LivingEntity> candidates = this.golem.level().getEntitiesOfClass(
                LivingEntity.class,
                area,
                livingEntity -> livingEntity.getType().is(EntityTypeTags.CANDIDATE_FOR_IRON_GOLEM_GIFT));
        this.entity = NativeGoalQuerySupport.findNearestEntity(
                this.golem,
                getServerLevel(this.golem),
                candidates,
                TargetingConditions.forNonCombat().range(6.0),
                area,
                this.golem.getX(),
                this.golem.getY(),
                this.golem.getZ());
        cir.setReturnValue(this.entity != null);
    }
}
