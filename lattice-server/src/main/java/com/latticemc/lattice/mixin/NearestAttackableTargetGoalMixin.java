package com.latticemc.lattice.mixin;

import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.ai.goal.target.TargetGoal;
import net.minecraft.world.entity.ai.targeting.TargetingConditions;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(NearestAttackableTargetGoal.class)
public abstract class NearestAttackableTargetGoalMixin<T extends LivingEntity> extends TargetGoal {
    @Shadow protected Class<T> targetType;
    @Shadow protected @Nullable LivingEntity target;
    @Shadow protected TargetingConditions targetConditions;
    @Shadow protected abstract AABB getTargetSearchArea(double targetDistance);

    private NearestAttackableTargetGoalMixin(Mob mob, boolean mustSee, boolean mustReach) {
        super(mob, mustSee, mustReach);
    }

    @Inject(method = "findTarget", at = @At("HEAD"), cancellable = true)
    private void lattice$findTarget(CallbackInfo ci) {
        final ServerLevel level = getServerLevel(this.mob);
        final double followDistance = this.getFollowDistance();
        final TargetingConditions conditions = this.targetConditions.range(followDistance);
        if (this.targetType != Player.class && this.targetType != ServerPlayer.class) {
            final AABB area = this.getTargetSearchArea(followDistance);
            final List<T> candidates = this.mob.level().getEntitiesOfClass(this.targetType, area, entity -> true);
            this.target = NativeGoalQuerySupport.findNearestEntity(
                    this.mob,
                    level,
                    candidates,
                    conditions,
                    area,
                    this.mob.getX(),
                    this.mob.getEyeY(),
                    this.mob.getZ());
        } else {
            final AABB area = this.mob.getBoundingBox().inflate(followDistance, followDistance, followDistance);
            this.target = NativeGoalQuerySupport.findNearestEntity(
                    this.mob,
                    level,
                    level.players(),
                    conditions,
                    area,
                    this.mob.getX(),
                    this.mob.getEyeY(),
                    this.mob.getZ());
        }
        ci.cancel();
    }
}
