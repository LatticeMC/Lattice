package com.latticemc.lattice.mixin;

import java.util.function.Predicate;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.EntityGetter;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(EntityGetter.class)
public interface EntityGetterMixin {
    @Inject(method = "getNearestPlayer(DDDDLjava/util/function/Predicate;)Lnet/minecraft/world/entity/player/Player;",
            at = @At("HEAD"), cancellable = true)
    private void lattice$getNearestPlayer(double x,
                                          double y,
                                          double z,
                                          double distance,
                                          @Nullable Predicate<Entity> predicate,
                                          CallbackInfoReturnable<Player> cir) {
        if (distance < 0.0) return;
        final EntityGetter self = (EntityGetter) this;
        cir.setReturnValue(NativeGoalQuerySupport.findNearestPlayer(
                self,
                x,
                y,
                z,
                distance,
                player -> predicate == null || predicate.test(player)));
    }

    @Inject(method = "hasNearbyAlivePlayer", at = @At("HEAD"), cancellable = true)
    private void lattice$hasNearbyAlivePlayer(double x,
                                              double y,
                                              double z,
                                              double distance,
                                              CallbackInfoReturnable<Boolean> cir) {
        if (distance < 0.0) return;
        final EntityGetter self = (EntityGetter) this;
        cir.setReturnValue(NativeGoalQuerySupport.findNearestPlayer(
                self,
                x,
                y,
                z,
                distance,
                player -> EntitySelector.NO_SPECTATORS.test(player)
                        && EntitySelector.LIVING_ENTITY_STILL_ALIVE.test(player)) != null);
    }
}
