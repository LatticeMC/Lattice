package com.latticemc.lattice.mixin;

import com.latticemc.lattice.nativelib.NativeEntityQuery;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.NearestVisibleLivingEntities;
import net.minecraft.world.entity.ai.sensing.NearestLivingEntitySensor;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(NearestLivingEntitySensor.class)
public abstract class NearestLivingEntitySensorMixin<T extends LivingEntity> {
    @Inject(method = "doTick", at = @At("HEAD"), cancellable = true)
    private void lattice$doTick(ServerLevel level, T entity, CallbackInfo ci) {
        final double followDistance = entity.getAttributeValue(Attributes.FOLLOW_RANGE);
        final AABB area = entity.getBoundingBox().inflate(followDistance, followDistance, followDistance);
        final List<LivingEntity> entities = NativeGoalQuerySupport.sortByDistance(
                entity,
                level.getEntitiesOfClass(LivingEntity.class, area, livingEntity -> true),
                area,
                entity.getX(),
                entity.getY(),
                entity.getZ(),
                livingEntity -> livingEntity != entity && livingEntity.isAlive(),
                NativeEntityQuery.PredicateKind.IS_ALIVE_NOT_SELF);
        final Brain<?> brain = entity.getBrain();
        brain.setMemory(MemoryModuleType.NEAREST_LIVING_ENTITIES, entities);
        brain.setMemory(MemoryModuleType.NEAREST_VISIBLE_LIVING_ENTITIES, new NearestVisibleLivingEntities(level, entity, entities));
        ci.cancel();
    }
}
