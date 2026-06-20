package com.latticemc.lattice.mixin;

import com.latticemc.lattice.nativelib.NativeEntityQuery;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.sensing.PlayerSensor;
import net.minecraft.world.entity.ai.sensing.Sensor;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PlayerSensor.class)
public abstract class PlayerSensorMixin {
    @Shadow protected abstract double getFollowDistance(LivingEntity entity);

    @Inject(method = "doTick", at = @At("HEAD"), cancellable = true)
    private void lattice$doTick(ServerLevel level, LivingEntity entity, CallbackInfo ci) {
        final double followDistance = this.getFollowDistance(entity);
        final AABB area = entity.getBoundingBox().inflate(followDistance, followDistance, followDistance);
        final List<Player> players = NativeGoalQuerySupport.sortByDistance(
                entity,
                level.players(),
                area,
                entity.getX(),
                entity.getY(),
                entity.getZ(),
                player -> EntitySelector.NO_SPECTATORS.test(player) && entity.closerThan(player, followDistance),
                NativeEntityQuery.PredicateKind.IS_ALIVE_NOT_SPEC);
        final Brain<?> brain = entity.getBrain();
        brain.setMemory(MemoryModuleType.NEAREST_PLAYERS, players);

        final List<Player> visiblePlayers = new ArrayList<>();
        final List<Player> attackablePlayers = new ArrayList<>();
        for (Player player : players) {
            if (!Sensor.isEntityTargetable(level, entity, player)) continue;
            visiblePlayers.add(player);
            if (Sensor.isEntityAttackable(level, entity, player)) {
                attackablePlayers.add(player);
            }
        }

        brain.setMemory(MemoryModuleType.NEAREST_VISIBLE_PLAYER, visiblePlayers.isEmpty() ? null : visiblePlayers.get(0));
        brain.setMemory(MemoryModuleType.NEAREST_VISIBLE_ATTACKABLE_PLAYERS, attackablePlayers);
        brain.setMemory(MemoryModuleType.NEAREST_VISIBLE_ATTACKABLE_PLAYER, attackablePlayers.isEmpty() ? null : attackablePlayers.get(0));
        ci.cancel();
    }
}
