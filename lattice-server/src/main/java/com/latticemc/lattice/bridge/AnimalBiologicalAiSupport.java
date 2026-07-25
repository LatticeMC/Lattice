package com.latticemc.lattice.bridge;

import com.latticemc.lattice.nativelib.BiologicalAiProfiles;
import com.latticemc.lattice.nativelib.LatticeNative;
import com.latticemc.lattice.nativelib.NativeBiologicalAi;
import java.util.function.Predicate;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.chicken.Chicken;
import net.minecraft.world.entity.animal.feline.Ocelot;
import net.minecraft.world.entity.animal.fox.Fox;
import net.minecraft.world.entity.animal.parrot.Parrot;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.entity.animal.polarbear.PolarBear;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;

public final class AnimalBiologicalAiSupport {
    private static final int CHICKEN_THREAT_SCAN_INTERVAL = 8;
    private static final double CHICKEN_THREAT_SCAN_RANGE = 8.0;
    private static final int POLAR_BEAR_PREY_SCAN_INTERVAL = 8;
    private static final double POLAR_BEAR_PREY_SCAN_RANGE = 10.0;
    private static final int POLAR_BEAR_BABY_ALERT_SCAN_INTERVAL = 8;
    private static final double POLAR_BEAR_BABY_ALERT_SCAN_RANGE_XZ = 8.0;
    private static final double POLAR_BEAR_BABY_ALERT_SCAN_RANGE_Y = 4.0;

    private AnimalBiologicalAiSupport() {}

    public static void run(Animal animal, ServerLevel level) {
        runPig(animal, level);
        runChicken(animal, level);
        runPolarBear(animal, level);
        runParrot(animal, level);
    }

    private static void runPig(Animal animal, ServerLevel level) {
        if (!(animal instanceof Pig pig)) return;
        if (!LatticeNative.isLoaded()) return;
        if (pig.isPassenger() || pig.isVehicle()) return;
        if (pig.getControllingPassenger() != null) return;

        float maxHealth = pig.getMaxHealth();
        if (maxHealth <= 0.0F) return;

        Mob mob = pig;
        boolean inLove = pig.isInLove();
        LivingEntity threat = HerbivoreAiSupport.selectThreat(pig.getLastHurtByMob(), pig.getTarget());
        Player temptingPlayer = !inLove
            ? level.getNearestPlayer(
                pig.getX(),
                pig.getY(),
                pig.getZ(),
                10.0,
                entity -> entity instanceof Player player
                    && (player.isHolding(Items.CARROT_ON_A_STICK) || pig.isFood(player.getMainHandItem()))
            )
            : null;

        NativeBiologicalAi.Decision decision = NativeBiologicalAi.decide(
            NativeBiologicalAi.Species.PIG,
            pig.getHealth() / maxHealth,
            inLove ? 0.35F : (pig.isSaddled() ? 0.85F : (pig.isBaby() ? 0.60F : 0.75F)),
            0.0F,
            1.5F,
            pig.isOnFire(),
            false,
            temptingPlayer != null,
            HerbivoreAiSupport.threatStrength(threat),
            !level.canSeeSky(pig.blockPosition()),
            threat == null && !pig.isOnFire() && !inLove,
            temptingPlayer != null,
            HerbivoreAiSupport.buildStimuli(
                mob,
                threat,
                temptingPlayer,
                temptingPlayer != null && temptingPlayer.isHolding(Items.CARROT_ON_A_STICK) ? 1.0F : 0.8F
            ),
            BiologicalAiProfiles.PIG
        );

        HerbivoreAiSupport.applyDecision(mob, decision, threat, temptingPlayer, 0.8);
    }

