package com.latticemc.lattice.mixin;

import com.latticemc.lattice.nativelib.BiologicalAiProfiles;
import com.latticemc.lattice.nativelib.LatticeNative;
import com.latticemc.lattice.nativelib.NativeBiologicalAi;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.feline.Ocelot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Ocelot.class)
public abstract class OcelotMixin {
    @Shadow abstract boolean isTrusting();

    @Shadow public abstract float getHealth();
    @Shadow public abstract float getMaxHealth();
    @Shadow public abstract boolean isOnFire();
    @Shadow public abstract boolean isPassenger();
    @Shadow public abstract boolean isVehicle();
    @Shadow public abstract boolean isInWaterOrRain();
    @Shadow public abstract boolean isBaby();
    @Shadow public abstract boolean isFood(ItemStack stack);
    @Shadow public abstract boolean isSteppingCarefully();
    @Shadow public abstract @Nullable LivingEntity getLastHurtByMob();
    @Shadow public abstract @Nullable LivingEntity getTarget();
    @Shadow public abstract BlockPos blockPosition();
    @Shadow public abstract double getX();
    @Shadow public abstract double getY();
    @Shadow public abstract double getZ();

    @Inject(method = "customServerAiStep", at = @At("TAIL"))
    private void lattice$runBiologicalAi(ServerLevel level, CallbackInfo ci) {
        if (!LatticeNative.isLoaded()) return;
        if (this.isPassenger() || this.isVehicle() || this.isInWaterOrRain()) return;

        final float maxHealth = this.getMaxHealth();
        if (maxHealth <= 0.0F) return;

        final Mob mob = (Mob) (Object) this;
        final LivingEntity threat = this.getLastHurtByMob();
        final LivingEntity prey = this.getTarget() != null && this.getTarget().isAlive() ? this.getTarget() : null;
        final Player temptingPlayer = level.getNearestPlayer(
                this.getX(), this.getY(), this.getZ(), 10.0,
                entity -> entity instanceof Player player && this.isFood(player.getMainHandItem()));

        int stimulusCount = 0;
        if (threat != null && threat.isAlive()) stimulusCount++;
        if (prey != null) stimulusCount++;
        if (temptingPlayer != null) stimulusCount++;
        final NativeBiologicalAi.Stimulus[] stimuli = new NativeBiologicalAi.Stimulus[stimulusCount];
        int threatIndex = -1;
        int preyIndex = -1;
        int foodIndex = -1;
        int index = 0;
        if (threat != null && threat.isAlive()) {
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
                    this.isSteppingCarefully() ? 1.0F : 0.75F,
                    true,
                    true);
        }
        if (temptingPlayer != null) {
            foodIndex = index;
            stimuli[index] = new NativeBiologicalAi.Stimulus(
                    NativeBiologicalAi.StimulusKind.FOOD,
                    (float) Math.sqrt(mob.distanceToSqr(temptingPlayer)),
                    this.isTrusting() ? 0.85F : 0.70F,
                    true,
                    true);
        }

        final NativeBiologicalAi.Decision decision = NativeBiologicalAi.decide(
                NativeBiologicalAi.Species.GENERIC,
                this.getHealth() / maxHealth,
                this.isBaby() ? 0.60F : (this.isSteppingCarefully() ? 0.90F : 0.72F),
                this.isTrusting() ? 0.55F : 0.35F,
                1.33F,
                this.isOnFire(),
                prey != null,
                true,
                threat != null ? 1.0F : 0.0F,
                !level.canSeeSky(this.blockPosition()),
                threat == null && !this.isOnFire() && prey == null,
                temptingPlayer != null,
                stimuli,
                BiologicalAiProfiles.OCELOT);

        PredatoryAnimalAiSupport.applyDecision(mob, decision, threat, prey, temptingPlayer, threatIndex, preyIndex, foodIndex, 1.0, 0.8);
    }
}
