package com.latticemc.lattice.mixin;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.AvoidEntityGoal;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.ai.targeting.TargetingConditions;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(AvoidEntityGoal.class)
public abstract class AvoidEntityGoalMixin<T extends LivingEntity> extends Goal {
    @Shadow protected PathfinderMob mob;
    @Shadow protected @Nullable T toAvoid;
    @Shadow @Final protected float maxDist;
    @Shadow protected @Nullable Path path;
    @Shadow @Final protected PathNavigation pathNav;
    @Shadow @Final protected Class<T> avoidClass;
    @Shadow @Final private TargetingConditions avoidEntityTargeting;

    @Inject(method = "canUse", at = @At("HEAD"), cancellable = true)
    private void lattice$canUse(CallbackInfoReturnable<Boolean> cir) {
        final AABB area = this.mob.getBoundingBox().inflate(this.maxDist, 3.0, this.maxDist);
        this.toAvoid = NativeGoalQuerySupport.findNearestEntity(
                this.mob,
                getServerLevel(this.mob),
                this.mob.level().getEntitiesOfClass(this.avoidClass, area, livingEntity -> true),
                this.avoidEntityTargeting,
                area,
                this.mob.getX(),
                this.mob.getY(),
                this.mob.getZ());
        if (this.toAvoid == null) {
            cir.setReturnValue(false);
            return;
        }

        final Vec3 posAway = DefaultRandomPos.getPosAway(this.mob, 16, 7, this.toAvoid.position());
        if (posAway == null) {
            cir.setReturnValue(false);
            return;
        }
        if (this.toAvoid.distanceToSqr(posAway.x, posAway.y, posAway.z) < this.toAvoid.distanceToSqr(this.mob)) {
            cir.setReturnValue(false);
            return;
        }

        this.path = this.pathNav.createPath(posAway.x, posAway.y, posAway.z, 0);
        cir.setReturnValue(this.path != null);
    }
}
