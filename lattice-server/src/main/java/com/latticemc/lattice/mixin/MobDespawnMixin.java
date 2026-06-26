package com.latticemc.lattice.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(Mob.class)
public abstract class MobDespawnMixin {
    @Redirect(
            method = "checkDespawn",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/Level;getNearestPlayer(Lnet/minecraft/world/entity/Entity;D)Lnet/minecraft/world/entity/player/Player;"
            )
    )
    private Player lattice$getNearestPlayer(Level level, Entity source, double distance) {
        if (distance < 0.0) {
            return level.getNearestPlayer(source.getX(), source.getY(), source.getZ(), distance, false);
        }
        return NativeGoalQuerySupport.findNearestPlayer(
                level,
                source.getX(),
                source.getY(),
                source.getZ(),
                distance,
                player -> !player.isSpectator());
    }
}
