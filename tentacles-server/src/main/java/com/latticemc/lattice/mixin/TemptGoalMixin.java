package com.latticemc.lattice.mixin;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.TemptGoal;
import net.minecraft.world.entity.ai.targeting.TargetingConditions;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(TemptGoal.class)
public abstract class TemptGoalMixin extends Goal {
    @Shadow @Final private TargetingConditions targetingConditions;
    @Shadow protected Mob mob;
    @Shadow protected @Nullable Player player;
    @Shadow private int calmDown;

    @Inject(method = "canUse", at = @At("HEAD"), cancellable = true)
    private void lattice$canUse(CallbackInfoReturnable<Boolean> cir) {
        if (this.calmDown > 0) {
            this.calmDown--;
            cir.setReturnValue(false);
            return;
        }

        final double temptRange = this.mob.getAttributeValue(Attributes.TEMPT_RANGE);
        final AABB area = this.mob.getBoundingBox().inflate(temptRange, temptRange, temptRange);
        this.player = NativeGoalQuerySupport.findNearestEntity(
                this.mob,
                getServerLevel(this.mob),
                getServerLevel(this.mob).players(),
                this.targetingConditions.range(temptRange),
                area,
                this.mob.getX(),
                this.mob.getY(),
                this.mob.getZ());
        cir.setReturnValue(this.player != null);
    }
}
