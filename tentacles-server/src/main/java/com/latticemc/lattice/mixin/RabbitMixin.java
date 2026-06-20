package com.latticemc.lattice.mixin;

import com.latticemc.lattice.nativelib.BiologicalAiProfiles;
import com.latticemc.lattice.nativelib.LatticeNative;
import com.latticemc.lattice.nativelib.NativeBiologicalAi;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.entity.animal.rabbit.Rabbit;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Rabbit.class)
public abstract class RabbitMixin {
    @Unique private static final int lattice$THREAT_SCAN_INTERVAL = 8;
    @Unique private static final double lattice$THREAT_SCAN_RANGE = 10.0;

    @Unique private @Nullable LivingEntity lattice$cachedThreat;

    @Shadow public int moreCarrotTicks;

    @Shadow public abstract float getHealth();
    @Shadow public abstract float getMaxHealth();
    @Shadow public abstract boolean isOnFire();
    @Shadow public abstract boolean isPassenger();
    @Shadow public abstract boolean isVehicle();
    @Shadow public abstract boolean isInWaterOrRain();
    @Shadow public abstract boolean isBaby();
    @Shadow public abstract boolean isFood(ItemStack stack);
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
        LivingEntity threat = HerbivoreAiSupport.selectThreat(this.getLastHurtByMob(), this.getTarget());
        if (threat == null) {
            final Predicate<LivingEntity> threatPredicate = entity -> entity instanceof Wolf
                    || entity instanceof Monster
                    || entity instanceof Player;
            threat = HerbivoreAiSupport.cachedThreat(mob, this.lattice$cachedThreat, lattice$THREAT_SCAN_RANGE, threatPredicate);
            if (HerbivoreAiSupport.shouldRefreshThreatScan(mob, lattice$THREAT_SCAN_INTERVAL)) {
                this.lattice$cachedThreat = HerbivoreAiSupport.findNearestThreat(mob, level, lattice$THREAT_SCAN_RANGE, threatPredicate);
                threat = this.lattice$cachedThreat;
            }
        }
        final Player temptingPlayer = level.getNearestPlayer(
                this.getX(), this.getY(), this.getZ(), 9.0,
                entity -> entity instanceof Player player && this.isFood(player.getMainHandItem()));

        final float energyRatio;
        if (this.isBaby()) {
            energyRatio = 0.45F;
        } else if (this.moreCarrotTicks > 0) {
            energyRatio = 0.80F;
        } else {
            energyRatio = 0.40F;
        }

        final NativeBiologicalAi.Decision decision = NativeBiologicalAi.decide(
                NativeBiologicalAi.Species.RABBIT,
                this.getHealth() / maxHealth,
                energyRatio,
                0.0F,
                1.0F,
                this.isOnFire(),
                false,
                true,
                threat != null ? 1.0F : 0.0F,
                !level.canSeeSky(this.blockPosition()),
                threat == null && !this.isOnFire(),
                temptingPlayer != null,
                HerbivoreAiSupport.buildStimuli(mob, threat, temptingPlayer, 1.0F),
                BiologicalAiProfiles.RABBIT);

        HerbivoreAiSupport.applyDecision(mob, decision, threat, temptingPlayer, 1.0);
    }
}
