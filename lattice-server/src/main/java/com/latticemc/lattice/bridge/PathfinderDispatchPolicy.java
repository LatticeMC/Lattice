package com.latticemc.lattice.bridge;

/** Pure cost model and process-wide tick budget for native pathfinder dispatch. */
final class PathfinderDispatchPolicy {
    private final long javaNanosPerNode;
    private final long nativeNanosPerNode;
    private final long nativeHitFixedNanos;
    private final long materializeNanosPerCell;
    private final long snapshotNanosPerCell;
    private final long minimumRegionVolume;

    PathfinderDispatchPolicy(long javaNanosPerNode, long nativeNanosPerNode,
                             long nativeHitFixedNanos, long materializeNanosPerCell, long snapshotNanosPerCell,
                             long minimumRegionVolume) {
        if (javaNanosPerNode <= 0 || nativeNanosPerNode < 0 || nativeHitFixedNanos < 0
                || materializeNanosPerCell < 0 || snapshotNanosPerCell < 0 || minimumRegionVolume < 0) {
            throw new IllegalArgumentException("cost rates and minimum volume must be non-negative; Java rate must be positive");
        }
        this.javaNanosPerNode = javaNanosPerNode;
        this.nativeNanosPerNode = nativeNanosPerNode;
        this.nativeHitFixedNanos = nativeHitFixedNanos;
        this.materializeNanosPerCell = materializeNanosPerCell;
        this.snapshotNanosPerCell = snapshotNanosPerCell;
        this.minimumRegionVolume = minimumRegionVolume;
    }

    static PathfinderDispatchPolicy configured() {
        // 2026-11-06 256x256 serpentine: Java 7.13ms/7913 path nodes,
        // mirror (5.57ms - 0.14ms materialization)/7913. Short mirror: 0.14ms/65536.
        // Use conservative rounded rates, not a claim about visited-node counts.
        // Snapshot includes Java fill: the recorded 60x15x60 miss ceiling is 7.5ms,
        // or 139ns/cell. Synthetic pre-encoded snapshots alone cannot price fill.
        long javaProxyNanosPerNode = Long.getLong("lattice.pathfinderJavaNanosPerNode", 900L);
        long nativeProxyNanosPerNode = Long.getLong("lattice.pathfinderNativeNanosPerNode", 700L);
        long hitNanos = Long.getLong("lattice.pathfinderNativeHitFixedNanos", 3_000L);
        long materializeNanosPerCell = Long.getLong("lattice.pathfinderMaterializeNanosPerCell", 3L);
        long snapshotNanosPerCell = Long.getLong("lattice.pathfinderSnapshotNanosPerCell", 140L);
        long minimumRegionVolume = Integer.getInteger("lattice.pathfinderMinNativeVolume", 8192);
        return new PathfinderDispatchPolicy(javaProxyNanosPerNode, nativeProxyNanosPerNode,
                hitNanos, materializeNanosPerCell, snapshotNanosPerCell, minimumRegionVolume);
    }

    Decision decide(long regionVolume, long expandedSnapshotVolume, int targetDistance,
                    int nodeBudget, boolean mirrorCovered) {
        if (regionVolume <= 0 || expandedSnapshotVolume <= 0 || targetDistance < 0 || nodeBudget <= 0) {
            return Decision.reject();
        }
        long nodes = Math.min(regionVolume, estimatedNodes(nodeBudget, targetDistance));
        if (regionVolume < this.minimumRegionVolume || nodes == 0) return Decision.reject();
        long javaNanos = multiplySaturated(nodes, this.javaNanosPerNode);
        long nativeNanos = addSaturated(this.nativeHitFixedNanos,
                addSaturated(multiplySaturated(regionVolume, this.materializeNanosPerCell),
                        multiplySaturated(nodes, this.nativeNanosPerNode)));
        long snapshotNanos = multiplySaturated(expandedSnapshotVolume, this.snapshotNanosPerCell);
        long totalNativeNanos = mirrorCovered ? nativeNanos : addSaturated(nativeNanos, snapshotNanos);
        return totalNativeNanos < javaNanos
                ? new Decision(true, nodes, javaNanos, nativeNanos, snapshotNanos)
                : Decision.reject();
    }

