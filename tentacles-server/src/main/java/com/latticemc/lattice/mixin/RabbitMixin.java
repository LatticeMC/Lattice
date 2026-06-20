package com.latticemc.lattice.mixin;

import com.latticemc.lattice.nativelib.BiologicalAiProfiles;
import com.latticemc.lattice.nativelib.LatticeNative;
import com.latticemc.lattice.nativelib.NativeBiologicalAi;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.entity.animal.rabbit.Rabbit;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Rabbit.class)
public abstract class RabbitMixin {
    @Unique private static final int lattice$THREAT_SCAN_INTERVAL = 8;
    @Unique private static final double lattice$THREAT_SCAN_RANGE = 10.0;
    @Unique private static final int lattice$PREY_SCAN_INTERVAL = 8;
    @Unique private static final double lattice$PREY_SCAN_RANGE = 10.0;

    @Unique private @Nullable LivingEntity lattice$cachedThreat;
    @Unique private @Nullable LivingEntity lattice$cachedPrey;

    @Shadow public int moreCarrotTicks;

    @Shadow public abstract float getHealth();
    @Shadow public abstract float getMaxHealth();
    @Shadow public abstract boolean isOnFire();
    @Shadow public abstract boolean isPassenger();
    @Shadow public abstract boolean isVehicle();
    @Shadow public abstract boolean isInWaterOrRain();
    @Shadow public abstract boolean isBaby();
    @Shadow public abstract boolean isInLove();
    @Shadow public abstract boolean isFood(ItemStack stack);
    @Shadow public abstract @Nullable LivingEntity getLastHurtByMob();
    @Shadow public abstract @Nullable LivingEntity getTarget();
    @Shadow public abstract Rabbit.Variant getVariant();
    @Shadow public abstract BlockPos blockPosition();
    @Shadow public abstract double getX();
    @Shadow public abstract double getY();
    @Shadow public abstract double getZ();

    @Inject(method = "customServerAiStep", at = @At("TAIL"))
    private void lattice$runBiologicalAi(ServerLevel level, CallbackInfo ci) {
        if (!LatticeNative.isLoaded()) return;
        if (this.isPassenger() || this.isVehicle()) return;

        final float maxHealth = this.getMaxHealth();
        if (maxHealth <= 0.0F) return;

        final Mob mob = (Mob) (Object) this;
        final boolean killerRabbit = this.getVariant() == Rabbit.Variant.EVIL;
        final boolean inLove = !killerRabbit && this.isInLove();
        LivingEntity prey = killerRabbit && this.getTarget() != null && this.getTarget().isAlive() ? this.getTarget() : null;
        if (killerRabbit && prey == null) {
            final Predicate<LivingEntity> preyPredicate = entity -> entity instanceof Wolf || entity instanceof Player;
            prey = PredatoryAnimalAiSupport.cachedPrey(mob, this.lattice$cachedPrey, lattice$PREY_SCAN_RANGE, preyPredicate);
            if (PredatoryAnimalAiSupport.shouldRefreshPreyScan(mob, lattice$PREY_SCAN_INTERVAL)) {
                this.lattice$cachedPrey = PredatoryAnimalAiSupport.findNearestPrey(mob, level, lattice$PREY_SCAN_RANGE, preyPredicate);
                prey = this.lattice$cachedPrey;
            }
        }
        LivingEntity threat = HerbivoreAiSupport.selectThreat(this.getLastHurtByMob(), this.getTarget());
        if (!killerRabbit && threat == null) {
            final Predicate<LivingEntity> threatPredicate = entity -> entity instanceof Wolf
                    || entity instanceof Monster
                    || entity instanceof Player;
            threat = HerbivoreAiSupport.cachedThreat(mob, this.lattice$cachedThreat, lattice$THREAT_SCAN_RANGE, threatPredicate);
            if (HerbivoreAiSupport.shouldRefreshThreatScan(mob, lattice$THREAT_SCAN_INTERVAL)) {
                this.lattice$cachedThreat = HerbivoreAiSupport.findNearestThreat(mob, level, lattice$THREAT_SCAN_RANGE, threatPredicate);
                threat = this.lattice$cachedThreat;
            }
        }
        final Player temptingPlayer = !killerRabbit && !inLove
                ? level.getNearestPlayer(
                        this.getX(), this.getY(), this.getZ(), 9.0,
                        entity -> entity instanceof Player player && this.isFood(player.getMainHandItem()))
                : null;

        final float energyRatio;
        if (killerRabbit) {
            energyRatio = 0.85F;
        } else if (inLove) {
            energyRatio = 0.35F;
        } else if (this.isBaby()) {
            energyRatio = 0.45F;
        } else if (this.moreCarrotTicks > 0) {
            energyRatio = 0.80F;
        } else {
            energyRatio = 0.40F;
        }

        if (killerRabbit) {
            int stimulusCount = 0;
            if (threat != null && threat.isAlive()) stimulusCount++;
            if (prey != null) stimulusCount++;
            final NativeBiologicalAi.Stimulus[] stimuli = new NativeBiologicalAi.Stimulus[stimulusCount];
            int threatIndex = -1;
            int preyIndex = -1;
            int index = 0;
            if (threat != null && threat.isAlive()) {
                threatIndex = index;
                stimuli[index++] = new NativeBiologicalAi.Stimulus(
                        NativeBiologicalAi.StimulusKind.THREAT,
                        (float) Math.sqrt(mob.distanceToSqr(threat)),
                        HerbivoreAiSupport.threatStrength(threat),
                        true,
                        true);
            }
            if (prey != null) {
                preyIndex = index;
                stimuli[index] = new NativeBiologicalAi.Stimulus(
                        NativeBiologicalAi.StimulusKind.PREY,
                        (float) Math.sqrt(mob.distanceToSqr(prey)),
                        1.0F,
                        true,
                        true);
            }

            final NativeBiologicalAi.Decision decision = NativeBiologicalAi.decide(
                    this.getHealth() / maxHealth,
                    energyRatio,
                    0.95F,
                    1.4F,
                    this.isOnFire(),
                    prey != null,
                    false,
                    HerbivoreAiSupport.threatStrength(threat),
                    !level.canSeeSky(this.blockPosition()),
                    threat == null && !this.isOnFire(),
                    false,
                    stimuli,
                    BiologicalAiProfiles.KILLER_RABBIT);

            PredatoryAnimalAiSupport.applyDecision(mob, decision, threat, prey, null, threatIndex, preyIndex, -1, 1.1, 0.0);
            return;
        }

        final NativeBiologicalAi.Decision decision = NativeBiologicalAi.decide(
                NativeBiologicalAi.Species.RABBIT,
                this.getHealth() / maxHealth,
                energyRatio,
                0.0F,
                1.0F,
                this.isOnFire(),
                false,
                true,
                HerbivoreAiSupport.threatStrength(threat),
                !level.canSeeSky(this.blockPosition()),
                threat == null && !this.isOnFire() && !inLove,
                temptingPlayer != null,
                HerbivoreAiSupport.buildStimuli(mob, threat, temptingPlayer, 1.0F),
                BiologicalAiProfiles.RABBIT);

        HerbivoreAiSupport.applyDecision(mob, decision, threat, temptingPlayer, 1.0);
    }
}
