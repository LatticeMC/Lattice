package com.latticemc.lattice.bridge;

import com.latticemc.lattice.nativelib.LatticeNative;
import com.latticemc.lattice.nativelib.NativePathfinder;
import com.latticemc.lattice.nativelib.PathfinderJfrEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathFinder;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.PathfindingContext;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import org.jspecify.annotations.Nullable;

public final class PathFinderNativeSupport {
    private static final int MIN_NATIVE_REGION_AXIS = 32;
    private static final int PATH_TYPE_STRIDE_MARGIN = 2;
    private static final PathType[] PATH_TYPES = PathType.values();
    private static final ThreadLocal<Boolean> VERIFY_SHADOW = ThreadLocal.withInitial(() -> false);
    private static final ThreadLocal<float[]> PATHFINDING_MALUS_BUFFER = ThreadLocal.withInitial(() -> new float[PATH_TYPES.length]);
    private static final ThreadLocal<PathfinderBuffers> PATHFINDER_BUFFERS = ThreadLocal.withInitial(PathfinderBuffers::new);

    private PathFinderNativeSupport() {}

    public static @Nullable Path tryFindPath(PathFinder pathFinder,
                                             int maxVisitedNodes,
                                             PathNavigationRegion region,
                                             Mob mob,
                                             Set<BlockPos> targets,
                                             float maxRange,
                                             int reachRange,
                                             float maxVisitedNodesMultiplier) {
        if (targets.isEmpty()
                || VERIFY_SHADOW.get()
                || !(pathFinder.nodeEvaluator instanceof WalkNodeEvaluator walkNodeEvaluator)
                || !NativePathfinder.isAvailable()) {
            return null;
        }

        boolean prepared = false;
        boolean attempted = false;
        boolean nativeAccepted = false;
        boolean targetReached = false;
        int pathLength = 0;
        int targetCount = targets.size();
        long totalStart = System.nanoTime();
        PathfinderJfrEvent jfrEvent = PathfinderJfrEvent.begin(targetCount, maxRange);
        try {
            pathFinder.nodeEvaluator.prepare(region, mob);
            prepared = true;
            Node start = pathFinder.nodeEvaluator.getStart();
            if (start == null) return null;

            PathfinderBuffers buffers = PATHFINDER_BUFFERS.get();
            targetCount = buffers.copyTargets(targets);
            NativePathfinder.recordAttempt();
            attempted = true;
            NativePathfinder.PathResult result = runNative(
                    maxVisitedNodes, region, mob, walkNodeEvaluator, start, buffers, targetCount, maxRange, reachRange,
                    maxVisitedNodesMultiplier, jfrEvent);
            if (result == null || result.length() == 0 || result.targetIndex() < 0 || result.targetIndex() >= targetCount) {
                if (result != null) NativePathfinder.recordEmptyResult();
                NativePathfinder.recordFallback();
                return null;
            }

            Path path = toPath(result, buffers.target(result.targetIndex()));
            pathLength = result.length();
            targetReached = result.reachedTarget();
            pathFinder.nodeEvaluator.done();
            prepared = false;

            String mismatch = LatticeNative.VERIFY
                    ? mismatchWithVanilla(pathFinder, region, mob, targets, maxRange, reachRange, maxVisitedNodesMultiplier, path)
                    : null;
            if (mismatch != null) {
                NativePathfinder.recordVerifyMismatch();
                throw new AssertionError("lattice.verify: native pathfinder mismatch: " + mismatch);
            }

            NativePathfinder.recordSuccess();
            nativeAccepted = true;
            return path;
        } catch (Throwable throwable) {
            NativePathfinder.recordFallback();
            LatticeNative.logFallbackOnce("native_pathfinder", throwable.getMessage());
            return null;
        } finally {
            if (prepared) {
                pathFinder.nodeEvaluator.done();
            }
            if (attempted) {
                long totalNanos = System.nanoTime() - totalStart;
                NativePathfinder.recordCompletedRequest(totalNanos);
                if (jfrEvent != null) {
                    jfrEvent.finish(totalNanos, nativeAccepted, targetReached, pathLength);
                }
            }
        }
    }

