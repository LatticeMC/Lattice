package com.latticemc.lattice.mixin;

import com.latticemc.lattice.nativelib.BiologicalAiProfiles;
import com.latticemc.lattice.nativelib.LatticeNative;
import com.latticemc.lattice.nativelib.NativeBiologicalAi;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.chicken.Chicken;
import net.minecraft.world.entity.animal.fox.Fox;
import net.minecraft.world.entity.animal.rabbit.Rabbit;
import net.minecraft.world.entity.animal.turtle.Turtle;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Fox.class)
public abstract class FoxMixin {
    @Unique private static final int lattice$PREY_SCAN_INTERVAL = 8;
    @Unique private static final double lattice$PREY_SCAN_RANGE = 10.0;

    @Unique private @Nullable LivingEntity lattice$cachedPrey;

    @Shadow public abstract float getHealth();
    @Shadow public abstract float getMaxHealth();
    @Shadow public abstract boolean isOnFire();
    @Shadow public abstract boolean isPassenger();
    @Shadow public abstract boolean isVehicle();
    @Shadow public abstract boolean isBaby();
    @Shadow public abstract boolean isInLove();
    @Shadow public abstract boolean isSleeping();
    @Shadow public abstract boolean isSitting();
    @Shadow public abstract boolean isCrouching();
    @Shadow public abstract boolean isPouncing();
    @Shadow public abstract boolean isFaceplanted();
    @Shadow public abstract boolean isDefending();
    @Shadow public abstract @Nullable LivingEntity getLastHurtByMob();
    @Shadow public abstract @Nullable LivingEntity getTarget();
    @Shadow public abstract BlockPos blockPosition();

    @Inject(method = "aiStep", at = @At("TAIL"))
    private void lattice$runBiologicalAi(CallbackInfo ci) {
        if (!LatticeNative.isLoaded()) return;
        final Mob mob = (Mob) (Object) this;
        if (!(mob.level() instanceof ServerLevel level)) return;
        if (this.isPassenger() || this.isVehicle()) return;

        final float maxHealth = this.getMaxHealth();
        if (maxHealth <= 0.0F) return;

        final boolean busy = this.isSleeping()
                || this.isSitting()
                || this.isCrouching()
                || this.isPouncing()
                || this.isFaceplanted()
                || this.isDefending()
                || this.isInLove();

        final Predicate<LivingEntity> preyPredicate = entity -> entity instanceof Chicken
                || entity instanceof Rabbit
                || entity instanceof Turtle turtle && turtle.isBaby() && !turtle.isInWater();
        final LivingEntity target = this.getTarget();
        if (target != null && target.isAlive() && !preyPredicate.test(target) && !this.isOnFire()) return;
        LivingEntity prey = target != null && target.isAlive() && preyPredicate.test(target) ? target : null;
        if (prey == null && !busy) {
            prey = PredatoryAnimalAiSupport.cachedPrey(mob, this.lattice$cachedPrey, lattice$PREY_SCAN_RANGE, preyPredicate);
            if (PredatoryAnimalAiSupport.shouldRefreshPreyScan(mob, lattice$PREY_SCAN_INTERVAL)) {
                this.lattice$cachedPrey = PredatoryAnimalAiSupport.findNearestPrey(mob, level, lattice$PREY_SCAN_RANGE, preyPredicate);
                prey = this.lattice$cachedPrey;
            }
        }
        final LivingEntity threat = prey == null ? HerbivoreAiSupport.selectThreat(this.getLastHurtByMob(), target) : null;

        int stimulusCount = 0;
        if (threat != null) stimulusCount++;
        if (prey != null) stimulusCount++;
        final NativeBiologicalAi.Stimulus[] stimuli = new NativeBiologicalAi.Stimulus[stimulusCount];
        int threatIndex = -1;
        int preyIndex = -1;
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
            stimuli[index] = new NativeBiologicalAi.Stimulus(
                    NativeBiologicalAi.StimulusKind.PREY,
                    (float) Math.sqrt(mob.distanceToSqr(prey)),
                    0.85F,
                    true,
                    true);
        }

        final NativeBiologicalAi.Decision decision = NativeBiologicalAi.decide(
                NativeBiologicalAi.Species.FOX,
                this.getHealth() / maxHealth,
                this.isBaby() ? 0.55F : 0.75F,
                prey != null ? 0.55F : 0.20F,
                1.2F,
                this.isOnFire(),
                prey != null && !this.isBaby(),
                false,
                threat != null ? 1.0F : 0.0F,
                !level.canSeeSky(this.blockPosition()),
                threat == null && !this.isOnFire() && prey == null && !busy,
                false,
                stimuli,
                BiologicalAiProfiles.FOX);

        PredatoryAnimalAiSupport.applyDecision(mob, decision, threat, prey, null, threatIndex, preyIndex, -1, 1.0, 0.0);
    }
}
