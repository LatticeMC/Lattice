package com.latticemc.lattice.nativelib;

public final class NativeBiologicalAi {

    public enum StimulusKind {
        THREAT,
        PREY,
        FOOD,
        CURIOSITY,
    }

    public enum Action {
        IDLE,
        WANDER,
        REST,
        FLEE,
        PURSUE,
        EAT,
        INVESTIGATE,
    }

    public record Stimulus(StimulusKind kind,
                           float distance,
                           float strength,
                           boolean visible,
                           boolean reachable) {}

    public record Decision(Action action,
                           int stimulusIndex,
                           float urgency,
                           float moveSpeed,
                           float desiredRange) {}

    public record Profile(float fleeHealthThreshold,
                          float fleeDangerThreshold,
                          float attackHealthThreshold,
                          float attackEnergyThreshold,
                          float seekFoodEnergyThreshold,
                          float restEnergyThreshold,
                          float curiosityStrengthThreshold,
                          float closeThreatDistance,
                          float closeFoodDistance) {}

    private static final int VISIBLE_FLAG = 1;
    private static final int REACHABLE_FLAG = 2;
    public static final Profile DEFAULT_PROFILE = new Profile(0.35F, 0.65F, 0.55F, 0.35F, 0.60F, 0.20F, 0.40F, 4.0F, 2.5F);

    private NativeBiologicalAi() {}

    public static boolean isAvailable() {
        LatticeNative.ensureLoaded();
        return LatticeNative.isLoaded();
    }

    public static Decision decide(float healthRatio,
                                  float energyRatio,
                                  float aggression,
                                  float attackRange,
                                  boolean isOnFire,
                                  boolean canAttack,
                                  boolean canConsumeFood,
                                  float ambientDanger,
                                  boolean hasShelter,
                                  boolean canIdleSafely,
                                  boolean canPathToFood,
                                  Stimulus[] stimuli,
                                  Profile profile) {
        final Stimulus[] safeStimuli = stimuli != null ? stimuli : new Stimulus[0];
        final Profile safeProfile = profile != null ? profile : DEFAULT_PROFILE;
        LatticeNative.ensureLoaded();
        if (LatticeNative.isLoaded()) {
            return nativeDecideWrapper(healthRatio, energyRatio, aggression, attackRange,
                    isOnFire, canAttack, canConsumeFood,
                    ambientDanger, hasShelter, canIdleSafely, canPathToFood,
                    safeStimuli, safeProfile);
        }
        return javaDecide(healthRatio, energyRatio, aggression, attackRange,
                isOnFire, canAttack, canConsumeFood,
                ambientDanger, hasShelter, canIdleSafely, canPathToFood,
                safeStimuli, safeProfile);
    }

    public static Decision javaDecide(float healthRatio,
                                      float energyRatio,
                                      float aggression,
                                      float attackRange,
                                      boolean isOnFire,
                                      boolean canAttack,
                                      boolean canConsumeFood,
                                      float ambientDanger,
                                      boolean hasShelter,
                                      boolean canIdleSafely,
                                      boolean canPathToFood,
                                      Stimulus[] stimuli,
                                      Profile profile) {
        final float health = clampUnit(healthRatio, 1.0F);
        final float energy = clampUnit(energyRatio, 1.0F);
        final float aggressionValue = clampUnit(aggression, 0.0F);
        final float attackRangeValue = clampNonNegative(attackRange, 1.5F);
        final float ambientDangerValue = clampUnit(ambientDanger, 0.0F);
        final Profile safeProfile = profile != null ? profile : DEFAULT_PROFILE;

        int threatIndex = -1;
        Stimulus threat = selectBestStimulus(stimuli, StimulusKind.THREAT, true, false);
        if (threat != null) {
            threatIndex = indexOf(stimuli, threat);
        }

        final boolean underImmediateThreat =
                threat != null && clampNonNegative(threat.distance(), 0.0F) <= safeProfile.closeThreatDistance();
        final boolean shouldFlee = isOnFire
                || (threat != null && (health <= safeProfile.fleeHealthThreshold() || ambientDangerValue >= safeProfile.fleeDangerThreshold()))
                || (underImmediateThreat && health < 0.5F);
        if (shouldFlee) {
            final float urgency = Math.max(ambientDangerValue, 1.0F - health);
            final float desiredRange = threat != null
                    ? Math.max(clampNonNegative(threat.distance(), 0.0F), safeProfile.closeThreatDistance())
                    : 8.0F;
            return new Decision(Action.FLEE, threatIndex, urgency,
                    hasShelter ? 1.2F : 1.0F, desiredRange);
        }

        if (canConsumeFood && energy <= safeProfile.seekFoodEnergyThreshold() && canPathToFood) {
            Stimulus food = selectBestStimulus(stimuli, StimulusKind.FOOD, true, true);
            if (food != null) {
                final float distance = clampNonNegative(food.distance(), 0.0F);
                final float urgency = clampUnit(1.0F - energy, 0.0F);
                if (distance <= safeProfile.closeFoodDistance()) {
                    return new Decision(Action.EAT, indexOf(stimuli, food), urgency, 0.2F, 0.0F);
                }
                return new Decision(Action.PURSUE, indexOf(stimuli, food), urgency, 0.8F, 0.5F);
            }
        }

        if (energy <= safeProfile.restEnergyThreshold() && canIdleSafely) {
            return new Decision(Action.REST, -1, 1.0F - energy, 0.0F, 0.0F);
        }

        if (!canIdleSafely || stimuli.length > 0) {
            return new Decision(Action.WANDER, -1, 0.25F, 0.5F, 0.0F);
        }

        return new Decision(Action.IDLE, -1, 0.1F, 0.0F, 0.0F);
    }