    private static void runChicken(Animal animal, ServerLevel level) {
        if (!(animal instanceof Chicken chicken)) return;
        if (!LatticeNative.isLoaded()) return;
        if (chicken.isPassenger() || chicken.isVehicle() || chicken.isChickenJockey()) return;

        float maxHealth = chicken.getMaxHealth();
        if (maxHealth <= 0.0F) return;

        Mob mob = chicken;
        LivingEntity threat = HerbivoreAiSupport.selectThreat(chicken.getLastHurtByMob(), chicken.getTarget());
        if (threat == null) {
            Predicate<LivingEntity> threatPredicate = entity -> entity instanceof Ocelot || entity instanceof Fox;
            threat = HerbivoreAiSupport.cachedThreat(
                mob, animal.nativeAiCachedChickenThreat(), CHICKEN_THREAT_SCAN_RANGE, threatPredicate
            );
            if (HerbivoreAiSupport.shouldRefreshThreatScan(mob, CHICKEN_THREAT_SCAN_INTERVAL)) {
                animal.nativeAiCachedChickenThreat(
                    HerbivoreAiSupport.findNearestThreat(
                        mob, level, CHICKEN_THREAT_SCAN_RANGE, threatPredicate, Ocelot.class, Fox.class
                    )
                );
                threat = animal.nativeAiCachedChickenThreat();
            }
        }
        boolean inLove = chicken.isInLove();
        Player temptingPlayer = !inLove
            ? level.getNearestPlayer(
                chicken.getX(),
                chicken.getY(),
                chicken.getZ(),
                8.0,
                entity -> entity instanceof Player player && chicken.isFood(player.getMainHandItem())
            )
            : null;

        float energyRatio;
        if (inLove) {
            energyRatio = 0.35F;
        } else if (chicken.isBaby()) {
            energyRatio = 0.50F;
        } else if (chicken.eggTime < 1200) {
            energyRatio = 0.35F;
        } else {
            energyRatio = 0.65F;
        }

        NativeBiologicalAi.Decision decision = NativeBiologicalAi.decide(
            NativeBiologicalAi.Species.CHICKEN,
            chicken.getHealth() / maxHealth,
            energyRatio,
            0.0F,
            1.0F,
            chicken.isOnFire(),
            false,
            true,
            HerbivoreAiSupport.threatStrength(threat),
            !level.canSeeSky(chicken.blockPosition()),
            threat == null && !chicken.isOnFire() && !inLove,
            temptingPlayer != null,
            HerbivoreAiSupport.buildStimuli(mob, threat, temptingPlayer, 0.9F),
            BiologicalAiProfiles.CHICKEN
        );

        HerbivoreAiSupport.applyDecision(mob, decision, threat, temptingPlayer, 0.9);
    }

    private static void runPolarBear(Animal animal, ServerLevel level) {
        if (!(animal instanceof PolarBear polarBear)) return;
        if (!LatticeNative.isLoaded()) return;
        if (polarBear.isPassenger() || polarBear.isVehicle()) return;

        float maxHealth = polarBear.getMaxHealth();
        if (maxHealth <= 0.0F) return;

        Mob mob = polarBear;
        boolean busy = polarBear.isStanding() || polarBear.isInLove();
        if (HerbivoreAiSupport.shouldRefreshThreatScan(mob, POLAR_BEAR_BABY_ALERT_SCAN_INTERVAL)) {
            animal.nativeAiCachedBabyAlert(hasNearbyBabyPolarBear(mob, level));
        }
        Predicate<LivingEntity> preyPredicate = entity -> entity instanceof Fox || entity instanceof Player;
        Predicate<LivingEntity> scannedPreyPredicate = entity -> entity instanceof Fox;
        LivingEntity target = polarBear.getTarget();
        if (target != null && target.isAlive() && !preyPredicate.test(target) && !polarBear.isOnFire()) return;
        LivingEntity prey = target != null && target.isAlive() && preyPredicate.test(target) ? target : null;
        if (prey == null && !busy && !polarBear.isBaby() && animal.nativeAiCachedBabyAlert()) {
            Player nearbyPlayer = level.getNearestPlayer(
                polarBear.getX(),
                polarBear.getY(),
                polarBear.getZ(),
                10.0,
                entity -> entity instanceof Player player && !player.isCreative() && !player.isSpectator()
            );
            if (nearbyPlayer != null) {
                prey = nearbyPlayer;
            }
        }
        if (prey == null && !busy && !polarBear.isBaby()) {
            prey = PredatoryAnimalAiSupport.cachedPrey(
                mob, animal.nativeAiCachedPrey(), POLAR_BEAR_PREY_SCAN_RANGE, scannedPreyPredicate
            );
            if (PredatoryAnimalAiSupport.shouldRefreshPreyScan(mob, POLAR_BEAR_PREY_SCAN_INTERVAL)) {
                animal.nativeAiCachedPrey(
                    PredatoryAnimalAiSupport.findNearestPrey(
                        mob, level, POLAR_BEAR_PREY_SCAN_RANGE, scannedPreyPredicate, Fox.class
                    )
                );
                prey = animal.nativeAiCachedPrey();
            }
        }
        LivingEntity threat = prey == null ? HerbivoreAiSupport.selectThreat(polarBear.getLastHurtByMob(), target) : null;
        Player temptingPlayer = !busy
            ? level.getNearestPlayer(
                polarBear.getX(),
                polarBear.getY(),
                polarBear.getZ(),
                10.0,
                entity -> entity instanceof Player player && polarBear.isFood(player.getMainHandItem())
            )
            : null;

        int stimulusCount = 0;
        if (threat != null) stimulusCount++;
        if (prey != null) stimulusCount++;
        if (temptingPlayer != null) stimulusCount++;
        NativeBiologicalAi.Stimulus[] stimuli = new NativeBiologicalAi.Stimulus[stimulusCount];
        int threatIndex = -1;
        int preyIndex = -1;
        int foodIndex = -1;
        int index = 0;
        if (threat != null) {
            threatIndex = index;
            stimuli[index++] = new NativeBiologicalAi.Stimulus(
                NativeBiologicalAi.StimulusKind.THREAT,
                (float)Math.sqrt(mob.distanceToSqr(threat)),
                HerbivoreAiSupport.threatStrength(threat),
                true,
                true
            );
        }
        if (prey != null) {
            preyIndex = index;
            stimuli[index++] = new NativeBiologicalAi.Stimulus(
                NativeBiologicalAi.StimulusKind.PREY,
                (float)Math.sqrt(mob.distanceToSqr(prey)),
                1.0F,
                true,
                true
            );
        }
        if (temptingPlayer != null) {
            foodIndex = index;
            stimuli[index] = new NativeBiologicalAi.Stimulus(
                NativeBiologicalAi.StimulusKind.FOOD,
                (float)Math.sqrt(mob.distanceToSqr(temptingPlayer)),
                0.65F,
                true,
                true
            );
        }

        NativeBiologicalAi.Decision decision = NativeBiologicalAi.decide(
            NativeBiologicalAi.Species.WOLF,
            polarBear.getHealth() / maxHealth,
            busy ? 0.35F : (polarBear.isBaby() ? 0.55F : 0.85F),
            prey != null && !polarBear.isBaby() ? 0.75F : 0.20F,
            2.0F,
            polarBear.isOnFire(),
            prey != null && !polarBear.isBaby(),
            temptingPlayer != null,
            HerbivoreAiSupport.threatStrength(threat),
            !level.canSeeSky(polarBear.blockPosition()),
            threat == null && !polarBear.isOnFire() && prey == null && !busy,
            temptingPlayer != null,
            stimuli,
            BiologicalAiProfiles.WOLF
        );

        PredatoryAnimalAiSupport.applyDecision(
            mob, decision, threat, prey, temptingPlayer, threatIndex, preyIndex, foodIndex, 1.0, 0.8
        );
    }

