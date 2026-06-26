package com.latticemc.lattice.mixin;

import com.latticemc.lattice.nativelib.NativeLineOfSight;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Level.class)
public abstract class LevelLineOfSightCacheMixin {
    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z", at = @At("RETURN"))
    private void lattice$invalidateLineOfSightSection(BlockPos pos,
                                                      BlockState state,
                                                      @Block.UpdateFlags int flags,
                                                      int recursionLeft,
                                                      CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValue()) {
            NativeLineOfSight.invalidateSection((Level)(Object)this, pos);
        }
    }
}