    static long estimatedNodes(int nodeBudget, int nearestTargetDistance) {
        if (nodeBudget <= 0 || nearestTargetDistance <= 0) return 0;
        return Math.min((long) nodeBudget, multiplySaturated(nearestTargetDistance, nearestTargetDistance));
    }

    static int distanceBeyondReach(int startX, int startY, int startZ,
                                   int targetX, int targetY, int targetZ, int reachRange) {
        long distance = Math.abs((long) startX - targetX) + Math.abs((long) startY - targetY)
                + Math.abs((long) startZ - targetZ);
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0L, distance - Math.max(0, reachRange)));
    }

    SnapshotAdmission admit(long regionVolume, long expandedVolume, int distance, int nodeBudget,
                            boolean covered, long tick, TickBudget budget) {
        Decision decision = decide(regionVolume, expandedVolume, distance, nodeBudget, covered);
        if (!decision.useNative() || (!covered && !budget.tryReserve(tick, decision.snapshotNanos()))) {
            return null;
        }
        return new SnapshotAdmission(regionVolume, expandedVolume, distance, nodeBudget, covered, tick, budget, decision);
    }

    final class SnapshotAdmission {
        private final long regionVolume, expandedVolume, tick;
        private final int distance, nodeBudget;
        private final TickBudget budget;
        private final Decision firstDecision;
        private boolean reserved;
        private boolean fallbackChecked;
        private boolean fallbackAllowed;

        private SnapshotAdmission(long regionVolume, long expandedVolume, int distance, int nodeBudget,
                                  boolean covered, long tick, TickBudget budget, Decision firstDecision) {
            this.regionVolume = regionVolume;
            this.expandedVolume = expandedVolume;
            this.distance = distance;
            this.nodeBudget = nodeBudget;
            this.tick = tick;
            this.budget = budget;
            this.firstDecision = firstDecision;
            this.reserved = !covered;
        }

        boolean allowSnapshotAfterMirrorFailure() {
            if (reserved) return true;
            if (fallbackChecked) return fallbackAllowed;
            fallbackChecked = true;
            Decision miss = decide(regionVolume, expandedVolume, distance, nodeBudget, false);
            // The unsuccessful mirror attempt is already paid, in addition to the
            // snapshot's own search. Do not price the second attempt as a fresh miss.
            if (!miss.useNative() || addSaturated(firstDecision.nativeNanos(),
                    addSaturated(miss.nativeNanos(), miss.snapshotNanos())) >= miss.javaNanos()) return false;
            fallbackAllowed = budget.tryReserve(tick, miss.snapshotNanos());
            reserved |= fallbackAllowed;
            return fallbackAllowed;
        }
    }

    private static long multiplySaturated(long left, long right) {
        if (left == 0 || right == 0) return 0;
        if (left > Long.MAX_VALUE / right) return Long.MAX_VALUE;
        return left * right;
    }

    private static long addSaturated(long left, long right) {
        return Long.MAX_VALUE - left < right ? Long.MAX_VALUE : left + right;
    }

    record Decision(boolean useNative, long nodes, long javaNanos, long nativeNanos, long snapshotNanos) {
        private static Decision reject() { return new Decision(false, 0, 0, 0, 0); }
    }

    static final class TickBudget {
        private final long limitNanos;
        private long tick = Long.MIN_VALUE;
        private long reservedNanos;

        TickBudget(long limitNanos) {
            if (limitNanos < 0) throw new IllegalArgumentException("tick budget must be non-negative");
            this.limitNanos = limitNanos;
        }

        synchronized boolean tryReserve(long requestedTick, long nanos) {
            if (requestedTick < this.tick || nanos < 0 || nanos > this.limitNanos) return false;
            if (requestedTick > this.tick) {
                this.tick = requestedTick;
                this.reservedNanos = 0;
            }
            if (this.reservedNanos > this.limitNanos - nanos) return false;
            this.reservedNanos += nanos;
            return true;
        }
    }
}
