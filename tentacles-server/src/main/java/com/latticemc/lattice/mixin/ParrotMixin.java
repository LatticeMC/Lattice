package com.latticemc.lattice.mixin;

import com.latticemc.lattice.nativelib.BiologicalAiProfiles;
import com.latticemc.lattice.nativelib.LatticeNative;
import com.latticemc.lattice.nativelib.NativeBiologicalAi;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.parrot.Parrot;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Animal.class)
public abstract class ParrotMixin {
    @Inject(method = "customServerAiStep", at = @At("TAIL"))
    private void lattice$runParrotBiologicalAi(ServerLevel level, CallbackInfo ci) {
        if (!((Object) this instanceof Parrot parrot)) return;
        if (!LatticeNative.isLoaded()) return;
        if (parrot.isPassenger() || parrot.isVehicle()) return;

        final float maxHealth = parrot.getMaxHealth();
        if (maxHealth <= 0.0F) return;

        final Mob mob = parrot;
        final LivingEntity threat = HerbivoreAiSupport.selectThreat(parrot.getLastHurtByMob(), parrot.getTarget());
        final boolean busy = parrot.isPartyParrot()
                || parrot.isOrderedToSit()
                || parrot.isInSittingPose()
                || parrot.isFlying();

        final NativeBiologicalAi.Decision decision = NativeBiologicalAi.decide(
                this.lattice$parrotHealthRatio(parrot, maxHealth),
                busy ? 0.20F : 0.75F,
                0.0F,
                1.0F,
                parrot.isOnFire(),
                false,
                false,
                HerbivoreAiSupport.threatStrength(threat),
                !level.canSeeSky(parrot.blockPosition()),
                threat == null && !parrot.isOnFire() && !busy,
                false,
                HerbivoreAiSupport.buildStimuli(mob, threat, null, 0.0F),
                BiologicalAiProfiles.BEE);

        if (decision.action() == NativeBiologicalAi.Action.FLEE && threat != null && threat.isAlive()) {
            HerbivoreAiSupport.applyDecision(mob, decision, threat, null, 0.8);
            return;
        }

        if (decision.action() == NativeBiologicalAi.Action.REST) {
            mob.stopInPlace();
        }
    }

    @Unique
    private float lattice$parrotHealthRatio(Parrot parrot, float maxHealth) {
        return parrot.getHealth() / maxHealth;
    }
}
