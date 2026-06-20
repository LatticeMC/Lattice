package com.latticemc.lattice.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.targeting.TargetingConditions;
import net.minecraft.world.entity.ambient.Bat;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(Bat.class)
public abstract class BatMixin {
    @Redirect(
            method = "customServerAiStep",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerLevel;getNearestPlayer(Lnet/minecraft/world/entity/ai/targeting/TargetingConditions;Lnet/minecraft/world/entity/LivingEntity;)Lnet/minecraft/world/entity/player/Player;"
            )
    )
    private Player lattice$getNearestPlayer(ServerLevel level, TargetingConditions targetingConditions, LivingEntity source) {
        final AABB area = source.getBoundingBox().inflate(4.0, 4.0, 4.0);
        return NativeGoalQuerySupport.findNearestEntity(
                source,
                level,
                level.players(),
                targetingConditions,
                area,
                source.getX(),
                source.getY(),
                source.getZ());
    }
}
