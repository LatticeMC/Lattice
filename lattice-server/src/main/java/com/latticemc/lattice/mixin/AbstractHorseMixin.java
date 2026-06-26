package com.latticemc.lattice.mixin;

import com.latticemc.lattice.nativelib.BiologicalAiProfiles;
import com.latticemc.lattice.nativelib.LatticeNative;
import com.latticemc.lattice.nativelib.NativeBiologicalAi;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.minecraft.world.entity.animal.equine.AbstractChestedHorse;
import net.minecraft.world.entity.animal.equine.SkeletonHorse;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractHorse.class)
public abstract class AbstractHorseMixin {
    @Shadow public abstract float getHealth();
    @Shadow public abstract float getMaxHealth();
    @Shadow public abstract boolean isOnFire();
    @Shadow public abstract boolean isPassenger();
    @Shadow public abstract boolean isVehicle();
    @Shadow public abstract boolean isBaby();
    @Shadow public abstract boolean isTamed();
    @Shadow public abstract boolean isInLove();
    @Shadow public abstract boolean isEating();
    @Shadow public abstract boolean isStanding();
    @Shadow public abstract boolean isFood(ItemStack stack);
    @Shadow public abstract @Nullable LivingEntity getLastHurtByMob();
    @Shadow public abstract @Nullable LivingEntity getTarget();
    @Shadow public abstract BlockPos blockPosition();
    @Shadow public abstract double getX();
    @Shadow public abstract double getY();
    @Shadow public abstract double getZ();

    @Inject(method = "aiStep", at = @At("TAIL"))
    private void lattice$runBiologicalAi(CallbackInfo ci) {
        if (!LatticeNative.isLoaded()) return;
        final Mob mob = (Mob) (Object) this;
        if (!(mob.level() instanceof ServerLevel level)) return;
        if (this.isPassenger() || this.isVehicle()) return;

        final float maxHealth = this.getMaxHealth();
        if (maxHealth <= 0.0F) return;

        final boolean skeletonTrap = mob instanceof SkeletonHorse skeletonHorse && skeletonHorse.isTrap();
        final boolean chested = mob instanceof AbstractChestedHorse abstractChestedHorse && abstractChestedHorse.hasChest();
        final boolean busy = this.isEating() || this.isStanding() || this.isInLove() || skeletonTrap;
        final LivingEntity threat = HerbivoreAiSupport.selectThreat(this.getLastHurtByMob(), this.getTarget());
        final Player temptingPlayer = !busy
                ? level.getNearestPlayer(
                        this.getX(), this.getY(), this.getZ(), 10.0,
                        entity -> entity instanceof Player player && this.isFood(player.getMainHandItem()))
                : null;

        final NativeBiologicalAi.Decision decision = NativeBiologicalAi.decide(
                NativeBiologicalAi.Species.CAMEL,
                this.getHealth() / maxHealth,
                busy ? 0.25F : (this.isBaby() ? 0.60F : (this.isTamed() ? (chested ? 0.82F : 0.80F) : 0.65F)),
                0.0F,
                1.5F,
                this.isOnFire(),
                false,
                temptingPlayer != null,
                HerbivoreAiSupport.threatStrength(threat),
                !level.canSeeSky(this.blockPosition()),
                threat == null && !this.isOnFire() && !busy,
                temptingPlayer != null,
                HerbivoreAiSupport.buildStimuli(mob, threat, temptingPlayer, this.isTamed() ? (chested ? 0.80F : 0.75F) : 0.65F),
                BiologicalAiProfiles.CAMEL);

        HerbivoreAiSupport.applyDecision(mob, decision, threat, temptingPlayer, 0.8);
    }
}
