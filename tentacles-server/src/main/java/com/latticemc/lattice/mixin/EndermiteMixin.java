package com.latticemc.lattice.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Endermite;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.LevelAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Endermite.class)
public abstract class EndermiteMixin {
    @Inject(method = "checkEndermiteSpawnRules", at = @At("HEAD"), cancellable = true)
    private static void lattice$checkEndermiteSpawnRules(EntityType<Endermite> entityType,
                                                         LevelAccessor level,
                                                         EntitySpawnReason spawnReason,
                                                         BlockPos pos,
                                                         RandomSource random,
                                                         CallbackInfoReturnable<Boolean> cir) {
        if (!Endermite.checkAnyLightMonsterSpawnRules(entityType, level, spawnReason, pos, random)) {
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
