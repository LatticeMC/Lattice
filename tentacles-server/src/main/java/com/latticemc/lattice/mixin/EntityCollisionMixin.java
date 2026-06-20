package com.latticemc.lattice.mixin;

import com.latticemc.lattice.nativelib.LatticeNative;
import com.latticemc.lattice.nativelib.NativeCollisionSweep;
import java.util.List;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Entity.class)
public abstract class EntityCollisionMixin {
    private static final int MIN_NATIVE_OBSTACLES = 8;

    @Inject(method = "collideWithShapes", at = @At("HEAD"), cancellable = true)
    private static void lattice$collideWithShapes(Vec3 deltaMovement,
                                                  AABB entityBB,
                                                  List<VoxelShape> shapes,
                                                  CallbackInfoReturnable<Vec3> cir) {
        if (!NativeCollisionSweep.isAvailable() || shapes.isEmpty()) {
            return;
        }

        int obstacleCount = 0;
        for (VoxelShape shape : shapes) {
            List<AABB> aabbs = shape.toAabbs();
            if (aabbs.size() != 1) {
                return;
            }
            obstacleCount++;
        }
        if (obstacleCount < MIN_NATIVE_OBSTACLES) {
            return;
        }

        double[] obstacles = new double[obstacleCount * NativeCollisionSweep.AABB_STRIDE];
        int index = 0;
        for (VoxelShape shape : shapes) {
            AABB box = shape.toAabbs().get(0);
            int base = index++ * NativeCollisionSweep.AABB_STRIDE;
            obstacles[base] = box.minX;
            obstacles[base + 1] = box.minY;
            obstacles[base + 2] = box.minZ;
            obstacles[base + 3] = box.maxX;
            obstacles[base + 4] = box.maxY;
            obstacles[base + 5] = box.maxZ;
        }

        double[] moving = {
                entityBB.minX, entityBB.minY, entityBB.minZ,
                entityBB.maxX, entityBB.maxY, entityBB.maxZ,
        };
        double[] adjusted = {deltaMovement.x, deltaMovement.y, deltaMovement.z};
        try {
            NativeCollisionSweep.adjustMovement(moving, adjusted, obstacles, obstacleCount);
            cir.setReturnValue(new Vec3(adjusted[0], adjusted[1], adjusted[2]));
        } catch (Exception e) {
            LatticeNative.logFallbackOnce("entity_collision", e.getMessage());
        }
    }
}
