package com.latticemc.lattice.mixin;

import com.latticemc.lattice.nativelib.BiologicalAiProfiles;
import com.latticemc.lattice.nativelib.LatticeNative;
import com.latticemc.lattice.nativelib.NativeBiologicalAi;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.fox.Fox;
import net.minecraft.world.entity.animal.polarbear.PolarBear;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Animal.class)
public abstract class PolarBearMixin {
    @Unique private static final int lattice$PREY_SCAN_INTERVAL = 8;
    @Unique private static final double lattice$PREY_SCAN_RANGE = 10.0;

    @Unique private @Nullable LivingEntity lattice$cachedPrey;

    @Inject(method = "customServerAiStep", at = @At("TAIL"))
    private void lattice$runBiologicalAi(ServerLevel level, CallbackInfo ci) {
        if (!((Object) this instanceof PolarBear polarBear)) return;
        if (!LatticeNative.isLoaded()) return;
        if (polarBear.isPassenger() || polarBear.isVehicle()) return;

        final float maxHealth = polarBear.getMaxHealth();
        if (maxHealth <= 0.0F) return;

        final Mob mob = polarBear;
        final boolean busy = polarBear.isStanding() || polarBear.isInLove();
        final Predicate<LivingEntity> preyPredicate = entity -> entity instanceof Fox || entity instanceof Player;
        final Predicate<LivingEntity> scannedPreyPredicate = entity -> entity instanceof Fox;
        final LivingEntity target = polarBear.getTarget();
        if (target != null && target.isAlive() && !preyPredicate.test(target) && !polarBear.isOnFire()) return;
        LivingEntity prey = target != null && target.isAlive() && preyPredicate.test(target) ? target : null;
        if (prey == null && !busy && !polarBear.isBaby()) {
            prey = PredatoryAnimalAiSupport.cachedPrey(mob, this.lattice$cachedPrey, lattice$PREY_SCAN_RANGE, scannedPreyPredicate);
            if (PredatoryAnimalAiSupport.shouldRefreshPreyScan(mob, lattice$PREY_SCAN_INTERVAL)) {
                this.lattice$cachedPrey = PredatoryAnimalAiSupport.findNearestPrey(mob, level, lattice$PREY_SCAN_RANGE, scannedPreyPredicate);
                prey = this.lattice$cachedPrey;
            }
        }
        final LivingEntity threat = prey == null ? HerbivoreAiSupport.selectThreat(polarBear.getLastHurtByMob(), target) : null;
        final Player temptingPlayer = !busy
                ? level.getNearestPlayer(
                        polarBear.getX(), polarBear.getY(), polarBear.getZ(), 10.0,
                        entity -> entity instanceof Player player && polarBear.isFood(player.getMainHandItem()))
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
                    1.0F,
                    true,
                    true);
        }
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
                    0.65F,
                    true,
                    true);
        }

        final NativeBiologicalAi.Decision decision = NativeBiologicalAi.decide(
                NativeBiologicalAi.Species.WOLF,
                polarBear.getHealth() / maxHealth,
                busy ? 0.35F : (polarBear.isBaby() ? 0.55F : 0.85F),
                prey != null && !polarBear.isBaby() ? 0.75F : 0.20F,
                2.0F,
                polarBear.isOnFire(),
                prey != null && !polarBear.isBaby(),
                temptingPlayer != null,
                threat != null ? 1.0F : 0.0F,
                !level.canSeeSky(polarBear.blockPosition()),
                threat == null && !polarBear.isOnFire() && prey == null && !busy,
                temptingPlayer != null,
                stimuli,
                BiologicalAiProfiles.WOLF);

        PredatoryAnimalAiSupport.applyDecision(mob, decision, threat, prey, temptingPlayer, threatIndex, preyIndex, foodIndex, 1.0, 0.8);
    }
}
