package com.latticemc.lattice.mixin;

import com.latticemc.lattice.nativelib.NativeEntityQuery;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.targeting.TargetingConditions;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.EntityGetter;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;

final class NativeGoalQuerySupport {
    private static final int MIN_NATIVE_CANDIDATES = 8;

    private NativeGoalQuerySupport() {}

    static <T extends LivingEntity> @Nullable T findNearestEntity(LivingEntity source,
                                                                  ServerLevel level,
                                                                  List<? extends T> candidates,
                                                                  TargetingConditions targetingConditions,
                                                                  AABB area,
                                                                  double x,
                                                                  double y,
                                                                  double z) {
        if (!NativeEntityQuery.isAvailable() || candidates.size() < MIN_NATIVE_CANDIDATES) {
            return lattice$findNearestEntityFallback(level, source, candidates, targetingConditions, x, y, z);
        }

        final Map<Integer, T> byId = new HashMap<>(candidates.size());
        final NativeEntityQuery.EntitySnapshot[] snapshots = new NativeEntityQuery.EntitySnapshot[candidates.size()];
        int snapshotCount = 0;
        for (int i = candidates.size() - 1; i >= 0; --i) {
            final T entity = candidates.get(i);
            final AABB box = entity.getBoundingBox();
            byId.put(entity.getId(), entity);
            snapshots[snapshotCount++] = new NativeEntityQuery.EntitySnapshot(
                    entity.getId(),
                    BuiltInRegistries.ENTITY_TYPE.getId(entity.getType()),
                    entity.getX(), entity.getY(), entity.getZ(),
                    box.minX, box.minY, box.minZ,
                    box.maxX, box.maxY, box.maxZ,
                    entity.isAlive(),
                    entity.isSpectator());
        }

        final int[] nearestIds = NativeEntityQuery.query(
                area.minX, area.minY, area.minZ,
                area.maxX, area.maxY, area.maxZ,
                snapshotCount == snapshots.length ? snapshots : Arrays.copyOf(snapshots, snapshotCount),
                null,
                NativeEntityQuery.PredicateKind.IS_ALIVE_NOT_SELF_NOT_SPEC,
                source.getId(),
                true,
                snapshotCount,
                x, y, z);
        for (int id : nearestIds) {
            final T candidate = byId.get(id);
            if (candidate != null && targetingConditions.test(level, source, candidate)) {
                return candidate;
            }
        }
        return null;
    }

    static <T extends LivingEntity> List<T> sortByDistance(LivingEntity source,
                                                           List<? extends T> candidates,
                                                           AABB area,
                                                           double x,
                                                           double y,
                                                           double z,
                                                           Predicate<? super T> filter,
                                                           NativeEntityQuery.PredicateKind predicateKind) {
        if (!NativeEntityQuery.isAvailable() || candidates.size() < MIN_NATIVE_CANDIDATES) {
            return lattice$sortByDistanceFallback(candidates, x, y, z, filter);
        }

        final Map<Integer, T> byId = new HashMap<>(candidates.size());
        final NativeEntityQuery.EntitySnapshot[] snapshots = new NativeEntityQuery.EntitySnapshot[candidates.size()];
        int snapshotCount = 0;
        for (int i = candidates.size() - 1; i >= 0; --i) {
            final T entity = candidates.get(i);
            final AABB box = entity.getBoundingBox();
            byId.put(entity.getId(), entity);
            snapshots[snapshotCount++] = new NativeEntityQuery.EntitySnapshot(
                    entity.getId(),
                    BuiltInRegistries.ENTITY_TYPE.getId(entity.getType()),
                    entity.getX(), entity.getY(), entity.getZ(),
                    box.minX, box.minY, box.minZ,
                    box.maxX, box.maxY, box.maxZ,
                    entity.isAlive(),
                    entity.isSpectator());
        }

        final int[] nearestIds = NativeEntityQuery.query(
                area.minX, area.minY, area.minZ,
                area.maxX, area.maxY, area.maxZ,
                snapshotCount == snapshots.length ? snapshots : Arrays.copyOf(snapshots, snapshotCount),
                null,
                predicateKind,
                source.getId(),
                true,
                snapshotCount,
                x, y, z);
        final List<T> sorted = new ArrayList<>(nearestIds.length);
        for (int id : nearestIds) {
            final T candidate = byId.get(id);
            if (candidate != null && filter.test(candidate)) {
                sorted.add(candidate);
            }
        }
        return sorted;
    }

