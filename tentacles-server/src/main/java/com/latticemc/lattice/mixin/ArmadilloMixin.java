package com.latticemc.lattice.mixin;

import com.latticemc.lattice.nativelib.BiologicalAiProfiles;
import com.latticemc.lattice.nativelib.LatticeNative;
import com.latticemc.lattice.nativelib.NativeBiologicalAi;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.armadillo.Armadillo;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Armadillo.class)
public abstract class ArmadilloMixin {
    @Shadow public abstract float getHealth();
    @Shadow public abstract float getMaxHealth();
    @Shadow public abstract boolean isOnFire();
    @Shadow public abstract boolean isPassenger();
    @Shadow public abstract boolean isVehicle();
    @Shadow public abstract boolean isInWaterOrRain();
    @Shadow public abstract boolean isBaby();
    @Shadow public abstract boolean isFood(ItemStack stack);
    @Shadow public abstract boolean isScared();
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
        final boolean scared = this.isScared();
        final LivingEntity threat = HerbivoreAiSupport.selectThreat(this.getLastHurtByMob(), this.getTarget());
        final Player temptingPlayer = !scared
                ? level.getNearestPlayer(
                        this.getX(), this.getY(), this.getZ(), 10.0,
                        entity -> entity instanceof Player player && this.isFood(player.getMainHandItem()))
                : null;

        final NativeBiologicalAi.Decision decision = NativeBiologicalAi.decide(
                NativeBiologicalAi.Species.ARMADILLO,
                this.getHealth() / maxHealth,
                scared ? 0.10F : (this.isBaby() ? 0.55F : 0.70F),
                0.0F,
                1.0F,
                this.isOnFire(),
                false,
                temptingPlayer != null,
                this.isOnFire() ? 1.0F : (threat != null ? 0.8F : (scared ? 0.75F : 0.0F)),
                !level.canSeeSky(this.blockPosition()),
                threat == null && !this.isOnFire(),
                temptingPlayer != null,
                HerbivoreAiSupport.buildStimuli(mob, threat, temptingPlayer, 0.75F),
                BiologicalAiProfiles.ARMADILLO);

        HerbivoreAiSupport.applyDecision(mob, decision, threat, temptingPlayer, 0.7);
    }
}
