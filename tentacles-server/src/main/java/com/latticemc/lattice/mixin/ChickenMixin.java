package com.latticemc.lattice.mixin;

import com.latticemc.lattice.nativelib.BiologicalAiProfiles;
import com.latticemc.lattice.nativelib.LatticeNative;
import com.latticemc.lattice.nativelib.NativeBiologicalAi;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.chicken.Chicken;
import net.minecraft.world.entity.animal.feline.Ocelot;
import net.minecraft.world.entity.animal.fox.Fox;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Animal.class)
public abstract class ChickenMixin {
    @Unique private static final int lattice$THREAT_SCAN_INTERVAL = 8;
    @Unique private static final double lattice$THREAT_SCAN_RANGE = 8.0;

    @Unique private LivingEntity lattice$cachedChickenThreat;

    @Inject(method = "customServerAiStep", at = @At("TAIL"))
    private void lattice$runChickenBiologicalAi(ServerLevel level, CallbackInfo ci) {
        if (!((Object) this instanceof Chicken chicken)) return;
        if (!LatticeNative.isLoaded()) return;
        if (chicken.isPassenger() || chicken.isVehicle() || chicken.isChickenJockey()) return;

        final float maxHealth = chicken.getMaxHealth();
        if (maxHealth <= 0.0F) return;

        final Mob mob = chicken;
        LivingEntity threat = HerbivoreAiSupport.selectThreat(chicken.getLastHurtByMob(), chicken.getTarget());
        if (threat == null) {
            final Predicate<LivingEntity> threatPredicate = entity -> entity instanceof Ocelot || entity instanceof Fox;
            threat = HerbivoreAiSupport.cachedThreat(mob, this.lattice$cachedChickenThreat, lattice$THREAT_SCAN_RANGE, threatPredicate);
            if (HerbivoreAiSupport.shouldRefreshThreatScan(mob, lattice$THREAT_SCAN_INTERVAL)) {
                this.lattice$cachedChickenThreat = HerbivoreAiSupport.findNearestThreat(mob, level, lattice$THREAT_SCAN_RANGE, threatPredicate);
                threat = this.lattice$cachedChickenThreat;
            }
        }
        final boolean inLove = chicken.isInLove();
        final Player temptingPlayer = !inLove
                ? level.getNearestPlayer(
                        chicken.getX(), chicken.getY(), chicken.getZ(), 8.0,
                        entity -> entity instanceof Player player && chicken.isFood(player.getMainHandItem()))
                : null;

        final float energyRatio;
        if (inLove) {
            energyRatio = 0.35F;
        } else if (chicken.isBaby()) {
            energyRatio = 0.50F;
        } else if (chicken.eggTime < 1200) {
            energyRatio = 0.35F;
        } else {
            energyRatio = 0.65F;
        }

        final NativeBiologicalAi.Decision decision = NativeBiologicalAi.decide(
                NativeBiologicalAi.Species.CHICKEN,
                chicken.getHealth() / maxHealth,
                energyRatio,
                0.0F,
                1.0F,
                chicken.isOnFire(),
                false,
                true,
                threat != null ? 1.0F : 0.0F,
                !level.canSeeSky(chicken.blockPosition()),
                threat == null && !chicken.isOnFire() && !inLove,
                temptingPlayer != null,
                HerbivoreAiSupport.buildStimuli(mob, threat, temptingPlayer, 0.9F),
                BiologicalAiProfiles.CHICKEN);

        HerbivoreAiSupport.applyDecision(mob, decision, threat, temptingPlayer, 0.9);
    }
}
