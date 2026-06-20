package com.latticemc.lattice.mixin;

import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.targeting.TargetingConditions;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractHorse.class)
public abstract class AbstractHorseFollowMommyMixin {
    private static final TargetingConditions.Selector lattice$PARENT_HORSE_SELECTOR = (entity, level) -> entity instanceof AbstractHorse abstractHorse
            && abstractHorse.isBred();
    private static final TargetingConditions lattice$MOMMY_TARGETING = TargetingConditions.forNonCombat()
            .range(16.0)
            .ignoreLineOfSight()
            .selector(lattice$PARENT_HORSE_SELECTOR);

    @Inject(method = "followMommy", at = @At("HEAD"), cancellable = true)
    private void lattice$followMommy(ServerLevel level, CallbackInfo ci) {
        final AbstractHorse horse = (AbstractHorse) (Object) this;
        if (!horse.isBred() || !horse.isBaby() || horse.isEating()) {
            ci.cancel();
            return;
        }

        final AABB area = horse.getBoundingBox().inflate(16.0);
        final List<AbstractHorse> candidates = level.getEntitiesOfClass(AbstractHorse.class, area, entity -> true);
        final LivingEntity nearestEntity = NativeGoalQuerySupport.findNearestEntity(
                horse,
                level,
                candidates,
                lattice$MOMMY_TARGETING,
                area,
                horse.getX(),
                horse.getY(),
                horse.getZ());
        if (nearestEntity != null && horse.distanceToSqr(nearestEntity) > 4.0) {
            horse.getNavigation().createPath(nearestEntity, 0);
        }
        ci.cancel();
    }
}
