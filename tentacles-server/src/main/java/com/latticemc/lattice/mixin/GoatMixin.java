package com.latticemc.lattice.mixin;

import com.latticemc.lattice.nativelib.BiologicalAiProfiles;
import com.latticemc.lattice.nativelib.LatticeNative;
import com.latticemc.lattice.nativelib.NativeBiologicalAi;
import java.util.List;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.goat.Goat;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Goat.class)
public abstract class GoatMixin {
    @Shadow private int lowerHeadTick;

    @Shadow public abstract float getHealth();
    @Shadow public abstract float getMaxHealth();
    @Shadow public abstract boolean isOnFire();
    @Shadow public abstract boolean isPassenger();
    @Shadow public abstract boolean isVehicle();
    @Shadow public abstract boolean isInWaterOrRain();
    @Shadow public abstract boolean isBaby();
    @Shadow public abstract boolean isInLove();
    @Shadow public abstract boolean isFood(ItemStack stack);
    @Shadow public abstract boolean isScreamingGoat();
    @Shadow public abstract boolean hasLeftHorn();
    @Shadow public abstract boolean hasRightHorn();
    @Shadow public abstract @Nullable LivingEntity getLastHurtByMob();
    @Shadow public abstract @Nullable LivingEntity getTarget();
    @Shadow public abstract Brain<Goat> getBrain();
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
        final boolean preparingRam = this.lowerHeadTick > 0;
        final boolean inLove = this.isInLove();
        final Vec3 ramTarget = preparingRam ? this.getBrain().getMemory(MemoryModuleType.RAM_TARGET).orElse(null) : null;
        final LivingEntity prey = ramTarget != null ? lattice$findRamTarget(level, mob, ramTarget) : null;
        final LivingEntity threat = prey == null ? HerbivoreAiSupport.selectThreat(this.getLastHurtByMob(), this.getTarget()) : null;
        final Player temptingPlayer = !preparingRam && !inLove
                ? level.getNearestPlayer(
                        this.getX(), this.getY(), this.getZ(), 10.0,
                        entity -> entity instanceof Player player && this.isFood(player.getMainHandItem()))
                : null;

        final float energyRatio;
        if (preparingRam || this.isScreamingGoat()) {
            energyRatio = 0.90F;
        } else if (inLove) {
            energyRatio = 0.35F;
        } else if (this.isBaby()) {
            energyRatio = 0.55F;
        } else if (!this.hasLeftHorn() || !this.hasRightHorn()) {
            energyRatio = 0.65F;
        } else {
            energyRatio = 0.80F;
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
                    1.0F,
                    true,
                    true);
        }
        if (prey != null) {
            preyIndex = index;
            stimuli[index++] = new NativeBiologicalAi.Stimulus(
                    NativeBiologicalAi.StimulusKind.PREY,
                    (float) Math.sqrt(mob.distanceToSqr(prey)),
                    this.isScreamingGoat() ? 1.0F : 0.85F,
                    true,
                    true);
        }
        if (temptingPlayer != null) {
            foodIndex = index;
            stimuli[index] = new NativeBiologicalAi.Stimulus(
                    NativeBiologicalAi.StimulusKind.FOOD,
                    (float) Math.sqrt(mob.distanceToSqr(temptingPlayer)),
                    this.isScreamingGoat() ? 0.75F : 0.65F,
                    true,
                    true);
        }

        final NativeBiologicalAi.Decision decision = NativeBiologicalAi.decide(
                NativeBiologicalAi.Species.GOAT,
                this.getHealth() / maxHealth,
                energyRatio,
                prey != null ? 0.55F : (this.isScreamingGoat() ? 0.35F : 0.15F),
                2.0F,
                this.isOnFire(),
                !this.isBaby() && this.hasLeftHorn() && this.hasRightHorn() && prey != null,
                temptingPlayer != null,
                threat != null ? 1.0F : 0.0F,
                !level.canSeeSky(this.blockPosition()),
                threat == null && !this.isOnFire() && !preparingRam && !inLove,
                temptingPlayer != null,
                stimuli,
                BiologicalAiProfiles.GOAT);

        if (prey != null) {
            PredatoryAnimalAiSupport.applyDecision(mob, decision, threat, prey, temptingPlayer, threatIndex, preyIndex, foodIndex, 1.0, 0.9);
            return;
        }

        HerbivoreAiSupport.applyDecision(mob, decision, threat, temptingPlayer, 0.9);
    }

    private static @Nullable LivingEntity lattice$findRamTarget(ServerLevel level, Mob mob, Vec3 ramTarget) {
        final List<LivingEntity> candidates = level.getEntitiesOfClass(
                LivingEntity.class,
                mob.getBoundingBox().inflate(8.0),
                entity -> entity != mob && entity.isAlive() && !(entity instanceof Goat) && entity.position().distanceToSqr(ramTarget) <= 16.0);
        LivingEntity best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (LivingEntity candidate : candidates) {
            final double distance = candidate.position().distanceToSqr(ramTarget);
            if (distance < bestDistance) {
                best = candidate;
                bestDistance = distance;
            }
        }
        return best;
    }
}
