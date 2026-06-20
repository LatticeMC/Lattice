package com.latticemc.lattice.mixin;

import com.latticemc.lattice.nativelib.BiologicalAiProfiles;
import com.latticemc.lattice.nativelib.NativeHomeTargetSampler;
import com.latticemc.lattice.nativelib.LatticeNative;
import com.latticemc.lattice.nativelib.NativeBiologicalAi;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.bee.Bee;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Bee.class)
public abstract class BeeMixin {
    @Unique private static final int lattice$ANGER_SCAN_INTERVAL = 8;
    @Unique private static final double lattice$ANGER_SCAN_RANGE = 10.0;

    @Unique private @Nullable LivingEntity lattice$cachedAngerTarget;

    @Shadow public abstract float getHealth();
    @Shadow public abstract float getMaxHealth();
    @Shadow public abstract boolean isOnFire();
    @Shadow public abstract boolean isPassenger();
    @Shadow public abstract boolean isVehicle();
    @Shadow public abstract boolean isFood(ItemStack stack);
    @Shadow public abstract boolean isAngry();
    @Shadow public abstract boolean hasHive();
    @Shadow public abstract boolean hasSavedFlowerPos();
    @Shadow public abstract boolean hasNectar();
    @Shadow public abstract boolean hasStung();
    @Shadow public abstract @Nullable BlockPos getHivePos();
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
        final boolean angry = this.isAngry();
        final boolean hasNectar = this.hasNectar();
        final boolean hasStung = this.hasStung();
        final boolean hasHive = this.hasHive() && this.getHivePos() != null;
        LivingEntity prey = angry && !hasStung && this.getTarget() != null && this.getTarget().isAlive()
                ? this.getTarget()
                : null;
        if (prey == null && angry && !hasStung) {
            final Predicate<LivingEntity> angerPredicate = entity -> entity instanceof Player player
                    && ((NeutralMob) (Object) this).isAngryAt(player, level);
            prey = PredatoryAnimalAiSupport.cachedPrey(mob, this.lattice$cachedAngerTarget, lattice$ANGER_SCAN_RANGE, angerPredicate);
            if (PredatoryAnimalAiSupport.shouldRefreshPreyScan(mob, lattice$ANGER_SCAN_INTERVAL)) {
                this.lattice$cachedAngerTarget = PredatoryAnimalAiSupport.findNearestPrey(mob, level, lattice$ANGER_SCAN_RANGE, angerPredicate);
                prey = this.lattice$cachedAngerTarget;
            }
        }
        final Player temptingPlayer = !hasNectar && !hasStung
                ? level.getNearestPlayer(
                        this.getX(), this.getY(), this.getZ(), 8.0,
                        entity -> entity instanceof Player player && this.isFood(player.getMainHandItem()))
                : null;
        final float energyRatio = beeEnergyRatio(angry, hasNectar, hasStung, this.hasSavedFlowerPos());
        final float aggression = angry && !hasStung ? (prey != null ? 0.95F : 0.70F) : 0.05F;

        int stimulusCount = 0;
        if (prey != null) stimulusCount++;
        if (temptingPlayer != null) stimulusCount++;
        final NativeBiologicalAi.Stimulus[] stimuli = new NativeBiologicalAi.Stimulus[stimulusCount];
        int preyIndex = -1;
        int foodIndex = -1;
        int index = 0;
        if (prey != null) {
            preyIndex = index;
            stimuli[index++] = new NativeBiologicalAi.Stimulus(
                    NativeBiologicalAi.StimulusKind.PREY,
                    (float) Math.sqrt(mob.distanceToSqr(prey)),
                    1.0F,
                    true,
                    true);
        }
        if (temptingPlayer != null) {
            foodIndex = index;
            stimuli[index] = new NativeBiologicalAi.Stimulus(
                    NativeBiologicalAi.StimulusKind.FOOD,
                    (float) Math.sqrt(mob.distanceToSqr(temptingPlayer)),
                    0.8F,
                    true,
                    true);
        }

        final NativeBiologicalAi.Decision decision = NativeBiologicalAi.decide(
                NativeBiologicalAi.Species.BEE,
                this.getHealth() / maxHealth,
                energyRatio,
                aggression,
                1.4F,
                this.isOnFire(),
                prey != null,
                temptingPlayer != null,
                this.isOnFire() ? 1.0F : (angry ? 0.75F : (hasNectar && !hasHive ? 0.35F : (hasStung ? 0.40F : 0.0F))),
                hasHive,
                !angry && !hasStung && !this.isOnFire() && prey == null && !hasNectar,
                temptingPlayer != null,
                stimuli,
                BiologicalAiProfiles.BEE);

        if (hasHive && hasNectar && prey == null) {
            BlockPos hivePos = this.getHivePos();
            double[] candidates = buildHomeCandidates(hivePos, 2.0);
            int candidate = NativeHomeTargetSampler.sampleHomeTarget(
                    candidates,
                    candidates.length / 3,
                    this.getX(), this.getY(), this.getZ(),
                    hivePos.getX(), hivePos.getY(), hivePos.getZ(),
                    null, 0,
                    2.0,
                    0.5);
            if (candidate >= 0) {
                mob.getNavigation().moveTo(
                        candidates[candidate * 3],
                        candidates[candidate * 3 + 1],
                        candidates[candidate * 3 + 2],
                        1.0);
                return;
            }
        }

        PollinatorAiSupport.applyDecision(mob, decision, prey, temptingPlayer, preyIndex, foodIndex, 1.2, 1.0);
    }

    private static float beeEnergyRatio(boolean angry, boolean hasNectar, boolean hasStung, boolean hasSavedFlowerPos) {
        if (hasStung) return 0.05F;
        if (hasNectar) return 0.95F;
        if (angry) return 0.80F;
        return hasSavedFlowerPos ? 0.70F : 0.45F;
    }

    private double[] buildHomeCandidates(BlockPos home, double preferredDistance) {
        double hx = home.getX() + 0.5;
        double hy = home.getY();
        double hz = home.getZ() + 0.5;
        return new double[] {
                hx + preferredDistance, hy, hz,
                hx - preferredDistance, hy, hz,
                hx, hy, hz + preferredDistance,
                hx, hy, hz - preferredDistance,
                hx, hy, hz,
        };
    }
}
