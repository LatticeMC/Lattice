package com.latticemc.lattice.mixin;

import com.latticemc.lattice.nativelib.NativeBiologicalAi;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import org.jspecify.annotations.Nullable;

final class AquaticAiSupport {
    private AquaticAiSupport() {}

    static boolean applyDecision(Mob mob,
                                 NativeBiologicalAi.Decision decision,
                                 @Nullable LivingEntity threat,
                                 @Nullable Player temptingPlayer,
                                 double minPursueSpeed,
                                 boolean prefersWater) {
        if (decision.action() == NativeBiologicalAi.Action.FLEE && threat != null && threat.isAlive()) {
            final double dx = mob.getX() - threat.getX();
            final double dz = mob.getZ() - threat.getZ();
            final double horizontal = Math.sqrt(dx * dx + dz * dz);
            if (horizontal > 1.0E-4) {
                final double scale = (prefersWater ? 5.0 : 4.0) / horizontal;
                mob.getNavigation().moveTo(
                        mob.getX() + dx * scale,
                        mob.getY(),
                        mob.getZ() + dz * scale,
                        Math.max(1.0, decision.moveSpeed()));
            }
            return true;
        }

        if (decision.action() == NativeBiologicalAi.Action.REST) {
            mob.stopInPlace();
            return true;
        }

        if (temptingPlayer == null) {
            return false;
        }

        final double distanceSq = mob.distanceToSqr(temptingPlayer);
        if (decision.action() == NativeBiologicalAi.Action.EAT) {
            mob.lookAt(temptingPlayer, 20.0F, 20.0F);
            if (distanceSq <= 9.0) {
                mob.stopInPlace();
            }
            return true;
        }

        if (decision.action() == NativeBiologicalAi.Action.PURSUE) {
            mob.lookAt(temptingPlayer, 20.0F, 20.0F);
            if (distanceSq > 6.25) {
                mob.getNavigation().moveTo(temptingPlayer, Math.max(minPursueSpeed, decision.moveSpeed()));
            } else {
                mob.getNavigation().stop();
            }
            return true;
        }

        if (decision.action() == NativeBiologicalAi.Action.INVESTIGATE) {
            mob.lookAt(temptingPlayer, 10.0F, 10.0F);
            return true;
        }

        return false;
    }
}