    private static boolean hasNearbyBabyPolarBear(Mob self, ServerLevel level) {
        AABB area = self.getBoundingBox().inflate(
            POLAR_BEAR_BABY_ALERT_SCAN_RANGE_XZ,
            POLAR_BEAR_BABY_ALERT_SCAN_RANGE_Y,
            POLAR_BEAR_BABY_ALERT_SCAN_RANGE_XZ
        );
        return !level.getEntitiesOfClass(
            PolarBear.class,
            area,
            bear -> bear != self && bear.isAlive() && bear.isBaby()
        ).isEmpty();
    }

    private static void runParrot(Animal animal, ServerLevel level) {
        if (!(animal instanceof Parrot parrot)) return;
        if (!LatticeNative.isLoaded()) return;
        if (parrot.isPassenger() || parrot.isVehicle()) return;

        float maxHealth = parrot.getMaxHealth();
        if (maxHealth <= 0.0F) return;

        Mob mob = parrot;
        LivingEntity threat = HerbivoreAiSupport.selectThreat(parrot.getLastHurtByMob(), parrot.getTarget());
        boolean busy = parrot.isPartyParrot()
            || parrot.isOrderedToSit()
            || parrot.isInSittingPose()
            || parrot.isFlying();

        NativeBiologicalAi.Decision decision = NativeBiologicalAi.decide(
            parrot.getHealth() / maxHealth,
            busy ? 0.20F : 0.75F,
            0.0F,
            1.0F,
            parrot.isOnFire(),
            false,
            false,
            HerbivoreAiSupport.threatStrength(threat),
            !level.canSeeSky(parrot.blockPosition()),
            threat == null && !parrot.isOnFire() && !busy,
            false,
            HerbivoreAiSupport.buildStimuli(mob, threat, null, 0.0F),
            BiologicalAiProfiles.BEE
        );

        if (decision.action() == NativeBiologicalAi.Action.FLEE && threat != null && threat.isAlive()) {
            HerbivoreAiSupport.applyDecision(mob, decision, threat, null, 0.8);
            return;
        }

        if (decision.action() == NativeBiologicalAi.Action.REST) {
            mob.stopInPlace();
        }
    }
}
