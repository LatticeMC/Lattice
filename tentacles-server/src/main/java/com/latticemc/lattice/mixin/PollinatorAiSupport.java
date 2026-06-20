package com.latticemc.lattice.mixin;

import com.latticemc.lattice.nativelib.NativeApproachTargetSampler;
import com.latticemc.lattice.nativelib.NativeBiologicalAi;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import org.jspecify.annotations.Nullable;

final class PollinatorAiSupport {
    private PollinatorAiSupport() {}

    static boolean applyDecision(Mob mob,
                                 NativeBiologicalAi.Decision decision,
                                 @Nullable LivingEntity prey,
                                 @Nullable Player temptingPlayer,
                                 int preyIndex,
                                 int foodIndex,
                                 double minPreyPursueSpeed,
                                 double minFoodPursueSpeed) {
        if (decision.action() == NativeBiologicalAi.Action.PURSUE && decision.stimulusIndex() == preyIndex && prey != null && prey.isAlive()) {
            mob.lookAt(prey, 25.0F, 25.0F);
            if (mob.distanceToSqr(prey) > 2.25) {
                double[] candidates = buildApproachCandidates(mob, prey, 1.5);
                int candidate = NativeApproachTargetSampler.sampleApproachTarget(
                        candidates,
                        candidates.length / 3,
                        mob.getX(), mob.getY(), mob.getZ(),
                        prey.getX(), prey.getY(), prey.getZ(),
                        null, 0,
                        1.5,
                        0.5);
                if (candidate >= 0) {
                    mob.getNavigation().moveTo(
                            candidates[candidate * 3],
                            candidates[candidate * 3 + 1],
                            candidates[candidate * 3 + 2],
                            Math.max(minPreyPursueSpeed, decision.moveSpeed()));
                } else {
                    mob.getNavigation().moveTo(prey, Math.max(minPreyPursueSpeed, decision.moveSpeed()));
                }
            } else {
                mob.getNavigation().stop();
            }
            return true;
        }

        if (decision.action() == NativeBiologicalAi.Action.PURSUE && decision.stimulusIndex() == foodIndex && temptingPlayer != null) {
            mob.lookAt(temptingPlayer, 20.0F, 20.0F);
            if (mob.distanceToSqr(temptingPlayer) > 4.0) {
                double[] candidates = buildApproachCandidates(mob, temptingPlayer, 2.0);
                int candidate = NativeApproachTargetSampler.sampleApproachTarget(
                        candidates,
                        candidates.length / 3,
                        mob.getX(), mob.getY(), mob.getZ(),
                        temptingPlayer.getX(), temptingPlayer.getY(), temptingPlayer.getZ(),
                        null, 0,
                        2.0,
                        0.5);
                if (candidate >= 0) {
                    mob.getNavigation().moveTo(
                            candidates[candidate * 3],
                            candidates[candidate * 3 + 1],
                            candidates[candidate * 3 + 2],
                            Math.max(minFoodPursueSpeed, decision.moveSpeed()));
                } else {
                    mob.getNavigation().moveTo(temptingPlayer, Math.max(minFoodPursueSpeed, decision.moveSpeed()));
                }
            } else {
                mob.getNavigation().stop();
            }
            return true;
        }

        if (decision.action() == NativeBiologicalAi.Action.EAT && decision.stimulusIndex() == foodIndex && temptingPlayer != null) {
            mob.lookAt(temptingPlayer, 20.0F, 20.0F);
            if (mob.distanceToSqr(temptingPlayer) <= 9.0) {
                mob.stopInPlace();
            }
            return true;
        }

        if (decision.action() == NativeBiologicalAi.Action.INVESTIGATE && decision.stimulusIndex() == foodIndex && temptingPlayer != null) {
            mob.lookAt(temptingPlayer, 15.0F, 15.0F);
            return true;
        }

        return false;
    }

    private static double[] buildApproachCandidates(Mob mob, LivingEntity target, double preferredDistance) {
        double dx = target.getX() - mob.getX();
        double dz = target.getZ() - mob.getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        if (horizontal < 1.0E-4) {
            dx = 1.0;
            dz = 0.0;
            horizontal = 1.0;
        }
        double ux = dx / horizontal;
        double uz = dz / horizontal;
        double px = -uz;
        double pz = ux;
        double tx = target.getX();
        double ty = target.getY();
        double tz = target.getZ();
        return new double[] {
                tx - ux * preferredDistance, ty, tz - uz * preferredDistance,
                tx - (ux + px * 0.5) * preferredDistance, ty, tz - (uz + pz * 0.5) * preferredDistance,
                tx - (ux - px * 0.5) * preferredDistance, ty, tz - (uz - pz * 0.5) * preferredDistance,
                tx - px * preferredDistance, ty, tz - pz * preferredDistance,
                tx + px * preferredDistance, ty, tz + pz * preferredDistance,
        };
    }
}
