package com.latticemc.lattice.mixin;

import com.latticemc.lattice.nativelib.BiologicalAiProfiles;
import com.latticemc.lattice.nativelib.LatticeNative;
import com.latticemc.lattice.nativelib.NativeBiologicalAi;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.feline.Cat;
import net.minecraft.world.entity.animal.rabbit.Rabbit;
import net.minecraft.world.entity.animal.turtle.Turtle;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Cat.class)
public abstract class CatMixin {
    @Unique private static final int lattice$PREY_SCAN_INTERVAL = 8;
    @Unique private static final double lattice$PREY_SCAN_RANGE = 10.0;
    @Unique private static final int lattice$THREAT_SCAN_INTERVAL = 8;
    @Unique private static final double lattice$THREAT_SCAN_RANGE = 16.0;

    @Unique private @Nullable LivingEntity lattice$cachedPrey;
    @Unique private @Nullable LivingEntity lattice$cachedThreat;

    @Shadow public abstract float getHealth();
    @Shadow public abstract float getMaxHealth();
    @Shadow public abstract boolean isOnFire();
    @Shadow public abstract boolean isPassenger();
    @Shadow public abstract boolean isVehicle();
    @Shadow public abstract boolean isTame();
    @Shadow public abstract boolean isLying();
    @Shadow public abstract boolean isRelaxStateOne();
    @Shadow public abstract boolean isFood(ItemStack stack);
    @Shadow public abstract @Nullable LivingEntity getTarget();
    @Shadow public abstract BlockPos blockPosition();
    @Shadow public abstract double getX();
    @Shadow public abstract double getY();
    @Shadow public abstract double getZ();

    @Inject(method = "customServerAiStep", at = @At("TAIL"))
    private void lattice$runBiologicalAi(ServerLevel level, CallbackInfo ci) {
        if (!LatticeNative.isLoaded()) return;
        if (this.isPassenger() || this.isVehicle() || this.isTame()) return;

        final float maxHealth = this.getMaxHealth();
        if (maxHealth <= 0.0F) return;

        final Mob mob = (Mob) (Object) this;
        final boolean busy = this.isLying() || this.isRelaxStateOne();
        final Predicate<LivingEntity> preyPredicate = entity -> entity instanceof Rabbit
                || entity instanceof Turtle turtle && turtle.isBaby() && !turtle.isInWater();
        final Predicate<LivingEntity> threatPredicate = entity -> entity instanceof Player player
                && !player.isCreative()
                && !player.isSpectator();
        final LivingEntity target = this.getTarget();
        if (target != null && target.isAlive() && !preyPredicate.test(target) && !threatPredicate.test(target) && !this.isOnFire()) return;
        LivingEntity prey = target != null && target.isAlive() && preyPredicate.test(target) ? target : null;
        if (prey == null && !busy) {
            prey = PredatoryAnimalAiSupport.cachedPrey(mob, this.lattice$cachedPrey, lattice$PREY_SCAN_RANGE, preyPredicate);
            if (PredatoryAnimalAiSupport.shouldRefreshPreyScan(mob, lattice$PREY_SCAN_INTERVAL)) {
                this.lattice$cachedPrey = PredatoryAnimalAiSupport.findNearestPrey(
                        mob, level, lattice$PREY_SCAN_RANGE, preyPredicate, Rabbit.class, Turtle.class);
                prey = this.lattice$cachedPrey;
            }
        }
        LivingEntity threat = target != null && target.isAlive() && prey == null && threatPredicate.test(target) ? target : null;
        if (threat == null && !busy) {
            threat = HerbivoreAiSupport.cachedThreat(mob, this.lattice$cachedThreat, lattice$THREAT_SCAN_RANGE, threatPredicate);
            if (HerbivoreAiSupport.shouldRefreshThreatScan(mob, lattice$THREAT_SCAN_INTERVAL)) {
                this.lattice$cachedThreat = HerbivoreAiSupport.findNearestThreat(
                        mob, level, lattice$THREAT_SCAN_RANGE, threatPredicate, Player.class);
                threat = this.lattice$cachedThreat;
            }
        }
        final Player temptingPlayer = !busy
                ? level.getNearestPlayer(
                        this.getX(), this.getY(), this.getZ(), 10.0,
                        entity -> entity instanceof Player player && this.isFood(player.getMainHandItem()))
                : null;

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
                    0.8F,
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
                NativeBiologicalAi.Species.CAT,
                this.getHealth() / maxHealth,
                busy ? 0.20F : 0.65F,
                prey != null ? 0.55F : 0.15F,
                1.2F,
                this.isOnFire(),
                prey != null,
                temptingPlayer != null,
                HerbivoreAiSupport.threatStrength(threat),
                !level.canSeeSky(this.blockPosition()),
                threat == null && !this.isOnFire() && prey == null && !busy,
                temptingPlayer != null,
                stimuli,
                BiologicalAiProfiles.CAT);

        PredatoryAnimalAiSupport.applyDecision(mob, decision, threat, prey, temptingPlayer, threatIndex, preyIndex, foodIndex, 1.0, 0.8);
    }
}
