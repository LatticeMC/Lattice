package com.latticemc.lattice.mixin;

import com.latticemc.lattice.nativelib.BiologicalAiProfiles;
import com.latticemc.lattice.nativelib.LatticeNative;
import com.latticemc.lattice.nativelib.NativeBiologicalAi;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.equine.Llama;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Llama.class)
public abstract class LlamaMixin {
    @Shadow public boolean didSpit;

    @Shadow public abstract float getHealth();
    @Shadow public abstract float getMaxHealth();
    @Shadow public abstract boolean isOnFire();
    @Shadow public abstract boolean isPassenger();
    @Shadow public abstract boolean isVehicle();
    @Shadow public abstract boolean isInWaterOrRain();
    @Shadow public abstract boolean isBaby();
    @Shadow public abstract boolean isFood(ItemStack stack);
    @Shadow public abstract int getStrength();
    @Shadow public abstract boolean inCaravan();
    @Shadow public abstract @Nullable Llama getCaravanHead();
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
        final LivingEntity threat = this.getTarget();
        final Player temptingPlayer = level.getNearestPlayer(
                this.getX(), this.getY(), this.getZ(), 10.0,
                entity -> entity instanceof Player player && this.isFood(player.getMainHandItem()));

        final NativeBiologicalAi.Decision decision = NativeBiologicalAi.decide(
                NativeBiologicalAi.Species.LLAMA,
                this.getHealth() / maxHealth,
                this.didSpit ? 0.90F : (this.inCaravan() ? 0.80F : (this.isBaby() ? 0.55F : 0.65F + this.getStrength() * 0.05F)),
                this.didSpit ? 0.45F : 0.10F,
                1.4F,
                this.isOnFire(),
                false,
                true,
                threat != null ? 1.0F : 0.0F,
                !level.canSeeSky(this.blockPosition()) || this.getCaravanHead() != null,
                threat == null && !this.isOnFire() && !this.didSpit,
                temptingPlayer != null,
                HerbivoreAiSupport.buildStimuli(mob, threat, temptingPlayer, 0.75F),
                BiologicalAiProfiles.LLAMA);

        HerbivoreAiSupport.applyDecision(mob, decision, threat, temptingPlayer, 0.8);
    }
}
