package com.latticemc.lattice.mixin;

import com.latticemc.lattice.nativelib.NativeBiologicalAi;
import com.latticemc.lattice.nativelib.NativeWaterTargetSampler;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
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
                double[] candidates = buildFleeCandidates(mob, dx / horizontal, dz / horizontal, prefersWater ? 5.0 : 4.0);
                boolean[] water = sampleWaterFlags(mob, candidates);
                int candidate = NativeWaterTargetSampler.sampleWaterTarget(
                        candidates,
                        water,
                        water.length,
                        mob.getX(), mob.getY(), mob.getZ(),
                        prefersWater);
                if (candidate >= 0) {
                    mob.getNavigation().moveTo(
                            candidates[candidate * 3],
                            candidates[candidate * 3 + 1],
                            candidates[candidate * 3 + 2],
                            Math.max(1.0, decision.moveSpeed()));
                }
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
                double[] candidates = buildApproachCandidates(mob, temptingPlayer, 2.0);
                boolean[] water = sampleWaterFlags(mob, candidates);
                int candidate = NativeWaterTargetSampler.sampleWaterTarget(
                        candidates,
                        water,
                        water.length,
                        mob.getX(), mob.getY(), mob.getZ(),
                        prefersWater);
                if (candidate >= 0) {
                    mob.getNavigation().moveTo(
                            candidates[candidate * 3],
                            candidates[candidate * 3 + 1],
                            candidates[candidate * 3 + 2],
                            Math.max(minPursueSpeed, decision.moveSpeed()));
                }
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

    private static boolean[] sampleWaterFlags(Mob mob, double[] candidates) {
        boolean[] water = new boolean[candidates.length / 3];
        for (int i = 0; i < water.length; ++i) {
            water[i] = mob.level().getFluidState(BlockPos.containing(
                    candidates[i * 3],
                    candidates[i * 3 + 1],
                    candidates[i * 3 + 2])).is(FluidTags.WATER);
        }
        return water;
    }

    private static double[] buildFleeCandidates(Mob mob, double ux, double uz, double radius) {
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
