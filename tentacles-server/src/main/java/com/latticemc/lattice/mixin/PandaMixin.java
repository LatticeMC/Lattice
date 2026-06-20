package com.latticemc.lattice.mixin;

import com.latticemc.lattice.nativelib.BiologicalAiProfiles;
import com.latticemc.lattice.nativelib.LatticeNative;
import com.latticemc.lattice.nativelib.NativeBiologicalAi;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.panda.Panda;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Panda.class)
public abstract class PandaMixin {
    @Shadow public abstract float getHealth();
    @Shadow public abstract float getMaxHealth();
    @Shadow public abstract boolean isOnFire();
    @Shadow public abstract boolean isPassenger();
    @Shadow public abstract boolean isVehicle();
    @Shadow public abstract boolean isInWater();
    @Shadow public abstract boolean isBaby();
    @Shadow public abstract boolean isFood(ItemStack stack);
    @Shadow public abstract boolean isSitting();
    @Shadow public abstract boolean isOnBack();
    @Shadow public abstract boolean isEating();
    @Shadow public abstract boolean isRolling();
    @Shadow public abstract boolean isSneezing();
    @Shadow public abstract boolean isScared();
    @Shadow public abstract boolean isWorried();
    @Shadow public abstract boolean isAggressive();
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
        final LivingEntity threat = target != null && target.isAlive() ? target : null;
        final boolean sitting = this.isSitting();
        final boolean eating = this.isEating();
        final boolean rolling = this.isRolling();
        final boolean sneezing = this.isSneezing();
        final boolean scared = this.isScared();
        final boolean busy = sitting || eating || rolling || sneezing || this.isOnBack() || scared;
        final Player temptingPlayer = !busy && !this.isInWater()
                ? level.getNearestPlayer(
                        this.getX(), this.getY(), this.getZ(), 10.0,
                        entity -> entity instanceof Player player && this.isFood(player.getMainHandItem()))
                : null;

        final float energyRatio;
        if (scared) {
            energyRatio = 0.15F;
        } else if (eating || sitting) {
            energyRatio = 0.25F;
        } else if (rolling || sneezing) {
            energyRatio = 0.90F;
        } else if (this.isBaby()) {
            energyRatio = 0.55F;
        } else {
            energyRatio = 0.70F;
        }

        final NativeBiologicalAi.Decision decision = NativeBiologicalAi.decide(
                NativeBiologicalAi.Species.PANDA,
                this.getHealth() / maxHealth,
                energyRatio,
                this.isAggressive() ? 0.45F : 0.05F,
                1.2F,
                this.isOnFire(),
                false,
                temptingPlayer != null,
                this.isOnFire() ? 1.0F : (threat != null ? 0.8F : (scared ? 0.75F : 0.0F)),
                sitting || !level.canSeeSky(this.blockPosition()),
                threat == null && !this.isOnFire() && !busy,
                temptingPlayer != null,
                HerbivoreAiSupport.buildStimuli(mob, threat, temptingPlayer, this.isWorried() ? 0.65F : 0.85F),
                BiologicalAiProfiles.PANDA);

        HerbivoreAiSupport.applyDecision(mob, decision, threat, temptingPlayer, 0.8);
    }
}
