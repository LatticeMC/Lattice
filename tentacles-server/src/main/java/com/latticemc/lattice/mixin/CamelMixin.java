package com.latticemc.lattice.mixin;

import com.latticemc.lattice.nativelib.BiologicalAiProfiles;
import com.latticemc.lattice.nativelib.LatticeNative;
import com.latticemc.lattice.nativelib.NativeBiologicalAi;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.camel.Camel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Camel.class)
public abstract class CamelMixin {
    @Shadow public abstract float getHealth();
    @Shadow public abstract float getMaxHealth();
    @Shadow public abstract boolean isOnFire();
    @Shadow public abstract boolean isPassenger();
    @Shadow public abstract boolean isVehicle();
    @Shadow public abstract boolean isInWaterOrRain();
    @Shadow public abstract boolean isBaby();
    @Shadow public abstract boolean isFood(ItemStack stack);
    @Shadow public abstract boolean isCamelSitting();
    @Shadow public abstract boolean isInPoseTransition();
    @Shadow public abstract boolean isDashing();
    @Shadow public abstract boolean refuseToMove();
    @Shadow public abstract @Nullable LivingEntity getLastHurtByMob();
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
        final LivingEntity threat = HerbivoreAiSupport.selectThreat(this.getLastHurtByMob(), this.getTarget());
        final boolean sitting = this.isCamelSitting();
        final boolean poseTransition = this.isInPoseTransition();
        final boolean dashing = this.isDashing();
        final boolean busy = dashing || sitting || poseTransition || this.refuseToMove();
        final Player temptingPlayer = !busy
                ? level.getNearestPlayer(
                        this.getX(), this.getY(), this.getZ(), 12.0,
                        entity -> entity instanceof Player player && this.isFood(player.getMainHandItem()))
                : null;

        final float energyRatio;
        if (dashing) {
            energyRatio = 0.95F;
        } else if (sitting) {
            energyRatio = 0.15F;
        } else if (poseTransition) {
            energyRatio = 0.25F;
        } else if (this.isBaby()) {
            energyRatio = 0.55F;
        } else {
            energyRatio = 0.75F;
        }

        final NativeBiologicalAi.Decision decision = NativeBiologicalAi.decide(
                NativeBiologicalAi.Species.CAMEL,
                this.getHealth() / maxHealth,
                energyRatio,
                0.0F,
                1.5F,
                this.isOnFire(),
                false,
                temptingPlayer != null,
                threat != null ? 1.0F : 0.0F,
                sitting || !level.canSeeSky(this.blockPosition()),
                threat == null && !this.isOnFire() && !dashing,
                temptingPlayer != null,
                HerbivoreAiSupport.buildStimuli(mob, threat, temptingPlayer, 0.7F),
                BiologicalAiProfiles.CAMEL);

        HerbivoreAiSupport.applyDecision(mob, decision, threat, temptingPlayer, 0.8);
    }
}
