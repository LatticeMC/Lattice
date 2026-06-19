package com.latticemc.lattice.mixin;

public interface LightEngineCallbacks {
    int callbackGetPropagatedLevel(long source, long target, int level);
    void callbackPropagateLevel(long nativePtr, long source, long target, int level, boolean decrease);
    boolean callbackIsMarker(long id);
    int callbackRecalculateLevel(long id, long excludedId, int maxLevel);
    int callbackGetCommittedLevel(long id);
    void callbackSetCommittedLevel(long id, int level);
}
