package com.latticemc.lattice.bridge;

import com.latticemc.lattice.nativelib.NativeEntityQuery;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;

final class AnimalCandidateQuerySupport {
    private static final int MIN_NATIVE_CANDIDATES = 8;

    private AnimalCandidateQuerySupport() {}

    static @Nullable LivingEntity findNearest(Mob self, ServerLevel level, double range,
                                              Predicate<LivingEntity> predicate,
                                              Class<? extends LivingEntity>[] nativePrefilterTypes) {
        final AABB area = self.getBoundingBox().inflate(range);
        if (!NativeEntityQuery.isAvailable()) {
            return nearest(self, range, level.getEntitiesOfClass(LivingEntity.class, area,
                    entity -> eligible(self, entity, predicate)));
        }

        final List<LivingEntity> candidates = level.getEntitiesOfClass(LivingEntity.class, area, entity -> true);
        int snapshotCount = 0;
        if (candidates.size() >= MIN_NATIVE_CANDIDATES) {
            for (LivingEntity entity : candidates) {
                if (matchesAnyType(entity, nativePrefilterTypes)) ++snapshotCount;
            }
        }
        if (snapshotCount < MIN_NATIVE_CANDIDATES) {
            // Finish predicates before reading distances, as the original
            // filtered level query did, including predicate side effects.
            final List<LivingEntity> accepted = new ArrayList<>();
            for (LivingEntity entity : candidates) {
                if (eligible(self, entity, predicate)) accepted.add(entity);
            }
            return nearest(self, range, accepted);
        }

        final Map<Integer, LivingEntity> byId = new HashMap<>(snapshotCount);
        final NativeEntityQuery.EntitySnapshot[] snapshots = new NativeEntityQuery.EntitySnapshot[snapshotCount];
        int index = 0;
        for (LivingEntity entity : candidates) {
            if (!matchesAnyType(entity, nativePrefilterTypes)) continue;
            final AABB box = entity.getBoundingBox();
            byId.put(entity.getId(), entity);
            snapshots[index++] = new NativeEntityQuery.EntitySnapshot(
                    entity.getId(), BuiltInRegistries.ENTITY_TYPE.getId(entity.getType()),
                    entity.getX(), entity.getY(), entity.getZ(),
                    box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ,
                    entity.isAlive(), entity.isSpectator());
        }
        final int[] nearestIds = NativeEntityQuery.queryAll(
                area.minX, area.minY, area.minZ, area.maxX, area.maxY, area.maxZ,
                snapshots, null, NativeEntityQuery.PredicateKind.IS_ALIVE_NOT_SELF_NOT_SPEC,
                self.getId(), true, self.getX(), self.getY(), self.getZ());
        final double maxDistance = range * range;
        for (int id : nearestIds) {
            final LivingEntity candidate = byId.get(id);
            if (candidate != null && self.distanceToSqr(candidate) <= maxDistance && predicate.test(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static boolean matchesAnyType(LivingEntity entity, Class<? extends LivingEntity>[] types) {
        if (types == null || types.length == 0) return true;
        for (Class<? extends LivingEntity> type : types) {
            if (type.isInstance(entity)) return true;
        }
        return false;
    }

    private static boolean eligible(Mob self, LivingEntity entity, Predicate<LivingEntity> predicate) {
        return entity != self && entity.isAlive() && !entity.isSpectator() && predicate.test(entity);
    }

    private static @Nullable LivingEntity nearest(Mob self, double range, List<LivingEntity> candidates) {
        LivingEntity nearest = null;
        double nearestDistance = range * range;
        for (LivingEntity candidate : candidates) {
            final double distance = self.distanceToSqr(candidate);
            if (distance <= nearestDistance) {
                nearest = candidate;
                nearestDistance = distance;
            }
        }
        return nearest;
    }
}
