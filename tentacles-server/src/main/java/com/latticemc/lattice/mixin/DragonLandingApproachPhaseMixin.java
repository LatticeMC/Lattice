package com.latticemc.lattice.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.targeting.TargetingConditions;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(targets = "net.minecraft.world.entity.boss.enderdragon.phases.DragonLandingApproachPhase")
public abstract class DragonLandingApproachPhaseMixin {
    @Redirect(
            method = "findNewTarget",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerLevel;getNearestPlayer(Lnet/minecraft/world/entity/ai/targeting/TargetingConditions;Lnet/minecraft/world/entity/LivingEntity;DDD)Lnet/minecraft/world/entity/player/Player;"
            )
    )
    private Player lattice$getNearestPlayer(ServerLevel level,
                                            TargetingConditions targetingConditions,
                                            LivingEntity source,
                                            double x,
                                            double y,
                                            double z) {
        final AABB area = AABB.ofSize(new Vec3(x, y, z), 512.0, 512.0, 512.0);
        return NativeGoalQuerySupport.findNearestEntity(source, level, level.players(), targetingConditions, area, x, y, z);
    }
}