    private static NativePathfinder.PathResult runNative(int configuredMaxVisitedNodes,
                                                         PathNavigationRegion region,
                                                         Mob mob,
                                                         WalkNodeEvaluator evaluator,
                                                         Node start,
                                                         PathfinderBuffers buffers,
                                                         int targetCount,
                                                         float maxRange,
                                                         int reachRange,
                                                         float maxVisitedNodesMultiplier,
                                                         PathfinderJfrEvent jfrEvent) {
        int margin = Mth.ceil(maxRange) + PATH_TYPE_STRIDE_MARGIN;
        int minX = start.x;
        int maxX = start.x;
        int minY = start.y;
        int maxY = start.y;
        int minZ = start.z;
        int maxZ = start.z;
        for (int i = 0; i < targetCount; ++i) {
            BlockPos target = buffers.target(i);
            minX = Math.min(minX, target.getX());
            maxX = Math.max(maxX, target.getX());
            minY = Math.min(minY, target.getY());
            maxY = Math.max(maxY, target.getY());
            minZ = Math.min(minZ, target.getZ());
            maxZ = Math.max(maxZ, target.getZ());
        }

        minX -= margin;
        maxX += margin;
        minZ -= margin;
        maxZ += margin;
        minY = Math.max(region.getMinY(), minY - mob.getMaxFallDistance() - PATH_TYPE_STRIDE_MARGIN);
        maxY = Math.min(region.getMinY() + region.getHeight() - 1,
                maxY + Mth.floor(Math.max(1.0F, mob.maxUpStep())) + PATH_TYPE_STRIDE_MARGIN);

        int sizeX = maxX - minX + 1;
        int sizeY = maxY - minY + 1;
        int sizeZ = maxZ - minZ + 1;
        if (Math.max(sizeX, sizeZ) < MIN_NATIVE_REGION_AXIS) {
            NativePathfinder.recordRegionTooSmall();
            return null;
        }

        int volume = Math.multiplyExact(Math.multiplyExact(sizeX, sizeY), sizeZ);
        byte[] pathTypes = buffers.pathTypes(volume);
        float[] pathfindingMalus = pathfindingMalusFor(mob);
        boolean canFloat = evaluator.canFloat();
        PathfindingContext context = new PathfindingContext(region, mob);
        long precomputeStart = System.nanoTime();
        try {
            int entityWidth = Mth.floor(mob.getBbWidth() + 1.0F);
            int entityHeight = Mth.floor(mob.getBbHeight() + 1.0F);
            PathType unsupported = entityWidth == 1
                    ? fillSingleWidthPathTypes(evaluator, context, mob, buffers, pathTypes, pathfindingMalus, canFloat,
                            minX, minY, minZ, sizeX, sizeY, sizeZ, entityHeight)
                    : fillPathTypes(evaluator, context, mob, pathTypes, pathfindingMalus, canFloat,
                            minX, minY, minZ, sizeX, sizeY, sizeZ);
            if (unsupported != null) {
                NativePathfinder.recordUnsupportedPathType(unsupported);
                return null;
            }
        } finally {
            long precomputeNanos = System.nanoTime() - precomputeStart;
            NativePathfinder.recordPrecomputeNanos(precomputeNanos);
            if (jfrEvent != null) jfrEvent.recordPrecompute(precomputeNanos);
        }

        int[] targetX = buffers.targetX(targetCount);
        int[] targetY = buffers.targetY(targetCount);
        int[] targetZ = buffers.targetZ(targetCount);
        int maxVisitedNodes = (int)(configuredMaxVisitedNodes * maxVisitedNodesMultiplier);
        int[] outPath = buffers.outPath(maxVisitedNodes);
        for (int i = 0; i < targetCount; ++i) {
            BlockPos target = buffers.target(i);
            targetX[i] = target.getX();
            targetY[i] = target.getY();
            targetZ[i] = target.getZ();
        }

        long nativeStart = System.nanoTime();
        try {
            return NativePathfinder.findPath(pathTypes,
                    minX, minY, minZ, sizeX, sizeY, sizeZ,
                    start.x, start.y, start.z,
                    targetX, targetY, targetZ, targetCount,
                    maxRange, maxVisitedNodes, reachRange,
                    Mth.floor(mob.getBbWidth() + 1.0F), Mth.floor(mob.getBbHeight() + 1.0F), mob.maxUpStep(),
                    mob.getMaxFallDistance(), pathfindingMalus, outPath);
        } finally {
            long nativeNanos = System.nanoTime() - nativeStart;
            NativePathfinder.recordNativeNanos(nativeNanos);
            if (jfrEvent != null) jfrEvent.recordNative(nativeNanos);
        }
    }

