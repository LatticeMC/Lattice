package com.latticemc.lattice.mixin;

import com.latticemc.lattice.nativelib.NativeBiologicalAi;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import org.jspecify.annotations.Nullable;

final class HerbivoreAiSupport {
    private static final NativeBiologicalAi.Stimulus[] NO_STIMULI = new NativeBiologicalAi.Stimulus[0];

    private HerbivoreAiSupport() {}

    static @Nullable LivingEntity selectThreat(@Nullable LivingEntity lastHurtByMob,
                                               @Nullable LivingEntity target) {
        return lastHurtByMob != null ? lastHurtByMob : target;
    }

    static NativeBiologicalAi.Stimulus[] buildStimuli(Entity self,
                                                      @Nullable LivingEntity threat,
                                                      @Nullable Player temptingPlayer,
                                                      float foodStrength) {
        if (threat != null && threat.isAlive()) {
            return new NativeBiologicalAi.Stimulus[] {
                    new NativeBiologicalAi.Stimulus(
                            NativeBiologicalAi.StimulusKind.THREAT,
                            (float) Math.sqrt(self.distanceToSqr(threat)),
                            1.0F,
                            true,
                            true)
            };
        }
        if (temptingPlayer != null) {
            return new NativeBiologicalAi.Stimulus[] {
                    new NativeBiologicalAi.Stimulus(
                            NativeBiologicalAi.StimulusKind.FOOD,
                            (float) Math.sqrt(self.distanceToSqr(temptingPlayer)),
                            foodStrength,
                            true,
                            true)
            };
        }
        return NO_STIMULI;
    }

    static boolean applyDecision(Mob mob,
                                 NativeBiologicalAi.Decision decision,
                                 @Nullable LivingEntity threat,
                                 @Nullable Player temptingPlayer,
                                 double minPursueSpeed) {
        if (decision.action() == NativeBiologicalAi.Action.FLEE && threat != null && threat.isAlive()) {
            final double dx = mob.getX() - threat.getX();
            final double dz = mob.getZ() - threat.getZ();
            final double horizontal = Math.sqrt(dx * dx + dz * dz);
            if (horizontal > 1.0E-4) {
                final double scale = 4.0 / horizontal;
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
