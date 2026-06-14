package com.latticemc.lattice.mixin;

import com.latticemc.lattice.nativelib.NativeApproachTargetSampler;
import com.latticemc.lattice.nativelib.NativeBiologicalAi;
import com.latticemc.lattice.nativelib.NativeFleeTargetSampler;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import org.jspecify.annotations.Nullable;

final class PredatoryAnimalAiSupport {
    private PredatoryAnimalAiSupport() {}

    static boolean applyDecision(Mob mob,
                                 NativeBiologicalAi.Decision decision,
                                 @Nullable LivingEntity threat,
                                 @Nullable LivingEntity prey,
                                 @Nullable Player temptingPlayer,
                                 int threatIndex,
                                 int preyIndex,
                                 int foodIndex,
                                 double minPreyPursueSpeed,
                                 double minFoodPursueSpeed) {
        if (decision.action() == NativeBiologicalAi.Action.FLEE && decision.stimulusIndex() == threatIndex && threat != null && threat.isAlive()) {
            double[] candidates = buildFleeCandidates(mob, threat, 6.0);
            int candidate = NativeFleeTargetSampler.sampleFleeTarget(
                    candidates,
                    candidates.length / 3,
                    mob.getX(), mob.getY(), mob.getZ(),
                    threat.getX(), threat.getY(), threat.getZ(),
                    null, 0,
                    0.5);
            if (candidate >= 0) {
                mob.getNavigation().moveTo(
                        candidates[candidate * 3],
                        candidates[candidate * 3 + 1],
                        candidates[candidate * 3 + 2],
                        Math.max(1.2, decision.moveSpeed()));
            } else {
                final double dx = mob.getX() - threat.getX();
                final double dz = mob.getZ() - threat.getZ();
                final double horizontal = Math.sqrt(dx * dx + dz * dz);
                if (horizontal > 1.0E-4) {
                    final double scale = 6.0 / horizontal;
                    mob.getNavigation().moveTo(
                            mob.getX() + dx * scale,
                            mob.getY(),
                            mob.getZ() + dz * scale,
                            Math.max(1.2, decision.moveSpeed()));
                }
            }
            return true;
        }

        if (decision.action() == NativeBiologicalAi.Action.PURSUE && decision.stimulusIndex() == preyIndex && prey != null && prey.isAlive()) {
            return moveToward(mob, prey, 1.5, minPreyPursueSpeed, decision.moveSpeed(), 25.0F, 2.25);
        }

        if (decision.action() == NativeBiologicalAi.Action.INVESTIGATE && decision.stimulusIndex() == preyIndex && prey != null && prey.isAlive()) {
            mob.lookAt(prey, 15.0F, 15.0F);
            return true;
        }

        if (decision.action() == NativeBiologicalAi.Action.EAT && decision.stimulusIndex() == foodIndex && temptingPlayer != null) {
            mob.lookAt(temptingPlayer, 20.0F, 20.0F);
            return true;
        }

        if (decision.action() == NativeBiologicalAi.Action.PURSUE && decision.stimulusIndex() == foodIndex && temptingPlayer != null) {
            return moveToward(mob, temptingPlayer, 2.0, minFoodPursueSpeed, decision.moveSpeed(), 20.0F, 4.0);
        }

        if (decision.action() == NativeBiologicalAi.Action.REST) {
            mob.stopInPlace();
            return true;
        }

        return false;
    }

    private static boolean moveToward(Mob mob,
                                      LivingEntity target,
                                      double preferredDistance,
                                      double minSpeed,
                                      double decisionSpeed,
                                      float lookRot,
                                      double stopDistanceSq) {
        mob.lookAt(target, lookRot, lookRot);
        if (mob.distanceToSqr(target) > stopDistanceSq) {
            double[] candidates = buildApproachCandidates(mob, target, preferredDistance);
            int candidate = NativeApproachTargetSampler.sampleApproachTarget(
                    candidates,
                    candidates.length / 3,
                    mob.getX(), mob.getY(), mob.getZ(),
                    target.getX(), target.getY(), target.getZ(),
                    null, 0,
                    preferredDistance,
                    0.5);
            if (candidate >= 0) {
                mob.getNavigation().moveTo(
                        candidates[candidate * 3],
                        candidates[candidate * 3 + 1],
                        candidates[candidate * 3 + 2],
                        Math.max(minSpeed, decisionSpeed));
            } else {
                mob.getNavigation().moveTo(target, Math.max(minSpeed, decisionSpeed));
            }
        } else {
            mob.getNavigation().stop();
        }
        return true;
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

    private static double[] buildFleeCandidates(Mob mob, LivingEntity threat, double radius) {
        double dx = mob.getX() - threat.getX();
        double dz = mob.getZ() - threat.getZ();
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
        double x = mob.getX();
        double y = mob.getY();
        double z = mob.getZ();
        return new double[] {
                x + ux * radius, y, z + uz * radius,
                x + (ux + px * 0.5) * radius, y, z + (uz + pz * 0.5) * radius,
                x + (ux - px * 0.5) * radius, y, z + (uz - pz * 0.5) * radius,
                x + px * radius, y, z + pz * radius,
                x - px * radius, y, z - pz * radius,
        };
    }
}
