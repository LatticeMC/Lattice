package com.latticemc.lattice.mixin;

import java.util.List;
import net.minecraft.server.level.ServerEntityGetter;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.targeting.TargetingConditions;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerEntityGetter.class)
public interface ServerEntityGetterMixin {
    @Shadow ServerLevel getLevel();

    @Inject(method = "getNearestEntity(Ljava/util/List;Lnet/minecraft/world/entity/ai/targeting/TargetingConditions;Lnet/minecraft/world/entity/LivingEntity;DDD)Lnet/minecraft/world/entity/LivingEntity;",
            at = @At("HEAD"), cancellable = true)
    private <T extends LivingEntity> void lattice$getNearestEntity(List<? extends T> entities,
                                                                   TargetingConditions targetingConditions,
                                                                   @Nullable LivingEntity source,
                                                                   double x,
                                                                   double y,
                                                                   double z,
                                                                   CallbackInfoReturnable<T> cir) {
        if (entities.size() < 8) return;

        double range = 0.0;
        for (T entity : entities) {
            final double dx = Math.abs(entity.getX() - x);
            final double dy = Math.abs(entity.getY() - y);
            final double dz = Math.abs(entity.getZ() - z);
            range = Math.max(range, Math.max(dx, Math.max(dy, dz)) + 2.0);
        }
        final AABB area = AABB.ofSize(new net.minecraft.world.phys.Vec3(x, y, z), range * 2.0, range * 2.0, range * 2.0);
        cir.setReturnValue(NativeGoalQuerySupport.findNearestEntityNullableSource(
                source,
                this.getLevel(),
                entities,
                targetingConditions,
                area,
                x,
                y,
                z));
    }
}
