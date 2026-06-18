package com.latticemc.lattice.nativelib;

/**
 * JNI wrapper for the native BFS light propagator.
 *
 * <p>Each instance owns a native {@code ChunkLightProvider} through an opaque handle.
 * The native side calls back into this object for topology queries (opacity, shape
 * occlusion, committed level read/write) via the {@code callback*} methods.
 *
 * <p>The public API mirrors vanilla {@code DynamicGraphMinFixedPoint}:
 * <ul>
 *   <li>{@link #applyPendingUpdates(int)} — drain the BFS worklist</li>
 *   <li>{@link #updateLevel(long, long, int, boolean)} — enqueue a candidate</li>
 *   <li>{@link #hasPendingUpdates()} — check for pending work</li>
 *   <li>{@link #propagateLevel(long, int, boolean)} — push from a fixed source</li>
 * </ul>
 */
public class NativeLightEngine {
    private long nativeHandle;
    private final int levelCount;

    public NativeLightEngine(int levelCount, int expectedLevelSize, int expectedTotalSize) {
        if (levelCount <= 0 || levelCount >= 254) {
            throw new IllegalArgumentException("Level count must be in (0, 254).");
        }
        this.levelCount = levelCount;
        if (LatticeNative.isLoaded()) {
            this.nativeHandle = nativeCreate(levelCount, expectedLevelSize, expectedTotalSize);
        }
    }

    public boolean isNativeActive() {
        return nativeHandle != 0;
    }

    public static boolean isAvailable() {
        LatticeNative.ensureLoaded();
        return LatticeNative.isLoaded();
    }

    public int applyPendingUpdates(int maxSteps) {
        if (nativeHandle == 0) return maxSteps;
        return nativeApplyPendingUpdates(nativeHandle, maxSteps);
    }

    public void updateLevel(long sourceId, long id, int level, boolean decrease) {
        if (nativeHandle == 0) return;
        nativeUpdateLevel(nativeHandle, sourceId, id, level, decrease);
    }

    public void propagateLevel(long id, int level, boolean decrease) {
        if (nativeHandle == 0) return;
        nativePropagateLevel(nativeHandle, id, level, decrease);
    }

    public boolean hasPendingUpdates() {
        if (nativeHandle == 0) return false;
        return nativeHasPendingUpdates(nativeHandle);
    }

    public int getPendingUpdateCount() {
        if (nativeHandle == 0) return 0;
        return nativeGetPendingCount(nativeHandle);
    }

    public void removePendingUpdate(long id) {
        if (nativeHandle == 0) return;
        nativeRemovePendingUpdate(nativeHandle, id);
    }

    /**
     * Permanently disables the native handle (e.g. after a callback failure).
     * Subsequent calls will no-op, allowing the caller to fall back to vanilla.
     */
    public void disable() {
        if (nativeHandle != 0) {
            nativeDestroy(nativeHandle);
            nativeHandle = 0;
        }
    }

    public void destroy() {
        disable();
    }

    // ---- Callbacks invoked by native BFS (override in subclass / mixin bridge) ----

    /**
     * Called by native to compute the propagated level from source to target.
     * Subclass / mixin bridge must override.
     */
    protected int callbackGetPropagatedLevel(long source, long target, int level) {
        return levelCount;
    }

    /**
     * Called by native to fan out propagation from a node.
     * The implementation should call {@link #nativeUpdateLevelByPtr(long, long, long, int, boolean)}
     * for each neighbour.
     */
    protected void callbackPropagateLevel(long nativePtr, long source, long target, int level, boolean decrease) {
        // No-op default; subclass must provide topology
    }

    /**
     * Returns true if the id represents a "source" marker (Long.MAX_VALUE in vanilla).
     */
    protected boolean callbackIsMarker(long id) {
        return id == Long.MAX_VALUE;
    }

    /**
     * Called by native to recompute the best level for a node.
     */
    protected int callbackRecalculateLevel(long id, long excludedId, int maxLevel) {
        return maxLevel;
    }

    /**
     * Returns the currently committed (stored) level for a position.
     */
    protected int callbackGetCommittedLevel(long id) {
        return levelCount;
    }

    /**
     * Sets the committed level for a position.
     */
    protected void callbackSetCommittedLevel(long id, int level) {
        // No-op default
    }

    // ---- Static utility for re-entrant update from propagateLevel callback ----

    public static void updateLevelByPtr(long nativePtr, long sourceId, long id, int level, boolean decrease) {
        nativeUpdateLevelByPtr(nativePtr, sourceId, id, level, decrease);
    }

    // ---- Native methods ----

    private native long nativeCreate(int levelCount, int expectedLevelSize, int expectedTotalSize);
    private static native void nativeDestroy(long handle);
    private static native boolean nativeHasPendingUpdates(long handle);
    private static native int nativeGetPendingCount(long handle);
    private static native int nativeApplyPendingUpdates(long handle, int maxSteps);
    private static native void nativeUpdateLevel(long handle, long sourceId, long id, int level, boolean decrease);
    private static native void nativePropagateLevel(long handle, long id, int level, boolean decrease);
    private static native void nativeRemovePendingUpdate(long handle, long id);
    static native void nativeUpdateLevelByPtr(long propPtr, long sourceId, long id, int level, boolean decrease);
}
