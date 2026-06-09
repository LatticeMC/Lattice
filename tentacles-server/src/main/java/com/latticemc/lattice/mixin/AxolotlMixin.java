package com.latticemc.lattice.mixin;

import com.latticemc.lattice.nativelib.BiologicalAiProfiles;
import com.latticemc.lattice.nativelib.LatticeNative;
import com.latticemc.lattice.nativelib.NativeBiologicalAi;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.axolotl.Axolotl;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Axolotl.class)
public abstract class AxolotlMixin {
    @Shadow public abstract float getHealth();
    @Shadow public abstract float getMaxHealth();
    @Shadow public abstract boolean isOnFire();
    @Shadow public abstract boolean isPassenger();
    @Shadow public abstract boolean isVehicle();
    @Shadow public abstract boolean isInWaterOrRain();
    @Shadow public abstract boolean isInWater();
    @Shadow public abstract boolean isBaby();
    @Shadow public abstract boolean isFood(ItemStack stack);
    @Shadow public abstract boolean isPlayingDead();
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
        final LivingEntity threat = this.getTarget() != null && this.isOnFire() ? this.getTarget() : null;
        final Player temptingPlayer = level.getNearestPlayer(
                this.getX(), this.getY(), this.getZ(), 10.0,
                entity -> entity instanceof Player player && this.isFood(player.getMainHandItem()));

        final float energyRatio;
        if (this.isPlayingDead()) {
            energyRatio = 0.20F;
        } else if (this.isInWater()) {
            energyRatio = 0.90F;
        } else if (this.isBaby()) {
            energyRatio = 0.55F;
        } else {
            energyRatio = 0.65F;
        }

        final NativeBiologicalAi.Decision decision = NativeBiologicalAi.decide(
                NativeBiologicalAi.Species.AXOLOTL,
                this.getHealth() / maxHealth,
                energyRatio,
                0.10F,
                1.0F,
                this.isOnFire(),
                false,
                true,
                threat != null || this.isPlayingDead() ? 1.0F : 0.0F,
                this.isInWater() || !level.canSeeSky(this.blockPosition()),
                threat == null && !this.isOnFire() && !this.isPlayingDead(),
                temptingPlayer != null,
                HerbivoreAiSupport.buildStimuli(mob, threat, temptingPlayer, 0.75F),
                BiologicalAiProfiles.AXOLOTL);

        AquaticAiSupport.applyDecision(mob, decision, threat, temptingPlayer, 0.8, this.isInWater());
    }
}
