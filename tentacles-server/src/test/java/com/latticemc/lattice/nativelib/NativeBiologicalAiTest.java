package com.latticemc.lattice.nativelib;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class NativeBiologicalAiTest {

    private static NativeBiologicalAi.Decision decide(
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
            NativeBiologicalAi.Stimulus[] stimuli,
            NativeBiologicalAi.Profile profile) {
        return NativeBiologicalAi.javaDecide(
                healthRatio,
                energyRatio,
                aggression,
                attackRange,
                isOnFire,
                canAttack,
                canConsumeFood,
                ambientDanger,
                hasShelter,
                canIdleSafely,
                canPathToFood,
                stimuli,
                profile);
    }

    private static NativeBiologicalAi.Decision decideSpecies(
            NativeBiologicalAi.Species species,
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
            NativeBiologicalAi.Stimulus[] stimuli,
            NativeBiologicalAi.Profile fallbackProfile) {
        return NativeBiologicalAi.decide(
                species,
                healthRatio,
                energyRatio,
                aggression,
                attackRange,
                isOnFire,
                canAttack,
                canConsumeFood,
                ambientDanger,
                hasShelter,
                canIdleSafely,
                canPathToFood,
                stimuli,
                fallbackProfile);
    }

    @Test
    void sheepProfileSeeksFoodEarlierThanPigProfile() {
        NativeBiologicalAi.Stimulus[] stimuli = {
                new NativeBiologicalAi.Stimulus(NativeBiologicalAi.StimulusKind.FOOD, 3.0F, 0.8F, true, true)
        };

        NativeBiologicalAi.Decision sheep = decide(0.9F, 0.82F, 0.0F, 1.5F,
                false, false, true,
                0.0F, true, true, true,
                stimuli, BiologicalAiProfiles.SHEEP);

        NativeBiologicalAi.Decision pig = decide(0.9F, 0.82F, 0.0F, 1.5F,
                false, false, true,
                0.0F, true, true, true,
                stimuli, BiologicalAiProfiles.PIG);

        assertEquals(NativeBiologicalAi.Action.PURSUE, sheep.action());
        assertEquals(NativeBiologicalAi.Action.WANDER, pig.action());
    }

    @Test
    void rabbitProfileSeeksFoodAtLowEnergy() {
        NativeBiologicalAi.Stimulus[] stimuli = {
                new NativeBiologicalAi.Stimulus(NativeBiologicalAi.StimulusKind.FOOD, 2.0F, 1.0F, true, true)
        };

        NativeBiologicalAi.Decision rabbit = decide(0.8F, 0.4F, 0.0F, 1.0F,
                false, false, true,
                0.0F, true, true, true,
                stimuli, BiologicalAiProfiles.RABBIT);

        assertEquals(NativeBiologicalAi.Action.EAT, rabbit.action());
    }

    @Test
    void beeProfilePursuesThreatWhenAttackEnabled() {
        NativeBiologicalAi.Stimulus[] stimuli = {
                new NativeBiologicalAi.Stimulus(NativeBiologicalAi.StimulusKind.PREY, 3.0F, 1.0F, true, true)
        };

        NativeBiologicalAi.Decision bee = decide(0.9F, 0.7F, 0.85F, 1.4F,
                false, true, false,
                0.8F, true, false, false,
                stimuli, BiologicalAiProfiles.BEE);

        assertEquals(NativeBiologicalAi.Action.PURSUE, bee.action());
    }

    @Test
    void goatProfileStaysCalmerThanChickenUnderSameThreat() {
        NativeBiologicalAi.Stimulus[] stimuli = {
                new NativeBiologicalAi.Stimulus(NativeBiologicalAi.StimulusKind.THREAT, 5.0F, 1.0F, true, true)
        };

        NativeBiologicalAi.Decision goat = decide(0.5F, 0.6F, 0.0F, 1.0F,
                false, false, true,
                0.5F, true, true, false,
                stimuli, BiologicalAiProfiles.GOAT);

        assertEquals(NativeBiologicalAi.Action.WANDER, goat.action());
    }

    @Test
    void armadilloProfileFleesEarlierThanCowProfile() {
        NativeBiologicalAi.Stimulus[] stimuli = {
                new NativeBiologicalAi.Stimulus(NativeBiologicalAi.StimulusKind.THREAT, 5.0F, 1.0F, true, true)
        };

        NativeBiologicalAi.Decision armadillo = decide(0.65F, 0.95F, 0.0F, 1.0F,
                false, false, true,
                1.0F, true, false, false,
                stimuli, BiologicalAiProfiles.ARMADILLO);

        NativeBiologicalAi.Decision cow = decide(0.65F, 0.95F, 0.0F, 1.0F,
                false, false, true,
                1.0F, true, false, false,
                stimuli, BiologicalAiProfiles.COW);

        assertEquals(NativeBiologicalAi.Action.FLEE, armadillo.action());
        assertEquals(NativeBiologicalAi.Action.WANDER, cow.action());
    }

    @Test
    void camelProfileSeeksFoodEarlierThanCowWhenRestingPoseEnergyIsLow() {
        NativeBiologicalAi.Stimulus[] stimuli = {
                new NativeBiologicalAi.Stimulus(NativeBiologicalAi.StimulusKind.FOOD, 3.0F, 0.8F, true, true)
        };

        NativeBiologicalAi.Decision camel = decide(0.9F, 0.35F, 0.0F, 1.5F,
                false, false, true,
                0.0F, true, true, true,
                stimuli, BiologicalAiProfiles.CAMEL);

        NativeBiologicalAi.Decision cow = decide(0.9F, 0.35F, 0.0F, 1.5F,
                false, false, true,
                0.0F, true, true, true,
                stimuli, BiologicalAiProfiles.COW);

        assertEquals(NativeBiologicalAi.Action.EAT, camel.action());
        assertEquals(NativeBiologicalAi.Action.EAT, cow.action());
    }

    @Test
    void frogProfileCanPursueFoodWhileWaterBiased() {
        NativeBiologicalAi.Stimulus[] stimuli = {
                new NativeBiologicalAi.Stimulus(NativeBiologicalAi.StimulusKind.FOOD, 2.0F, 0.75F, true, true)
        };

        NativeBiologicalAi.Decision frog = decide(0.9F, 0.85F, 0.15F, 1.2F,
                false, false, true,
                0.0F, true, true, true,
                stimuli, BiologicalAiProfiles.FROG);

        assertEquals(NativeBiologicalAi.Action.WANDER, frog.action());
    }

    @Test
    void turtleProfilePrefersFoodSeekingWhenEggCarryingEnergyDrops() {
        NativeBiologicalAi.Stimulus[] stimuli = {
                new NativeBiologicalAi.Stimulus(NativeBiologicalAi.StimulusKind.FOOD, 2.0F, 0.8F, true, true)
        };

        NativeBiologicalAi.Decision turtle = decide(0.9F, 0.40F, 0.0F, 1.0F,
                false, false, true,
                0.0F, true, true, true,
                stimuli, BiologicalAiProfiles.TURTLE);

        assertEquals(NativeBiologicalAi.Action.EAT, turtle.action());
    }

    @Test
    void axolotlProfileRestsWhenPlayingDeadEnergyCollapses() {
        NativeBiologicalAi.Decision axolotl = decide(0.9F, 0.20F, 0.10F, 1.0F,
                false, false, true,
                1.0F, true, true, false,
                new NativeBiologicalAi.Stimulus[0], BiologicalAiProfiles.AXOLOTL);

        assertEquals(NativeBiologicalAi.Action.REST, axolotl.action());
    }

    @Test
    void snifferProfileLeansTowardExplorationRatherThanEarlyFoodPursuit() {
        NativeBiologicalAi.Stimulus[] stimuli = {
                new NativeBiologicalAi.Stimulus(NativeBiologicalAi.StimulusKind.FOOD, 3.0F, 0.7F, true, true)
        };

        NativeBiologicalAi.Decision sniffer = decide(0.9F, 0.90F, 0.10F, 1.0F,
                false, false, true,
                0.0F, true, false, true,
                stimuli, BiologicalAiProfiles.SNIFFER);

        assertEquals(NativeBiologicalAi.Action.WANDER, sniffer.action());
    }

    @Test
    void llamaProfileCanStillPursueFoodAtModerateEnergy() {
        NativeBiologicalAi.Stimulus[] stimuli = {
                new NativeBiologicalAi.Stimulus(NativeBiologicalAi.StimulusKind.FOOD, 3.0F, 0.75F, true, true)
        };

        NativeBiologicalAi.Decision llama = decide(0.9F, 0.70F, 0.10F, 1.4F,
                false, false, true,
                0.0F, true, true, true,
                stimuli, BiologicalAiProfiles.LLAMA);

        assertEquals(NativeBiologicalAi.Action.PURSUE, llama.action());
    }

    @Test
    void speciesRoutingFallsBackToProfileWhenNativeIsUnavailable() {
        NativeBiologicalAi.Stimulus[] stimuli = {
                new NativeBiologicalAi.Stimulus(NativeBiologicalAi.StimulusKind.THREAT, 5.0F, 1.0F, true, true)
        };

        NativeBiologicalAi.Decision chicken = decideSpecies(NativeBiologicalAi.Species.CHICKEN,
                0.5F, 0.6F, 0.0F, 1.0F,
                false, false, true,
                0.5F, true, true, false,
                stimuli, BiologicalAiProfiles.CHICKEN);

        assertEquals(NativeBiologicalAi.Action.FLEE, chicken.action());
    }

    @Test
    void pandaProfileRestsWhenSittingAndFoodIsNearby() {
        NativeBiologicalAi.Stimulus[] stimuli = {
                new NativeBiologicalAi.Stimulus(NativeBiologicalAi.StimulusKind.FOOD, 2.0F, 0.8F, true, true)
        };

        NativeBiologicalAi.Decision panda = decide(0.9F, 0.30F, 0.05F, 1.2F,
                false, false, true,
                0.0F, true, true, true,
                stimuli, BiologicalAiProfiles.PANDA);

        assertEquals(NativeBiologicalAi.Action.EAT, panda.action());
    }

    @Test
    void approachSamplerPrefersPreferredDistance() {
        double[] candidates = {
                1.0, 0.0, 0.0,
                3.0, 0.0, 0.0,
                6.0, 0.0, 0.0,
        };

        int result = NativeApproachTargetSampler.javaSampleApproachTarget(
                candidates,
                3,
                0.0, 0.0, 0.0,
                8.0, 0.0, 0.0,
                null, 0,
                2.0,
                0.5);

        assertEquals(2, result);
    }

    @Test
    void waterSamplerPrefersWaterCandidatesWhenRequested() {
        double[] candidates = {
                1.0, 0.0, 0.0,
                2.0, 0.0, 0.0,
                3.0, 0.0, 0.0,
        };
        boolean[] isWater = {false, true, false};

        int result = NativeWaterTargetSampler.javaSampleWaterTarget(
                candidates,
                isWater,
                3,
                0.0, 0.0, 0.0,
                true);

        assertEquals(1, result);
    }

    @Test
    void ocelotProfileCanPursuePreyWhileStillSupportingFoodStimulus() {
        NativeBiologicalAi.Stimulus[] stimuli = {
                new NativeBiologicalAi.Stimulus(NativeBiologicalAi.StimulusKind.PREY, 4.0F, 0.85F, true, true),
                new NativeBiologicalAi.Stimulus(NativeBiologicalAi.StimulusKind.FOOD, 3.0F, 0.70F, true, true)
        };

        NativeBiologicalAi.Decision ocelot = decide(0.9F, 0.9F, 0.55F, 1.33F,
                false, true, true,
                0.0F, true, true, true,
                stimuli, BiologicalAiProfiles.OCELOT);

        assertEquals(NativeBiologicalAi.Action.PURSUE, ocelot.action());
        assertEquals(0, ocelot.stimulusIndex());
    }

    @Test
    void homeSamplerPrefersCandidateNearHomeRadius() {
        double[] candidates = {
                1.0, 0.0, 0.0,
                4.0, 0.0, 0.0,
                8.0, 0.0, 0.0,
        };

        int result = NativeHomeTargetSampler.javaSampleHomeTarget(
                candidates,
                3,
                10.0, 0.0, 0.0,
                0.0, 0.0, 0.0,
                null, 0,
                4.0,
                0.5);

        assertEquals(1, result);
    }
}