    private static @Nullable PathType fillPathTypes(WalkNodeEvaluator evaluator,
                                                     PathfindingContext context,
                                                     Mob mob,
                                                     byte[] output,
                                                     float[] pathfindingMalus,
                                                     boolean canFloat,
                                                     int minX,
                                                     int minY,
                                                     int minZ,
                                                     int sizeX,
                                                     int sizeY,
                                                     int sizeZ) {
        for (int localY = 0; localY < sizeY; ++localY) {
            int y = minY + localY;
            for (int localZ = 0; localZ < sizeZ; ++localZ) {
                int z = minZ + localZ;
                for (int localX = 0; localX < sizeX; ++localX) {
                    int x = minX + localX;
                    PathType type = evaluator.getPathTypeOfMob(context, x, y, z, mob);
                    if (!isNativePathTypeSupported(type, pathfindingMalus[type.ordinal()], canFloat)) {
                        return type;
                    }
                    output[(localY * sizeZ + localZ) * sizeX + localX] = (byte)type.ordinal();
                }
            }
        }
        return null;
    }

    private static @Nullable PathType fillSingleWidthPathTypes(WalkNodeEvaluator evaluator,
                                                                PathfindingContext context,
                                                                Mob mob,
                                                                PathfinderBuffers buffers,
                                                                byte[] output,
                                                                float[] pathfindingMalus,
                                                                boolean canFloat,
                                                                int minX,
                                                                int minY,
                                                                int minZ,
                                                                int sizeX,
                                                                int sizeY,
                                                                int sizeZ,
                                                                int entityHeight) {
        int[] column = buffers.pathTypeColumn(entityHeight);
        for (int localZ = 0; localZ < sizeZ; ++localZ) {
            int z = minZ + localZ;
            for (int localX = 0; localX < sizeX; ++localX) {
                int x = minX + localX;
                for (int layer = 0; layer < entityHeight; ++layer) {
                    column[layer] = pathTypeLayer(evaluator, context, x, minY + layer, z);
                }
                for (int localY = 0; localY < sizeY; ++localY) {
                    long types = 0L;
                    for (int layer = 0; layer < entityHeight; ++layer) {
                        types |= 1L << (column[layer] & 0xFF);
                    }
                    PathType type = selectPathType(types, rawType(column[localY % entityHeight]), pathfindingMalus);
                    if (LatticeNative.VERIFY) {
                        PathType vanilla = evaluator.getPathTypeOfMob(context, x, minY + localY, z, mob);
                        if (type != vanilla) {
                            throw new AssertionError("path type snapshot mismatch at " + x + ',' + (minY + localY) + ',' + z
                                    + ": fast=" + type + " vanilla=" + vanilla);
                        }
                    }
                    if (!isNativePathTypeSupported(type, pathfindingMalus[type.ordinal()], canFloat)) {
                        return type;
                    }
                    output[(localY * sizeZ + localZ) * sizeX + localX] = (byte)type.ordinal();
                    if (localY + 1 < sizeY) {
                        int slot = localY % entityHeight;
                        column[slot] = pathTypeLayer(evaluator, context, x, minY + localY + entityHeight, z);
                    }
                }
            }
        }
        return null;
    }

    private static int pathTypeLayer(WalkNodeEvaluator evaluator,
                                     PathfindingContext context,
                                     int x,
                                     int y,
                                     int z) {
        PathType raw = evaluator.getPathType(context, x, y, z);
        PathType adjusted = raw;
        if (raw == PathType.DOOR_WOOD_CLOSED && evaluator.canOpenDoors() && evaluator.canPassDoors()) {
            adjusted = PathType.WALKABLE_DOOR;
        } else if (raw == PathType.DOOR_OPEN && !evaluator.canPassDoors()) {
            adjusted = PathType.BLOCKED;
        } else if (raw == PathType.RAIL) {
            BlockPos mobPosition = context.mobPosition();
            if (evaluator.getPathType(context, mobPosition.getX(), mobPosition.getY(), mobPosition.getZ()) != PathType.RAIL
                    && evaluator.getPathType(context, mobPosition.getX(), mobPosition.getY() - 1, mobPosition.getZ()) != PathType.RAIL) {
                adjusted = PathType.UNPASSABLE_RAIL;
            }
        }
        return raw.ordinal() << 8 | adjusted.ordinal();
    }