    private static Decision nativeDecideWrapper(float healthRatio,
                                                float energyRatio,
                                                float aggression,
                                                float attackRange,
                                                boolean isOnFire,
                                                boolean canAttack,
                                                boolean canConsumeFood,
                                                float ambientDanger,
                                                boolean hasShelter,
                                                boolean canIdleSafely,
                                                boolean canPathToFood,
                                                Stimulus[] stimuli,
                                                Profile profile) {
        final int[] stimulusKinds = new int[stimuli.length];
        final float[] stimulusDistances = new float[stimuli.length];
        final float[] stimulusStrengths = new float[stimuli.length];
        final int[] stimulusFlags = new int[stimuli.length];
        final float[] profileValues = new float[] {
                profile.fleeHealthThreshold(),
                profile.fleeDangerThreshold(),
                profile.attackHealthThreshold(),
                profile.attackEnergyThreshold(),
                profile.seekFoodEnergyThreshold(),
                profile.restEnergyThreshold(),
                profile.curiosityStrengthThreshold(),
                profile.closeThreatDistance(),
                profile.closeFoodDistance(),
        };
        for (int i = 0; i < stimuli.length; ++i) {
            final Stimulus stimulus = stimuli[i];
            stimulusKinds[i] = stimulus.kind().ordinal();
            stimulusDistances[i] = stimulus.distance();
            stimulusStrengths[i] = stimulus.strength();
            int flags = 0;
            if (stimulus.visible()) flags |= VISIBLE_FLAG;
            if (stimulus.reachable()) flags |= REACHABLE_FLAG;
            stimulusFlags[i] = flags;
        }

        final int[] outInts = new int[2];
        final float[] outFloats = new float[3];
        nativeDecide(healthRatio, energyRatio, aggression, attackRange,
                isOnFire, canAttack, canConsumeFood,
                ambientDanger, hasShelter, canIdleSafely, canPathToFood,
                profileValues,
                stimulusKinds, stimulusDistances, stimulusStrengths, stimulusFlags, stimuli.length,
                outInts, outFloats);
        return new Decision(Action.values()[outInts[0]], outInts[1], outFloats[0], outFloats[1], outFloats[2]);
    }

    private static Stimulus selectBestStimulus(Stimulus[] stimuli,
                                               StimulusKind kind,
                                               boolean requireVisible,
                                               boolean requireReachable) {
        Stimulus best = null;
        float bestScore = Float.NEGATIVE_INFINITY;
        for (Stimulus stimulus : stimuli) {
            if (stimulus.kind() != kind) continue;
            if (requireVisible && !stimulus.visible()) continue;
            if (requireReachable && !stimulus.reachable()) continue;
            final float distance = clampNonNegative(stimulus.distance(), 0.0F);
            final float strength = clampNonNegative(stimulus.strength(), 0.0F);
            final float score = strength - distance * 0.1F;
            if (best == null || score > bestScore) {
                best = stimulus;
                bestScore = score;
            }
        }
        return best;
    }

    private static int indexOf(Stimulus[] stimuli, Stimulus target) {
        for (int i = 0; i < stimuli.length; ++i) {
            if (stimuli[i] == target) return i;
        }
        return -1;
    }

    private static float clampUnit(float value, float fallback) {
        if (!Float.isFinite(value)) return fallback;
        return Math.max(0.0F, Math.min(1.0F, value));
    }

    private static float clampNonNegative(float value, float fallback) {
        if (!Float.isFinite(value)) return fallback;
        return Math.max(0.0F, value);
    }

    private static native void nativeDecide(
            float healthRatio,
            float energyRatio,
            float aggression,
            float attackRange,
            boolean isOnFire,
            boolean canAttack,
            boolean canConsumeFood,
            float ambientDanger,
            boolean hasShelter,
            boolean canIdleSafely,
            boolean canPathToFood,
            float[] profileValues,
            int[] stimulusKinds,
            float[] stimulusDistances,
            float[] stimulusStrengths,
            int[] stimulusFlags,
            int stimulusCount,
            int[] outInts,
            float[] outFloats);
}
