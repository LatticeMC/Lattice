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
    /// Cheap pre-filter in front of the coverage probe: a box this small cannot repay
    /// the fixed cost of the JNI round trip and the lazy-grid setup even on a mirror
    /// hit. Kept separate from MIN_NATIVE_REGION_AXIS, which bounds a single axis.
    private static final int MIN_NATIVE_REGION_VOLUME = Integer.getInteger("lattice.pathfinderMinNativeVolume", 8192);
    /// First gate, and the only one that protects the short-stroll case.
    ///
    /// Measured at 150 zombies on a 60x15x60 box: Java costs ~130-148us for these
    /// requests, a mirror hit ~110us, and a mirror miss (snapshot upload) 3000-7500us.
    /// The coverage probe below is exact, but it cannot fix this case, because the
    /// mirror only gets populated by misses: warming it costs more than every request
    /// it would ever accelerate. So short paths must not reach native at all.
    private static final int SHORT_PATH_MANHATTAN_LIMIT = Integer.getInteger("lattice.pathfinderShortJavaGate", 24);
    /// Uploads allowed per tick per thread when the mirror does not yet cover a box.
    ///
    /// Only the miss path calls store_pathfinder_state_snapshot, so a coverage gate with
    /// no escape hatch would leave the mirror permanently empty and the gate permanently
    /// closed. Bounding the uploads instead caps warm-up at roughly one snapshot upload
    /// per tick per thread while still letting coverage grow.
    private static final int MIRROR_WARMUP_UPLOADS_PER_TICK =
            Integer.getInteger("lattice.pathfinderMirrorWarmupPerTick", 1);
    private static final PathType[] PATH_TYPES = PathType.values();
    private static final ThreadLocal<Boolean> VERIFY_SHADOW = ThreadLocal.withInitial(() -> false);
    private static final ThreadLocal<float[]> PATHFINDING_MALUS_BUFFER = ThreadLocal.withInitial(() -> new float[PATH_TYPES.length]);
    private static final ThreadLocal<PathfinderBuffers> PATHFINDER_BUFFERS = ThreadLocal.withInitial(PathfinderBuffers::new);
    /// {game tick, uploads already claimed in that tick} for this thread. The mirror is
    /// thread-local on the native side, so the budget is too.
    private static final ThreadLocal<long[]> WARMUP_BUDGET =
            ThreadLocal.withInitial(() -> new long[] {Long.MIN_VALUE, 0L});

    private PathFinderNativeSupport() {}

    public static boolean isVerifyShadow() {
        return VERIFY_SHADOW.get();
    }

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
        if (isShortPath(mob, targets)) {
            NativePathfinder.recordShortPathGate();
            return null;
        }
        boolean prepared = false;
        boolean attempted = false;
        boolean nativeAccepted = false;
        boolean targetReached = false;
        int pathLength = 0;
        int targetCount = targets.size();
        PathfinderJfrEvent jfrEvent = PathfinderJfrEvent.begin(targetCount, maxRange);
        // Exclude creation of our diagnostic event from the native-versus-
        // vanilla comparison. Vanilla's shadow call has no matching event.
        long totalStart = System.nanoTime();
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
            NativePathfinder.recordComparableNativeNanos(System.nanoTime() - totalStart);

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

        int entityWidth = Mth.floor(mob.getBbWidth() + 1.0F);
        int entityHeight = Mth.floor(mob.getBbHeight() + 1.0F);
        int worldKey = System.identityHashCode(mob.level());
        if (!shouldTryNative(mob, worldKey, minX, minY, minZ, sizeX, sizeY, sizeZ, entityWidth, entityHeight)) {
            NativePathfinder.recordMirrorColdGate();
            return null;
        }

        float[] pathfindingMalus = pathfindingMalusFor(mob);
        boolean canFloat = evaluator.canFloat();
        boolean isAmphibious = evaluator instanceof net.minecraft.world.level.pathfinder.AmphibiousNodeEvaluator;
        boolean canPassDoors = evaluator.canPassDoors();
        boolean canOpenDoors = evaluator.canOpenDoors();
        boolean canWalkOverFences = evaluator.canWalkOverFences();
        boolean mobsIgnoreRails = mob.level().purpurConfig.mobsIgnoreRails;
        float maxUpStep = mob.maxUpStep();
        float mobJumpHeight = (float)Math.max(1.125D, maxUpStep);
        BlockPos mobBlockPosition = mob.blockPosition();
        int levelMinY = region.getMinY();
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
            NativePathfinder.PathResult mirrorResult = NativePathfinder.findPathFromStateMirror(
                    worldKey, minX, minY, minZ, sizeX, sizeY, sizeZ,
                    start.x, start.y, start.z, targetX, targetY, targetZ, targetCount,
                    maxRange, maxVisitedNodes, reachRange, entityWidth, entityHeight, maxUpStep,
                    mob.getMaxFallDistance(), pathfindingMalus,
                    mobJumpHeight, mob.getBbWidth(), canPassDoors, canOpenDoors,
                    mobBlockPosition.getX(), mobBlockPosition.getY(), mobBlockPosition.getZ(),
                    canWalkOverFences, mobsIgnoreRails, canFloat, isAmphibious, levelMinY, outPath);
            if (mirrorResult != null) {
                NativePathfinder.recordStateMirrorHit();
                return mirrorResult;
            }
            NativePathfinder.recordStateMirrorMiss();
        } finally {
            long nativeNanos = System.nanoTime() - nativeStart;
            NativePathfinder.recordNativeNanos(nativeNanos);
            NativePathfinder.recordStateMirrorNativeNanos(nativeNanos);
            if (jfrEvent != null) jfrEvent.recordNative(nativeNanos);
        }

        PathfinderStateSnapshot stateSnapshot = buffers.stateSnapshot();
        PathfinderTickStateCache tickStateCache = buffers.tickStateCache();
        tickStateCache.begin(mob.level(), mob.level().getGameTime());
        long cacheHits = tickStateCache.hits();
        long cacheMisses = tickStateCache.misses();
        long stateSnapshotStart = System.nanoTime();
        boolean supported = stateSnapshot.fill(region, tickStateCache,
                minX - 1, minY - 1, minZ - 1,
                sizeX + entityWidth + 1, sizeY + entityHeight + 1, sizeZ + entityWidth + 1);
        long stateSnapshotNanos = System.nanoTime() - stateSnapshotStart;
        NativePathfinder.recordStateSnapshot(stateSnapshotNanos,
                stateSnapshot.cellCount(), stateSnapshot.descriptorCount(), supported);
        NativePathfinder.recordStateSnapshotCache(tickStateCache.hits() - cacheHits,
                tickStateCache.misses() - cacheMisses);
        NativePathfinder.recordPrecomputeNanos(stateSnapshotNanos);
        if (jfrEvent != null) jfrEvent.recordPrecompute(stateSnapshotNanos);
        if (!supported) return null;

        NativePathfinder.recordStateMirrorUpload();
        nativeStart = System.nanoTime();
        try {
            return NativePathfinder.findPathFromStateSnapshot(
                    stateSnapshot.cells(), stateSnapshot.rawPathTypes(), stateSnapshot.floorHeights(),
                    stateSnapshot.descriptorCount(),
                    stateSnapshot.minX(), stateSnapshot.minY(), stateSnapshot.minZ(),
                    stateSnapshot.sizeX(), stateSnapshot.sizeY(), stateSnapshot.sizeZ(),
                    minX, minY, minZ, sizeX, sizeY, sizeZ,
                    start.x, start.y, start.z,
                    targetX, targetY, targetZ, targetCount,
                    maxRange, maxVisitedNodes, reachRange,
                    entityWidth, entityHeight, maxUpStep,
                    mob.getMaxFallDistance(), pathfindingMalus,
                    mobJumpHeight,
                    mob.getBbWidth(),
                    canPassDoors, canOpenDoors,
                    mobBlockPosition.getX(), mobBlockPosition.getY(), mobBlockPosition.getZ(),
                    canWalkOverFences, mobsIgnoreRails,
                    canFloat,
                    // isAmphibious() is protected; AmphibiousNodeEvaluator is the only
                    // subclass that overrides it to true.
                    isAmphibious,
                    levelMinY,
                    worldKey,
                    outPath);
        } finally {
            long nativeNanos = System.nanoTime() - nativeStart;
            NativePathfinder.recordNativeNanos(nativeNanos);
            NativePathfinder.recordStateSnapshotNativeNanos(nativeNanos);
            if (jfrEvent != null) jfrEvent.recordNative(nativeNanos);
        }
    }

    /**
     * Decides whether the native path is worth attempting for this box.
     *
     * <p>The cost structure forces an exact predicate rather than a heuristic. Measured
     * at 150 zombies on a 60x15x60 box: a mirror hit costs ~110us, a mirror miss
     * ~6400us, and plain Java ~140us. Breaking even against Java therefore needs the
     * mirror to hit on more than 99% of attempts — the miss penalty is ~200x the hit
     * benefit, so no proxy (Manhattan distance, region volume, historical node counts)
     * has anywhere near the required accuracy.
     *
     * <p>So the gate asks the mirror directly whether it already holds every cell the
     * search would read, and otherwise refuses — except for a small per-tick upload
     * budget, which is what populates the mirror in the first place.
     */
    private static boolean shouldTryNative(Mob mob, int worldKey,
                                           int minX, int minY, int minZ,
                                           int sizeX, int sizeY, int sizeZ,
                                           int entityWidth, int entityHeight) {
        long volume = (long)sizeX * sizeY * sizeZ;
        if (volume < MIN_NATIVE_REGION_VOLUME) return false;
        // Exactly the box findPathFromStateMirror's lazy grid and
        // stateSnapshot.fill below will read.
        if (NativePathfinder.stateMirrorCovers(worldKey,
                minX - 1, minY - 1, minZ - 1,
                sizeX + entityWidth + 1, sizeY + entityHeight + 1, sizeZ + entityWidth + 1)) {
            return true;
        }
        if (!claimWarmupUpload(mob.level().getGameTime())) return false;
        NativePathfinder.recordMirrorWarmupPass();
        return true;
    }

    /**
     * Claims one of this tick's mirror warm-up uploads, if any are left.
     *
     * <p>Only the miss path uploads to the mirror, so a coverage gate with no escape
     * hatch would keep the mirror empty forever. Allowing a bounded number of misses
     * per tick lets coverage grow while capping the warm-up cost at roughly one
     * snapshot upload per tick per thread.
     */
    private static boolean claimWarmupUpload(long gameTime) {
        if (MIRROR_WARMUP_UPLOADS_PER_TICK <= 0) return false;
        long[] budget = WARMUP_BUDGET.get();
        if (budget[0] != gameTime) {
            budget[0] = gameTime;
            budget[1] = 0L;
        }
        if (budget[1] >= MIRROR_WARMUP_UPLOADS_PER_TICK) return false;
        ++budget[1];
        return true;
    }

    private static boolean isShortPath(Mob mob, Set<BlockPos> targets) {
        if (SHORT_PATH_MANHATTAN_LIMIT <= 0) return false;
        BlockPos origin = mob.blockPosition();
        for (BlockPos target : targets) {
            int distance = Math.abs(target.getX() - origin.getX())
                    + Math.abs(target.getY() - origin.getY())
                    + Math.abs(target.getZ() - origin.getZ());
            if (distance > SHORT_PATH_MANHATTAN_LIMIT) return false;
        }
        return true;
    }

    private static final class CachingPathfindingContext extends PathfindingContext {
        private final PathfinderBuffers.RawPathTypeCache cache;

        private CachingPathfindingContext(PathNavigationRegion region,
                                          Mob mob,
                                          PathfinderBuffers.RawPathTypeCache cache) {
            super(region, mob);
            this.cache = cache;
        }

        @Override
        public PathType getPathTypeFromState(int x, int y, int z) {
            int ordinal = this.cache.get(x, y, z);
            if (ordinal >= 0) return PATH_TYPES[ordinal];
            PathType pathType = super.getPathTypeFromState(x, y, z);
            this.cache.put(x, y, z, pathType.ordinal());
            return pathType;
        }
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