    private static PathType rawType(int encoded) {
        return PATH_TYPES[encoded >>> 8];
    }

    static PathType selectPathType(long types, PathType bottomRawType, float[] pathfindingMalus) {
        if ((types & 1L << PathType.FENCE.ordinal()) != 0L) return PathType.FENCE;
        if ((types & 1L << PathType.UNPASSABLE_RAIL.ordinal()) != 0L) return PathType.UNPASSABLE_RAIL;

        PathType selected = PathType.BLOCKED;
        for (PathType type : PATH_TYPES) {
            if ((types & 1L << type.ordinal()) == 0L) continue;
            float malus = pathfindingMalus[type.ordinal()];
            if (malus < 0.0F) return type;
            if (malus >= pathfindingMalus[selected.ordinal()]) selected = type;
        }
        return selected != PathType.OPEN
                        && pathfindingMalus[selected.ordinal()] == 0.0F
                        && bottomRawType == PathType.OPEN
                ? PathType.OPEN
                : selected;
    }

    private static float[] pathfindingMalusFor(Mob mob) {
        float[] malus = PATHFINDING_MALUS_BUFFER.get();
        if (malus.length < PATH_TYPES.length) {
            malus = new float[PATH_TYPES.length];
            PATHFINDING_MALUS_BUFFER.set(malus);
        }
        for (int i = 0; i < PATH_TYPES.length; ++i) {
            malus[i] = mob.getPathfindingMalus(PATH_TYPES[i]);
        }
        return malus;
    }

    static boolean isNativePathTypeSupported(PathType type, float malus, boolean canFloat) {
        return malus < 0.0F
                || type == PathType.BLOCKED
                || type == PathType.OPEN
                || type == PathType.WALKABLE
                || type == PathType.DOOR_OPEN
                || type == PathType.WALKABLE_DOOR
                || type == PathType.COCOA
                || (type == PathType.WATER && canFloat);
    }

    private static @Nullable String mismatchWithVanilla(PathFinder pathFinder,
                                                         PathNavigationRegion region,
                                                         Mob mob,
                                                         Set<BlockPos> targets,
                                                         float maxRange,
                                                         int reachRange,
                                                         float maxVisitedNodesMultiplier,
                                                         Path nativePath) {
        VERIFY_SHADOW.set(true);
        try {
            Path vanillaPath = pathFinder.findPath(region, mob, targets, maxRange, reachRange, maxVisitedNodesMultiplier);
            return pathMismatch(nativePath, vanillaPath);
        } finally {
            VERIFY_SHADOW.set(false);
        }
    }

    static @Nullable String pathMismatch(Path nativePath, Path vanillaPath) {
        if (nativePath == vanillaPath) return null;
        if (nativePath == null || vanillaPath == null) return "one path is null";
        if (nativePath.canReach() != vanillaPath.canReach()) {
            return "reached native=" + nativePath.canReach() + " vanilla=" + vanillaPath.canReach();
        }
        if (!nativePath.getTarget().equals(vanillaPath.getTarget())) {
            return "target native=" + nativePath.getTarget() + " vanilla=" + vanillaPath.getTarget();
        }
        if (nativePath.getNodeCount() != vanillaPath.getNodeCount()) {
            return "length native=" + nativePath.getNodeCount() + " vanilla=" + vanillaPath.getNodeCount();
        }
        for (int i = 0; i < nativePath.getNodeCount(); ++i) {
            Node nativeNode = nativePath.getNode(i);
            Node vanillaNode = vanillaPath.getNode(i);
            if (nativeNode.x != vanillaNode.x || nativeNode.y != vanillaNode.y || nativeNode.z != vanillaNode.z) {
                return "node[" + i + "] native=" + nativeNode.x + ',' + nativeNode.y + ',' + nativeNode.z
                        + " vanilla=" + vanillaNode.x + ',' + vanillaNode.y + ',' + vanillaNode.z;
            }
        }
        return null;
    }

    private static Path toPath(NativePathfinder.PathResult result, BlockPos target) {
        List<Node> nodes = new ArrayList<>(result.length());
        Node previous = null;
        for (int i = 0; i < result.length(); ++i) {
            Node node = new Node(result.x(i), result.y(i), result.z(i));
            node.type = PathType.WALKABLE;
            node.costMalus = 0.0F;
            node.cameFrom = previous;
            nodes.add(node);
            previous = node;
        }
        return new Path(nodes, target, result.reachedTarget());
    }
}
