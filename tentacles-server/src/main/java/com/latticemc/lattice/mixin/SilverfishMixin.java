package com.latticemc.lattice.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Silverfish;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.LevelAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Silverfish.class)
public abstract class SilverfishMixin {
    @Inject(method = "checkSilverfishSpawnRules", at = @At("HEAD"), cancellable = true)
    private static void lattice$checkSilverfishSpawnRules(EntityType<Silverfish> entityType,
                                                          LevelAccessor level,
                                                          EntitySpawnReason spawnReason,
                                                          BlockPos pos,
                                                          RandomSource random,
                                                          CallbackInfoReturnable<Boolean> cir) {
        if (!Silverfish.checkAnyLightMonsterSpawnRules(entityType, level, spawnReason, pos, random)) {
            cir.setReturnValue(false);
            return;
        }
        if (EntitySpawnReason.isSpawner(spawnReason)) {
            cir.setReturnValue(true);
            return;
        }

        final Player nearestPlayer = NativeGoalQuerySupport.findNearestPlayer(
                level,
                pos.getX() + 0.5,
                pos.getY() + 0.5,
                pos.getZ() + 0.5,
                5.0,
                EntitySelector.NO_CREATIVE_OR_SPECTATOR::test);
        cir.setReturnValue(nearestPlayer == null);
    }
}
