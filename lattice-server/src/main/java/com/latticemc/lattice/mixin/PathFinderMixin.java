package com.latticemc.lattice.mixin;

import com.latticemc.lattice.nativelib.LatticeNative;
import com.latticemc.lattice.nativelib.NativePathfinder;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.NodeEvaluator;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathFinder;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.PathfindingContext;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PathFinder.class)
public abstract class PathFinderMixin {
    private static final int MIN_NATIVE_REGION_AXIS = 32;
    private static final int PATH_TYPE_STRIDE_MARGIN = 2;
    private static final PathType[] PATH_TYPES = PathType.values();
    private static final ThreadLocal<Boolean> VERIFY_SHADOW = ThreadLocal.withInitial(() -> false);
    private static final ThreadLocal<float[]> PATHFINDING_MALUS_BUFFER = ThreadLocal.withInitial(() -> new float[PATH_TYPES.length]);
    private static final ThreadLocal<PathfinderBuffers> PATHFINDER_BUFFERS = ThreadLocal.withInitial(PathfinderBuffers::new);

    @Shadow @Final public NodeEvaluator nodeEvaluator;
    @Shadow private int maxVisitedNodes;

    @Inject(method = "findPath", at = @At("HEAD"), cancellable = true)
    private void lattice$nativeFindPath(PathNavigationRegion region,
                                        Mob mob,
                                        Set<BlockPos> targets,
                                        float maxRange,
                                        int reachRange,
                                        float maxVisitedNodesMultiplier,
                                        CallbackInfoReturnable<Path> cir) {
        if (targets.isEmpty()
                || VERIFY_SHADOW.get()
                || !(this.nodeEvaluator instanceof WalkNodeEvaluator walkNodeEvaluator)
                || !NativePathfinder.isAvailable()) {
            return;
        }

        boolean prepared = false;
        try {
            this.nodeEvaluator.prepare(region, mob);
            prepared = true;
            Node start = this.nodeEvaluator.getStart();
            if (start == null) return;

            PathfinderBuffers buffers = PATHFINDER_BUFFERS.get();
            int targetCount = buffers.copyTargets(targets);
            NativePathfinder.recordAttempt();
            NativePathfinder.PathResult result = this.lattice$runNative(
                    region, mob, walkNodeEvaluator, start, buffers, targetCount, maxRange, reachRange, maxVisitedNodesMultiplier);
            if (result == null || result.length() == 0 || result.targetIndex() < 0 || result.targetIndex() >= targetCount) {
                NativePathfinder.recordFallback();
                return;
            }

            Path path = this.lattice$toPath(result, buffers.target(result.targetIndex()));
            this.nodeEvaluator.done();
            prepared = false;

            if (LatticeNative.VERIFY && !this.lattice$matchesVanilla(region, mob, targets, maxRange, reachRange, maxVisitedNodesMultiplier, path)) {
                NativePathfinder.recordVerifyMismatch();
                throw new AssertionError("lattice.verify: native pathfinder mismatch");
            }

            NativePathfinder.recordSuccess();
            cir.setReturnValue(path);
        } catch (Throwable throwable) {
            NativePathfinder.recordFallback();
            LatticeNative.logFallbackOnce("native_pathfinder", throwable.getMessage());
        } finally {
            if (prepared) {
                this.nodeEvaluator.done();
            }
        }
    }

    private NativePathfinder.PathResult lattice$runNative(PathNavigationRegion region,
                                                          Mob mob,
                                                          WalkNodeEvaluator evaluator,
                                                          Node start,
                                                          PathfinderBuffers buffers,
                                                          int targetCount,
                                                          float maxRange,
                                                          int reachRange,
                                                          float maxVisitedNodesMultiplier) {
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
        if (Math.max(sizeX, sizeZ) < MIN_NATIVE_REGION_AXIS) return null;

        int volume = Math.multiplyExact(Math.multiplyExact(sizeX, sizeY), sizeZ);
        byte[] pathTypes = buffers.pathTypes(volume);
        PathfindingContext context = new PathfindingContext(region, mob);
        long precomputeStart = System.nanoTime();
        for (int y = minY; y <= maxY; ++y) {
            for (int z = minZ; z <= maxZ; ++z) {
                for (int x = minX; x <= maxX; ++x) {
                    PathType type = evaluator.getPathTypeOfMob(context, x, y, z, mob);
                    if (!this.lattice$isNativePathTypeSupported(evaluator, type)) {
                        NativePathfinder.recordPrecomputeNanos(System.nanoTime() - precomputeStart);
                        return null;
                    }
                    int index = ((y - minY) * sizeZ + (z - minZ)) * sizeX + (x - minX);
                    pathTypes[index] = (byte)type.ordinal();
                }
            }
        }
        NativePathfinder.recordPrecomputeNanos(System.nanoTime() - precomputeStart);

        int[] targetX = buffers.targetX(targetCount);
        int[] targetY = buffers.targetY(targetCount);
        int[] targetZ = buffers.targetZ(targetCount);
        int maxVisitedNodes = (int)(this.maxVisitedNodes * maxVisitedNodesMultiplier);
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
                    mob.getMaxFallDistance(), this.lattice$pathfindingMalusFor(mob), outPath);
        } finally {
            NativePathfinder.recordNativeNanos(System.nanoTime() - nativeStart);
        }
    }

    private float[] lattice$pathfindingMalusFor(Mob mob) {
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

    private boolean lattice$isNativePathTypeSupported(WalkNodeEvaluator evaluator, PathType type) {
        return type == PathType.BLOCKED
                || type == PathType.OPEN
                || type == PathType.WALKABLE
                || type == PathType.DOOR_OPEN
                || type == PathType.WALKABLE_DOOR
                || type == PathType.COCOA
                || (type == PathType.WATER && evaluator.canFloat());
    }

    private boolean lattice$matchesVanilla(PathNavigationRegion region,
                                           Mob mob,
                                           Set<BlockPos> targets,
                                           float maxRange,
                                           int reachRange,
                                           float maxVisitedNodesMultiplier,
                                           Path nativePath) {
        VERIFY_SHADOW.set(true);
        try {
            Path vanillaPath = ((PathFinder)(Object)this).findPath(region, mob, targets, maxRange, reachRange, maxVisitedNodesMultiplier);
            return this.lattice$samePath(nativePath, vanillaPath);
        } finally {
            VERIFY_SHADOW.set(false);
        }
    }

    private boolean lattice$samePath(Path nativePath, Path vanillaPath) {
        if (nativePath == vanillaPath) return true;
        if (nativePath == null || vanillaPath == null) return false;
        if (nativePath.canReach() != vanillaPath.canReach()) return false;
        if (!nativePath.getTarget().equals(vanillaPath.getTarget())) return false;
        if (nativePath.getNodeCount() != vanillaPath.getNodeCount()) return false;
        for (int i = 0; i < nativePath.getNodeCount(); ++i) {
            Node nativeNode = nativePath.getNode(i);
            Node vanillaNode = vanillaPath.getNode(i);
            if (nativeNode.x != vanillaNode.x || nativeNode.y != vanillaNode.y || nativeNode.z != vanillaNode.z) {
                return false;
            }
        }
        return true;
    }

    private Path lattice$toPath(NativePathfinder.PathResult result, BlockPos target) {
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
