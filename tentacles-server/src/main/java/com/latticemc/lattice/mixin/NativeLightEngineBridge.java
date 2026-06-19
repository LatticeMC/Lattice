package com.latticemc.lattice.mixin;

import com.latticemc.lattice.nativelib.NativeLightEngine;

public class NativeLightEngineBridge extends NativeLightEngine {
    private final LightEngineCallbacks callbacks;

    public NativeLightEngineBridge(LightEngineCallbacks callbacks,
                                   int firstQueuedLevel, int width, int height) {
        super(firstQueuedLevel, width, height);
        this.callbacks = callbacks;
    }

    @Override
    protected int callbackGetPropagatedLevel(long source, long target, int level) {
        return callbacks.callbackGetPropagatedLevel(source, target, level);
    }

    @Override
    protected void callbackPropagateLevel(long nativePtr, long source, long target,
                                          int level, boolean decrease) {
        callbacks.callbackPropagateLevel(nativePtr, source, target, level, decrease);
    }

    @Override
    protected boolean callbackIsMarker(long id) {
        return callbacks.callbackIsMarker(id);
    }

    @Override
    protected int callbackRecalculateLevel(long id, long excludedId, int maxLevel) {
        return callbacks.callbackRecalculateLevel(id, excludedId, maxLevel);
    }

    @Override
    protected int callbackGetCommittedLevel(long id) {
        return callbacks.callbackGetCommittedLevel(id);
    }

    @Override
    protected void callbackSetCommittedLevel(long id, int level) {
        callbacks.callbackSetCommittedLevel(id, level);
    }
}
