package com.latticemc.lattice.mixin;

import com.latticemc.lattice.nativelib.LatticeNative;
import com.latticemc.lattice.nativelib.NativeCollisionSweep;
import java.util.List;
import net.minecraft.world.phys.AABB;
import ca.spottedleaf.moonrise.patches.collisions.CollisionUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(CollisionUtil.class)
public abstract class CollisionUtilMixin {
    private static final int MIN_NATIVE_OBSTACLES = 8;

    @Inject(method = "performAABBCollisionsX", at = @At("HEAD"), cancellable = true)
    private static void lattice$collideX(AABB currentBoundingBox,
                                         double value,
                                         List<AABB> potentialCollisions,
                                         CallbackInfoReturnable<Double> cir) {
        if (!NativeCollisionSweep.isAvailable()
                || Math.abs(value) < NativeCollisionSweep.COLLISION_EPSILON
                || potentialCollisions.size() < MIN_NATIVE_OBSTACLES) {
            return;
        }

        try {
            double[] moving = {
                    currentBoundingBox.minX, currentBoundingBox.minY, currentBoundingBox.minZ,
                    currentBoundingBox.maxX, currentBoundingBox.maxY, currentBoundingBox.maxZ,
            };
            double[] obstacles = NativeCollisionSweep.flattenAabbs(potentialCollisions);
            double result = NativeCollisionSweep.calcMaxOffset(0, moving, value, obstacles, potentialCollisions.size());
            cir.setReturnValue(result);
        } catch (Exception e) {
            LatticeNative.logFallbackOnce("collision_sweep_x", e.getMessage());
        }
    }

    @Inject(method = "performAABBCollisionsY", at = @At("HEAD"), cancellable = true)
    private static void lattice$collideY(AABB currentBoundingBox,
                                         double value,
                                         List<AABB> potentialCollisions,
                                         CallbackInfoReturnable<Double> cir) {
        if (!NativeCollisionSweep.isAvailable()
                || Math.abs(value) < NativeCollisionSweep.COLLISION_EPSILON
                || potentialCollisions.size() < MIN_NATIVE_OBSTACLES) {
            return;
        }

        try {
            double[] moving = {
                    currentBoundingBox.minX, currentBoundingBox.minY, currentBoundingBox.minZ,
                    currentBoundingBox.maxX, currentBoundingBox.maxY, currentBoundingBox.maxZ,
            };
            double[] obstacles = NativeCollisionSweep.flattenAabbs(potentialCollisions);
            double result = NativeCollisionSweep.calcMaxOffset(1, moving, value, obstacles, potentialCollisions.size());
            cir.setReturnValue(result);
        } catch (Exception e) {
            LatticeNative.logFallbackOnce("collision_sweep_y", e.getMessage());
        }
    }

    @Inject(method = "performAABBCollisionsZ", at = @At("HEAD"), cancellable = true)
    private static void lattice$collideZ(AABB currentBoundingBox,
                                         double value,
                                         List<AABB> potentialCollisions,
                                         CallbackInfoReturnable<Double> cir) {
        if (!NativeCollisionSweep.isAvailable()
                || Math.abs(value) < NativeCollisionSweep.COLLISION_EPSILON
                || potentialCollisions.size() < MIN_NATIVE_OBSTACLES) {
            return;
        }

        try {
            double[] moving = {
                    currentBoundingBox.minX, currentBoundingBox.minY, currentBoundingBox.minZ,
                    currentBoundingBox.maxX, currentBoundingBox.maxY, currentBoundingBox.maxZ,
            };
            double[] obstacles = NativeCollisionSweep.flattenAabbs(potentialCollisions);
            double result = NativeCollisionSweep.calcMaxOffset(2, moving, value, obstacles, potentialCollisions.size());
            cir.setReturnValue(result);
        } catch (Exception e) {
            LatticeNative.logFallbackOnce("collision_sweep_z", e.getMessage());
        }
    }
}
