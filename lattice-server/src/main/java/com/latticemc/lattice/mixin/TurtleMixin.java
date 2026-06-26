package com.latticemc.lattice.mixin;

import com.latticemc.lattice.nativelib.BiologicalAiProfiles;
import com.latticemc.lattice.nativelib.NativeHomeTargetSampler;
import com.latticemc.lattice.nativelib.LatticeNative;
import com.latticemc.lattice.nativelib.NativeBiologicalAi;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.turtle.Turtle;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Turtle.class)
public abstract class TurtleMixin {
    @Shadow BlockPos homePos;
    @Shadow @Nullable BlockPos travelPos;
    @Shadow boolean goingHome;

    @Shadow public abstract float getHealth();
    @Shadow public abstract float getMaxHealth();
    @Shadow public abstract boolean isOnFire();
    @Shadow public abstract boolean isPassenger();
    @Shadow public abstract boolean isVehicle();
    @Shadow public abstract boolean isInWaterOrRain();
    @Shadow public abstract boolean isInWater();
    @Shadow public abstract boolean isBaby();
    @Shadow public abstract boolean isFood(ItemStack stack);
    @Shadow public abstract boolean hasEgg();
    @Shadow public abstract boolean isLayingEgg();
    @Shadow public abstract @Nullable LivingEntity getTarget();
    @Shadow public abstract BlockPos blockPosition();
    @Shadow public abstract double getX();
    @Shadow public abstract double getY();
    @Shadow public abstract double getZ();

    @Inject(method = "aiStep", at = @At("TAIL"))
    private void lattice$runBiologicalAi(CallbackInfo ci) {
        if (!LatticeNative.isLoaded()) return;
        if (this.isPassenger() || this.isVehicle()) return;

        if (!(((Turtle) (Object) this).level() instanceof ServerLevel level)) return;

        final float maxHealth = this.getMaxHealth();
        if (maxHealth <= 0.0F) return;

        final Mob mob = (Mob) (Object) this;
        final LivingEntity target = this.getTarget();
        final LivingEntity threat = target != null && target.isAlive() ? target : null;
        final boolean inWater = this.isInWater();
        final boolean hasEgg = this.hasEgg();
        final boolean layingEgg = this.isLayingEgg();
        final boolean returningHome = this.goingHome || hasEgg;
        final boolean travelling = this.travelPos != null && inWater && !returningHome;
        final Vec3 position = new Vec3(this.getX(), this.getY(), this.getZ());
        final boolean nearHome = this.homePos.closerToCenterThan(position, 16.0);
        final boolean closeEnoughToLayEgg = this.homePos.closerToCenterThan(position, 9.0);
        final Player temptingPlayer = !returningHome && !layingEgg && !travelling
                ? level.getNearestPlayer(
                        this.getX(), this.getY(), this.getZ(), 10.0,
                        entity -> entity instanceof Player player && this.isFood(player.getMainHandItem()))
                : null;

        final float energyRatio;
        if (layingEgg) {
            energyRatio = 0.20F;
        } else if (hasEgg) {
            energyRatio = 0.35F;
        } else if (this.goingHome) {
            energyRatio = 0.45F;
        } else if (travelling) {
            energyRatio = 0.90F;
        } else if (inWater) {
            energyRatio = 0.85F;
        } else if (this.isBaby()) {
            energyRatio = 0.55F;
        } else {
            energyRatio = 0.65F;
        }

        final NativeBiologicalAi.Decision decision = NativeBiologicalAi.decide(
                NativeBiologicalAi.Species.TURTLE,
                this.getHealth() / maxHealth,
                energyRatio,
                0.0F,
                1.0F,
                this.isOnFire(),
                false,
                temptingPlayer != null,
                HerbivoreAiSupport.threatStrength(threat),
                inWater || nearHome,
                threat == null && !this.isOnFire() && !layingEgg && !returningHome && !travelling,
                temptingPlayer != null,
                HerbivoreAiSupport.buildStimuli(mob, threat, temptingPlayer, hasEgg ? 0.6F : 0.8F),
                BiologicalAiProfiles.TURTLE);

        if ((this.goingHome || (hasEgg && !closeEnoughToLayEgg)) && !layingEgg && threat == null) {
            double[] candidates = buildHomeCandidates(this.homePos, 4.0);
            int candidate = NativeHomeTargetSampler.sampleHomeTarget(
                    candidates,
                    candidates.length / 3,
                    this.getX(), this.getY(), this.getZ(),
                    this.homePos.getX(), this.homePos.getY(), this.homePos.getZ(),
                    null, 0,
                    4.0,
                    0.5);
            if (candidate >= 0) {
                mob.getNavigation().moveTo(
                        candidates[candidate * 3],
                        candidates[candidate * 3 + 1],
                        candidates[candidate * 3 + 2],
                        0.7);
                return;
            }
        }

        if (travelling && threat == null) {
            final BlockPos travelPos = this.travelPos;
            mob.getNavigation().moveTo(
                    travelPos.getX() + 0.5,
                    travelPos.getY(),
                    travelPos.getZ() + 0.5,
                    1.0);
            return;
        }

        AquaticAiSupport.applyDecision(mob, decision, threat, temptingPlayer, 0.7, this.isInWater());
    }

    private double[] buildHomeCandidates(BlockPos home, double preferredDistance) {
        double hx = home.getX() + 0.5;
        double hy = home.getY();
        double hz = home.getZ() + 0.5;
        return new double[] {
                hx + preferredDistance, hy, hz,
                hx - preferredDistance, hy, hz,
                hx, hy, hz + preferredDistance,
                hx, hy, hz - preferredDistance,
                hx, hy, hz,
        };
    }
}
