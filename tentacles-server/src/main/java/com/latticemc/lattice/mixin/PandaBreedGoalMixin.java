package com.latticemc.lattice.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.BreedGoal;
import net.minecraft.world.entity.ai.targeting.TargetingConditions;
import net.minecraft.world.entity.animal.panda.Panda;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "net.minecraft.world.entity.animal.panda.Panda$PandaBreedGoal")
public abstract class PandaBreedGoalMixin extends BreedGoal {
    private static final TargetingConditions lattice$BREED_TARGETING = TargetingConditions.forNonCombat().range(8.0);

    @Shadow private Panda panda;
    @Shadow private int unhappyCooldown;
    @Shadow protected ServerLevel level;
    @Shadow protected abstract boolean canFindBamboo();

    private PandaBreedGoalMixin(Panda panda, double speedModifier) {
        super(panda, speedModifier);
    }

    @Inject(method = "canUse", at = @At("HEAD"), cancellable = true)
    private void lattice$canUse(CallbackInfoReturnable<Boolean> cir) {
        if (!super.canUse() || this.panda.getUnhappyCounter() != 0) {
            cir.setReturnValue(false);
            return;
        }
        if (this.canFindBamboo()) {
            cir.setReturnValue(true);
            return;
        }
        if (this.unhappyCooldown <= this.panda.tickCount) {
            this.panda.setUnhappyCounter(32);
            this.unhappyCooldown = this.panda.tickCount + 600;
            if (this.panda.isEffectiveAi()) {
                final AABB area = this.panda.getBoundingBox().inflate(8.0, 8.0, 8.0);
                final Player nearestPlayer = NativeGoalQuerySupport.findNearestEntity(
                        this.panda,
                        this.level,
                        this.level.players(),
                        lattice$BREED_TARGETING,
                        area,
                        this.panda.getX(),
                        this.panda.getY(),
                        this.panda.getZ());
                this.panda.lookAtPlayerGoal.setTarget(nearestPlayer);
            }
        }

        cir.setReturnValue(false);
    }
}
