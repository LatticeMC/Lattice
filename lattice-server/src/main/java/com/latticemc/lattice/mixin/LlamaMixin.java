package com.latticemc.lattice.mixin;

import com.latticemc.lattice.nativelib.BiologicalAiProfiles;
import com.latticemc.lattice.nativelib.LatticeNative;
import com.latticemc.lattice.nativelib.NativeBiologicalAi;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.equine.Llama;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Llama.class)
public abstract class LlamaMixin {
    @Unique private static final int lattice$PREY_SCAN_INTERVAL = 8;
    @Unique private static final double lattice$PREY_SCAN_RANGE = 16.0;

    @Unique private @Nullable LivingEntity lattice$cachedWolfPrey;

    @Shadow public boolean didSpit;

    @Shadow public abstract float getHealth();
    @Shadow public abstract float getMaxHealth();
    @Shadow public abstract boolean isOnFire();
    @Shadow public abstract boolean isPassenger();
    @Shadow public abstract boolean isVehicle();
    @Shadow public abstract boolean isInWaterOrRain();
    @Shadow public abstract boolean isBaby();
    @Shadow public abstract boolean isInLove();
    @Shadow public abstract boolean isFood(ItemStack stack);
    @Shadow public abstract int getStrength();
    @Shadow public abstract boolean inCaravan();
    @Shadow public abstract @Nullable Llama getCaravanHead();
    @Shadow public abstract @Nullable LivingEntity getTarget();
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
        final LivingEntity target = this.getTarget();
        LivingEntity prey = target instanceof Wolf wolf && wolf.isAlive() ? wolf : null;
        final boolean inLove = this.isInLove();
        if (prey == null) {
            prey = PredatoryAnimalAiSupport.cachedPrey(mob, this.lattice$cachedWolfPrey, lattice$PREY_SCAN_RANGE, entity -> entity instanceof Wolf);
            if (PredatoryAnimalAiSupport.shouldRefreshPreyScan(mob, lattice$PREY_SCAN_INTERVAL)) {
                this.lattice$cachedWolfPrey = PredatoryAnimalAiSupport.findNearestPrey(
                        mob, level, lattice$PREY_SCAN_RANGE, entity -> entity instanceof Wolf, Wolf.class);
                prey = this.lattice$cachedWolfPrey;
            }
        }
        final LivingEntity threat = target != null && target.isAlive() && prey == null ? target : null;
        final boolean inCaravan = this.inCaravan();
        final Player temptingPlayer = !this.didSpit && !inCaravan && !inLove
                ? level.getNearestPlayer(
                        this.getX(), this.getY(), this.getZ(), 10.0,
                        entity -> entity instanceof Player player && this.isFood(player.getMainHandItem()))
                : null;

        final float energyRatio;
        if (this.didSpit) {
            energyRatio = 0.90F;
        } else if (inCaravan) {
            energyRatio = 0.80F;
        } else if (inLove) {
            energyRatio = 0.35F;
        } else if (this.isBaby()) {
            energyRatio = 0.55F;
        } else {
            energyRatio = 0.65F + this.getStrength() * 0.05F;
        }

        int stimulusCount = 0;
        if (threat != null) stimulusCount++;
        if (prey != null) stimulusCount++;
        if (temptingPlayer != null) stimulusCount++;
        final NativeBiologicalAi.Stimulus[] stimuli = new NativeBiologicalAi.Stimulus[stimulusCount];
        int threatIndex = -1;
        int preyIndex = -1;
        int foodIndex = -1;
        int index = 0;
        if (threat != null) {
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
            stimuli[index++] = new NativeBiologicalAi.Stimulus(
                    NativeBiologicalAi.StimulusKind.PREY,
                    (float) Math.sqrt(mob.distanceToSqr(prey)),
                    0.85F,
                    true,
                    true);
        }
        if (temptingPlayer != null) {
            foodIndex = index;
            stimuli[index] = new NativeBiologicalAi.Stimulus(
                    NativeBiologicalAi.StimulusKind.FOOD,
                    (float) Math.sqrt(mob.distanceToSqr(temptingPlayer)),
                    0.75F,
                    true,
                    true);
        }

        final NativeBiologicalAi.Decision decision = NativeBiologicalAi.decide(
                NativeBiologicalAi.Species.LLAMA,
                this.getHealth() / maxHealth,
                energyRatio,
                prey != null ? 0.55F : (this.didSpit ? 0.45F : 0.10F),
                1.4F,
                this.isOnFire(),
                prey != null,
                temptingPlayer != null,
                HerbivoreAiSupport.threatStrength(threat),
                !level.canSeeSky(this.blockPosition()) || this.getCaravanHead() != null,
                threat == null && !this.isOnFire() && !this.didSpit && !inLove,
                temptingPlayer != null,
                stimuli,
                BiologicalAiProfiles.LLAMA);

        if (decision.action() == NativeBiologicalAi.Action.PURSUE && decision.stimulusIndex() == preyIndex && prey != null && prey.isAlive()) {
            mob.lookAt(prey, 20.0F, 20.0F);
            if (mob.distanceToSqr(prey) > 36.0) {
                mob.getNavigation().moveTo(prey, 0.9);
            } else {
                mob.getNavigation().stop();
            }
            return;
        }

        PredatoryAnimalAiSupport.applyDecision(mob, decision, threat, prey, temptingPlayer, threatIndex, preyIndex, foodIndex, 0.9, 0.8);
    }
}
