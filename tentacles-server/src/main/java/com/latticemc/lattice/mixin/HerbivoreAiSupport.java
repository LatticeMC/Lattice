package com.latticemc.lattice.mixin;

import com.latticemc.lattice.nativelib.NativeApproachTargetSampler;
import com.latticemc.lattice.nativelib.NativeBiologicalAi;
import com.latticemc.lattice.nativelib.NativeFleeTargetSampler;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;

final class HerbivoreAiSupport {
    private static final NativeBiologicalAi.Stimulus[] NO_STIMULI = new NativeBiologicalAi.Stimulus[0];

    private HerbivoreAiSupport() {}

    static @Nullable LivingEntity selectThreat(@Nullable LivingEntity lastHurtByMob,
                                               @Nullable LivingEntity target) {
        if (lastHurtByMob != null && lastHurtByMob.isAlive()) return lastHurtByMob;
        if (target != null && target.isAlive()) return target;
        return null;
    }

    static @Nullable LivingEntity findNearestThreat(Mob self,
                                                    ServerLevel level,
                                                    double range,
                                                    Predicate<LivingEntity> predicate) {
        final AABB area = self.getBoundingBox().inflate(range);
        final List<LivingEntity> candidates = level.getEntitiesOfClass(
                LivingEntity.class,
                area,
                entity -> entity != self && entity.isAlive() && !entity.isSpectator() && predicate.test(entity));
        LivingEntity nearest = null;
        double nearestDistance = range * range;
        for (LivingEntity candidate : candidates) {
            final double distance = self.distanceToSqr(candidate);
            if (distance <= nearestDistance) {
                nearest = candidate;
                nearestDistance = distance;
            }
        }
        return nearest;
    }

    static @Nullable LivingEntity cachedThreat(Mob self,
                                               @Nullable LivingEntity cached,
                                               double range,
                                               Predicate<LivingEntity> predicate) {
        if (cached == null || !cached.isAlive() || cached.isSpectator() || !predicate.test(cached)) return null;
        return self.distanceToSqr(cached) <= range * range ? cached : null;
    }

    static boolean shouldRefreshThreatScan(Mob self, int interval) {
        return interval <= 1 || (self.tickCount + self.getId()) % interval == 0;
    }

    static float threatStrength(@Nullable LivingEntity threat) {
        if (threat == null) return 0.0F;
        if (threat instanceof Monster) return 1.0F;
        if (threat instanceof NeutralMob) return 0.7F;
        if (threat instanceof Player) return 0.85F;
        return 0.6F;
    }

    static NativeBiologicalAi.Stimulus[] buildStimuli(Entity self,
                                                       @Nullable LivingEntity threat,
                                                       @Nullable Player temptingPlayer,
                                                       float foodStrength) {
        final boolean hasThreat = threat != null && threat.isAlive();
        final boolean hasFood = temptingPlayer != null;
        if (!hasThreat && !hasFood) return NO_STIMULI;

        final NativeBiologicalAi.Stimulus[] stimuli = new NativeBiologicalAi.Stimulus[(hasThreat ? 1 : 0) + (hasFood ? 1 : 0)];
        int index = 0;
        if (hasThreat) {
            stimuli[index++] = new NativeBiologicalAi.Stimulus(
                    NativeBiologicalAi.StimulusKind.THREAT,
                    (float) Math.sqrt(self.distanceToSqr(threat)),
                    threatStrength(threat),
                    true,
                    true);
        }
        if (hasFood) {
            stimuli[index] = new NativeBiologicalAi.Stimulus(
                    NativeBiologicalAi.StimulusKind.FOOD,
                    (float) Math.sqrt(self.distanceToSqr(temptingPlayer)),
                    foodStrength,
                    true,
                    true);
        }
        return stimuli;
    }

    static boolean applyDecision(Mob mob,
                                 NativeBiologicalAi.Decision decision,
                                 @Nullable LivingEntity threat,
                                 @Nullable Player temptingPlayer,
                                 double minPursueSpeed) {
        try {
            return applyDecisionInner(mob, decision, threat, temptingPlayer, minPursueSpeed);
        } catch (final Exception e) {
            return false;
        }
    }

    private static boolean applyDecisionInner(Mob mob,
                                              NativeBiologicalAi.Decision decision,
                                              @Nullable LivingEntity threat,
                                              @Nullable Player temptingPlayer,
                                              double minPursueSpeed) {
        if (decision.action() == NativeBiologicalAi.Action.FLEE && threat != null && threat.isAlive()) {
            double[] candidates = buildFleeCandidates(mob, threat, 4.0);
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
                        Math.max(1.0, decision.moveSpeed()));
            } else {
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
                            Math.max(minPursueSpeed, decision.moveSpeed()));
                } else {
                    mob.getNavigation().moveTo(temptingPlayer, Math.max(minPursueSpeed, decision.moveSpeed()));
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
