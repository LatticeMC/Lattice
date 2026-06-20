package com.latticemc.lattice.mixin;

import com.latticemc.lattice.nativelib.BiologicalAiProfiles;
import com.latticemc.lattice.nativelib.LatticeNative;
import com.latticemc.lattice.nativelib.NativeBiologicalAi;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.sniffer.Sniffer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Sniffer.class)
public abstract class SnifferMixin {
    @Shadow public abstract float getHealth();
    @Shadow public abstract float getMaxHealth();
    @Shadow public abstract boolean isOnFire();
    @Shadow public abstract boolean isPassenger();
    @Shadow public abstract boolean isVehicle();
    @Shadow public abstract boolean isInWater();
    @Shadow public abstract boolean isBaby();
    @Shadow public abstract boolean isFood(ItemStack stack);
    @Shadow public abstract boolean isTempted();
    @Shadow public abstract boolean canSniff();
    @Shadow public abstract Sniffer.State getState();
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
        final Sniffer.State state = this.getState();
        final boolean digging = state == Sniffer.State.DIGGING || state == Sniffer.State.RISING;
        final boolean sniffing = state == Sniffer.State.SCENTING || state == Sniffer.State.SNIFFING || state == Sniffer.State.SEARCHING;
        final boolean happy = state == Sniffer.State.FEELING_HAPPY;
        final boolean inWater = this.isInWater();
        final LivingEntity target = this.getTarget();
        final LivingEntity threat = this.isOnFire() && target != null && target.isAlive() ? target : null;
        final boolean busy = digging || happy || sniffing;
        final Player temptingPlayer = !busy && !inWater
                ? level.getNearestPlayer(
                        this.getX(), this.getY(), this.getZ(), 12.0,
                        entity -> entity instanceof Player player && this.isFood(player.getMainHandItem()))
                : null;
        final float energyRatio = snifferEnergyRatio(state, this.isTempted(), inWater, this.isBaby());

        final NativeBiologicalAi.Decision decision = NativeBiologicalAi.decide(
                NativeBiologicalAi.Species.SNIFFER,
                this.getHealth() / maxHealth,
                energyRatio,
                0.10F,
                1.0F,
                this.isOnFire(),
                false,
                temptingPlayer != null,
                this.isOnFire() ? 1.0F : (inWater ? 0.45F : 0.0F),
                !level.canSeeSky(this.blockPosition()) && !inWater,
                threat == null && !this.isOnFire() && !sniffing,
                temptingPlayer != null,
                HerbivoreAiSupport.buildStimuli(mob, threat, temptingPlayer, this.canSniff() ? 0.85F : 0.65F),
                BiologicalAiProfiles.SNIFFER);

        HerbivoreAiSupport.applyDecision(mob, decision, threat, temptingPlayer, 0.75);
    }

    private static float snifferEnergyRatio(Sniffer.State state, boolean tempted, boolean inWater, boolean baby) {
        return switch (state) {
            case DIGGING, RISING -> 0.10F;
            case FEELING_HAPPY -> 0.20F;
            case SCENTING, SNIFFING, SEARCHING -> 0.90F;
            default -> inWater ? 0.35F : (tempted ? 0.80F : (baby ? 0.60F : 0.65F));
        };
    }
}
