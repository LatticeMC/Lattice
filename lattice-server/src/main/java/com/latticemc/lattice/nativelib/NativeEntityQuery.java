package com.latticemc.lattice.nativelib;

import java.util.Arrays;

public final class NativeEntityQuery {
    public static final int POSITION_STRIDE = 3;
    public static final int AABB_STRIDE = 6;

    private NativeEntityQuery() {}

    public record EntitySnapshot(
            int id,
            int typeId,
            double x, double y, double z,
            double bbMinX, double bbMinY, double bbMinZ,
            double bbMaxX, double bbMaxY, double bbMaxZ,
            boolean isAlive,
            boolean isSpectator) {}

    public enum PredicateKind {
        NONE(0),
        IS_ALIVE(1),
        IS_ALIVE_NOT_SELF(2),
        IS_ALIVE_NOT_SPEC(3),
        IS_ALIVE_NOT_SELF_NOT_SPEC(4);

        private final int nativeId;

        PredicateKind(int nativeId) {
            this.nativeId = nativeId;
        }
    }

    public static boolean isAvailable() {
        LatticeNative.ensureLoaded();
        return LatticeNative.isLoaded();
    }

    public static int[] query(double queryMinX, double queryMinY, double queryMinZ,
                              double queryMaxX, double queryMaxY, double queryMaxZ,
                              EntitySnapshot[] entities,
                              int[] allowedTypeIds,
                              PredicateKind predicateKind,
                              int excludedEntityId,
                              boolean sortByDistance,
                              int maxResults,
                              double refX, double refY, double refZ) {
        if (entities == null) throw new IllegalArgumentException("null entities");
        if (predicateKind == null) throw new IllegalArgumentException("null predicate kind");
        if (maxResults < 0) throw new IllegalArgumentException("negative max results");
        if (entities.length == 0) return new int[0];

        final int outputCapacity = maxResults > 0 ? Math.min(maxResults, entities.length) : entities.length;
        if (outputCapacity == 0) return new int[0];

        final int[] entityIds = new int[entities.length];
        final int[] entityTypeIds = new int[entities.length];
        final double[] positions = new double[entities.length * POSITION_STRIDE];
        final double[] aabbs = new double[entities.length * AABB_STRIDE];
        final boolean[] alive = new boolean[entities.length];
        final boolean[] spectator = new boolean[entities.length];
        for (int i = 0; i < entities.length; ++i) {
            final EntitySnapshot entity = entities[i];
            entityIds[i] = entity.id();
            entityTypeIds[i] = entity.typeId();
            int positionBase = i * POSITION_STRIDE;
            positions[positionBase] = entity.x();
            positions[positionBase + 1] = entity.y();
            positions[positionBase + 2] = entity.z();
            int boxBase = i * AABB_STRIDE;
            aabbs[boxBase] = entity.bbMinX();
            aabbs[boxBase + 1] = entity.bbMinY();
            aabbs[boxBase + 2] = entity.bbMinZ();
            aabbs[boxBase + 3] = entity.bbMaxX();
            aabbs[boxBase + 4] = entity.bbMaxY();
            aabbs[boxBase + 5] = entity.bbMaxZ();
            alive[i] = entity.isAlive();
            spectator[i] = entity.isSpectator();
        }

        final int[] outputIds = new int[outputCapacity];
        final double[] outputDistances = sortByDistance ? new double[outputCapacity * 2] : null;
        final int count;
        if (LatticeNative.isLoaded()) {
            count = nativeQuery(
                    queryMinX, queryMinY, queryMinZ,
                    queryMaxX, queryMaxY, queryMaxZ,
                    entityIds, entityTypeIds, positions, aabbs, alive, spectator,
                    allowedTypeIds,
                    predicateKind.nativeId, excludedEntityId,
                    sortByDistance, maxResults,
                    refX, refY, refZ,
                    outputIds, outputDistances);
        } else {
            LatticeNative.logFallbackOnce("entity_query", "native entity query unavailable");
            count = javaQuery(
                    queryMinX, queryMinY, queryMinZ,
                    queryMaxX, queryMaxY, queryMaxZ,
                    entityIds, entityTypeIds, positions, aabbs, alive, spectator,
                    allowedTypeIds, predicateKind, excludedEntityId,
                    sortByDistance, maxResults,
                    refX, refY, refZ,
                    outputIds, outputDistances);
        }
        return count == outputIds.length ? outputIds : Arrays.copyOf(outputIds, count);
    }

