package com.latticemc.lattice.mixin;

import com.latticemc.lattice.nativelib.BiologicalAiProfiles;
import com.latticemc.lattice.nativelib.LatticeNative;
import com.latticemc.lattice.nativelib.NativeBiologicalAi;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Animal.class)
public abstract class PigMixin {
    @Inject(method = "customServerAiStep", at = @At("TAIL"))
    private void lattice$runPigBiologicalAi(ServerLevel level, CallbackInfo ci) {
        if (!((Object) this instanceof Pig pig)) return;
        if (!LatticeNative.isLoaded()) return;
        if (pig.isPassenger() || pig.isVehicle()) return;
        if (pig.getControllingPassenger() != null) return;

        final float maxHealth = pig.getMaxHealth();
        if (maxHealth <= 0.0F) return;

        final Mob mob = pig;
        final LivingEntity threat = HerbivoreAiSupport.selectThreat(pig.getLastHurtByMob(), pig.getTarget());
        final Player temptingPlayer = level.getNearestPlayer(
                pig.getX(), pig.getY(), pig.getZ(), 10.0,
                entity -> entity instanceof Player player
                        && (player.isHolding(Items.CARROT_ON_A_STICK) || pig.isFood(player.getMainHandItem())));

        final NativeBiologicalAi.Decision decision = NativeBiologicalAi.decide(
                NativeBiologicalAi.Species.PIG,
                pig.getHealth() / maxHealth,
                pig.isSaddled() ? 0.85F : (pig.isBaby() ? 0.60F : 0.75F),
                0.0F,
                1.5F,
                pig.isOnFire(),
                false,
                true,
                threat != null ? 1.0F : 0.0F,
                !level.canSeeSky(pig.blockPosition()),
                threat == null && !pig.isOnFire(),
                temptingPlayer != null,
                HerbivoreAiSupport.buildStimuli(mob, threat, temptingPlayer,
                        temptingPlayer != null && temptingPlayer.isHolding(Items.CARROT_ON_A_STICK) ? 1.0F : 0.8F),
                BiologicalAiProfiles.PIG);

        HerbivoreAiSupport.applyDecision(mob, decision, threat, temptingPlayer, 0.8);
    }
}
