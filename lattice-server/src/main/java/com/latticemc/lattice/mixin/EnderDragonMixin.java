package com.latticemc.lattice.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.targeting.TargetingConditions;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(EnderDragon.class)
public abstract class EnderDragonMixin {
    @Redirect(
            method = "onCrystalDestroyed",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerLevel;getNearestPlayer(Lnet/minecraft/world/entity/ai/targeting/TargetingConditions;DDD)Lnet/minecraft/world/entity/player/Player;"
            )
    )
    private Player lattice$getNearestPlayer(ServerLevel level,
                                            TargetingConditions targetingConditions,
                                            double x,
                                            double y,
                                            double z) {
        return NativeGoalQuerySupport.findNearestPlayer(level, x, y, z, 256.0, player -> targetingConditions.test(level, null, player));
    }
}