    public static int javaQuery(double queryMinX, double queryMinY, double queryMinZ,
                                double queryMaxX, double queryMaxY, double queryMaxZ,
                                int[] entityIds, int[] entityTypeIds,
                                double[] positions, double[] aabbs,
                                boolean[] alive, boolean[] spectator,
                                int[] allowedTypeIds,
                                PredicateKind predicateKind,
                                int excludedEntityId,
                                boolean sortByDistance,
                                int maxResults,
                                double refX, double refY, double refZ,
                                int[] outputIds,
                                double[] outputDistances) {
        int count = 0;
        final int limit = maxResults > 0 ? Math.min(maxResults, outputIds.length) : outputIds.length;
        for (int i = 0; i < entityIds.length; ++i) {
            if (!typeAllowed(entityTypeIds[i], allowedTypeIds)) continue;
            if (!predicateAllowed(entityIds[i], alive[i], spectator[i], predicateKind, excludedEntityId)) continue;
            int boxBase = i * AABB_STRIDE;
            boolean overlap = queryMinX <= aabbs[boxBase + 3] && queryMaxX >= aabbs[boxBase]
                    && queryMinY <= aabbs[boxBase + 4] && queryMaxY >= aabbs[boxBase + 1]
                    && queryMinZ <= aabbs[boxBase + 5] && queryMaxZ >= aabbs[boxBase + 2];
            if (!overlap) continue;
            double distance = 0.0;
            if (sortByDistance) {
                int positionBase = i * POSITION_STRIDE;
                double dx = positions[positionBase] - refX;
                double dy = positions[positionBase + 1] - refY;
                double dz = positions[positionBase + 2] - refZ;
                distance = dx * dx + dy * dy + dz * dz;
            }
            if (!sortByDistance) {
                if (count >= outputIds.length) return count;
                outputIds[count++] = entityIds[i];
                continue;
            }
            if (count < limit) {
                outputIds[count] = entityIds[i];
                outputDistances[count++] = distance;
                outputDistances[outputIds.length + count - 1] = i;
                continue;
            }
            int farthest = 0;
            for (int j = 1; j < count; ++j) {
                if (outputDistances[j] > outputDistances[farthest]
                        || outputDistances[j] == outputDistances[farthest]
                        && outputDistances[outputIds.length + j] < outputDistances[outputIds.length + farthest]) {
                    farthest = j;
                }
            }
            if (distance < outputDistances[farthest]
                    || distance == outputDistances[farthest] && i > outputDistances[outputIds.length + farthest]) {
                outputIds[farthest] = entityIds[i];
                outputDistances[farthest] = distance;
                outputDistances[outputIds.length + farthest] = i;
            }
        }
        if (sortByDistance) {
            for (int i = 1; i < count; ++i) {
                int id = outputIds[i];
                double distance = outputDistances[i];
                double ordinal = outputDistances[outputIds.length + i];
                int j = i - 1;
                while (j >= 0 && (outputDistances[j] > distance
                        || outputDistances[j] == distance && outputDistances[outputIds.length + j] < ordinal)) {
                    outputIds[j + 1] = outputIds[j];
                    outputDistances[j + 1] = outputDistances[j];
                    outputDistances[outputIds.length + j + 1] = outputDistances[outputIds.length + j];
                    --j;
                }
                outputIds[j + 1] = id;
                outputDistances[j + 1] = distance;
                outputDistances[outputIds.length + j + 1] = ordinal;
            }
        }
        return count;
    }

    private static boolean typeAllowed(int typeId, int[] allowedTypeIds) {
        if (allowedTypeIds == null || allowedTypeIds.length == 0) return true;
        for (int allowed : allowedTypeIds) {
            if (allowed == typeId) return true;
        }
        return false;
    }

    private static boolean predicateAllowed(int entityId, boolean alive, boolean spectator,
                                            PredicateKind predicateKind, int excludedEntityId) {
        return switch (predicateKind) {
            case NONE -> true;
            case IS_ALIVE -> alive;
            case IS_ALIVE_NOT_SELF -> alive && entityId != excludedEntityId;
            case IS_ALIVE_NOT_SPEC -> alive && !spectator;
            case IS_ALIVE_NOT_SELF_NOT_SPEC -> alive && entityId != excludedEntityId && !spectator;
        };
    }

    private static native int nativeQuery(
            double queryMinX, double queryMinY, double queryMinZ,
            double queryMaxX, double queryMaxY, double queryMaxZ,
            int[] entityIds,
            int[] entityTypeIds,
            double[] entityPositions,
            double[] entityBoundingBoxes,
            boolean[] entityAlive,
            boolean[] entitySpectator,
            int[] allowedTypeIds,
            int predicateKind,
            int excludedEntityId,
            boolean sortByDistance,
            int maxResults,
            double refX, double refY, double refZ,
            int[] outputIds,
            double[] outputDistances);
}