    static @Nullable Player findNearestPlayer(EntityGetter level,
                                              double x,
                                              double y,
                                              double z,
                                              double range,
                                              Predicate<? super Player> filter) {
        final List<? extends Player> players = level.players();
        if (!NativeEntityQuery.isAvailable() || players.size() < MIN_NATIVE_CANDIDATES) {
            return lattice$findNearestPlayerFallback(players, x, y, z, range, filter);
        }

        final AABB area = AABB.ofSize(new net.minecraft.world.phys.Vec3(x, y, z), range * 2.0, range * 2.0, range * 2.0);
        final Map<Integer, Player> byId = new HashMap<>(players.size());
        final NativeEntityQuery.EntitySnapshot[] snapshots = new NativeEntityQuery.EntitySnapshot[players.size()];
        int snapshotCount = 0;
        for (int i = players.size() - 1; i >= 0; --i) {
            final Player player = players.get(i);
            final AABB box = player.getBoundingBox();
            byId.put(player.getId(), player);
            snapshots[snapshotCount++] = new NativeEntityQuery.EntitySnapshot(
                    player.getId(),
                    BuiltInRegistries.ENTITY_TYPE.getId(player.getType()),
                    player.getX(), player.getY(), player.getZ(),
                    box.minX, box.minY, box.minZ,
                    box.maxX, box.maxY, box.maxZ,
                    player.isAlive(),
                    player.isSpectator());
        }

        final int[] nearestIds = NativeEntityQuery.query(
                area.minX, area.minY, area.minZ,
                area.maxX, area.maxY, area.maxZ,
                snapshotCount == snapshots.length ? snapshots : Arrays.copyOf(snapshots, snapshotCount),
                null,
                NativeEntityQuery.PredicateKind.IS_ALIVE_NOT_SPEC,
                -1,
                true,
                snapshotCount,
                x, y, z);
        final double maxDistanceSq = range * range;
        for (int id : nearestIds) {
            final Player player = byId.get(id);
            if (player != null && player.distanceToSqr(x, y, z) < maxDistanceSq && filter.test(player)) {
                return player;
            }
        }
        return null;
    }

    private static <T extends LivingEntity> @Nullable T lattice$findNearestEntityFallback(ServerLevel level,
                                                                                           LivingEntity source,
                                                                                           List<? extends T> candidates,
                                                                                           TargetingConditions targetingConditions,
                                                                                           double x,
                                                                                           double y,
                                                                                           double z) {
        double nearestDistance = -1.0;
        T nearest = null;
        for (T candidate : candidates) {
            if (!targetingConditions.test(level, source, candidate)) continue;
            final double distance = candidate.distanceToSqr(x, y, z);
            if (nearestDistance == -1.0 || distance < nearestDistance) {
                nearestDistance = distance;
                nearest = candidate;
            }
        }
        return nearest;
    }

    private static <T extends LivingEntity> List<T> lattice$sortByDistanceFallback(List<? extends T> candidates,
                                                                                    double x,
                                                                                    double y,
                                                                                    double z,
                                                                                    Predicate<? super T> filter) {
        final List<T> sorted = new ArrayList<>(candidates.size());
        for (T candidate : candidates) {
            if (filter.test(candidate)) {
                sorted.add(candidate);
            }
        }
        sorted.sort(Comparator.comparingDouble(candidate -> candidate.distanceToSqr(x, y, z)));
        return sorted;
    }

    private static @Nullable Player lattice$findNearestPlayerFallback(List<? extends Player> players,
                                                                      double x,
                                                                      double y,
                                                                      double z,
                                                                      double range,
                                                                      Predicate<? super Player> filter) {
        final double maxDistanceSq = range * range;
        double nearestDistance = -1.0;
        Player nearest = null;
        for (Player player : players) {
            if (!filter.test(player)) continue;
            final double distance = player.distanceToSqr(x, y, z);
            if (distance >= maxDistanceSq) continue;
            if (nearestDistance == -1.0 || distance < nearestDistance) {
                nearestDistance = distance;
                nearest = player;
            }
        }
        return nearest;
    }
}
