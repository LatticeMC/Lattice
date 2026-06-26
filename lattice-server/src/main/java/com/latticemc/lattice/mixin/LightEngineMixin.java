package com.latticemc.lattice.mixin;

import com.latticemc.lattice.nativelib.LatticeNative;
import com.latticemc.lattice.nativelib.NativeLightEngine;
import net.minecraft.world.level.lighting.DynamicGraphMinFixedPoint;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Accelerates the {@code DynamicGraphMinFixedPoint.runUpdates(int)} BFS loop
 * by delegating to the native {@code LevelPropagator} / {@code ChunkLightProvider}.
 *
 * <p>Integration shape:
 * <ul>
 *   <li>On construction, allocates a native handle via
 *       {@link NativeLightEngine#nativeCreate}.</li>
 *   <li>On {@code runUpdates(int)}, if native is active, delegates the entire
 *       BFS drain to the native side and returns the remaining budget.</li>
 *   <li>On failure, disables the native handle and falls back to vanilla for
 *       the rest of the server lifetime.</li>
 * </ul>
 *
 * <p>The native side calls back into the Java instance for:
 * <ul>
 *   <li>{@code getComputedLevel} — recalculate best level from neighbours</li>
 *   <li>{@code checkNeighborsAfterUpdate} — fan out propagation</li>
 *   <li>{@code getLevel} / {@code setLevel} — committed storage read/write</li>
 *   <li>{@code computeLevelFromNeighbor} — per-edge attenuation</li>
 * </ul>
 *
 * @deprecated This mixin targets the vanilla {@code DynamicGraphMinFixedPoint} light engine
 * which is fully replaced by Moonrise/Starlight in Paper. Starlight uses a fundamentally
 * different propagation algorithm (outward BFS vs inward recalculation) and does not
 * use {@code DynamicGraphMinFixedPoint} at all. This mixin is effectively a no-op on
 * Paper servers and is retained only for potential non-Paper deployment. The native
 * BFS contract (increase/decrease FIFO queues) does not match Starlight's architecture.
 */
@Deprecated
@SuppressWarnings("removal")
@Mixin(DynamicGraphMinFixedPoint.class)
public abstract class LightEngineMixin implements LightEngineCallbacks {

    @Shadow @Final protected int levelCount;

    @Unique
    private NativeLightEngine lattice$nativeEngine;

    @Unique
    private boolean lattice$nativeDisabled = false;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void lattice$init(int firstQueuedLevel, int width, int height,
                              org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        if (!LatticeNative.isLoaded()) return;
        try {
            this.lattice$nativeEngine = new NativeLightEngineBridge(this, firstQueuedLevel, width, height);
        } catch (Exception e) {
            LatticeNative.logFallbackOnce("light_engine", "failed to create native engine: " + e.getMessage());
            this.lattice$nativeDisabled = true;
        }
    }

    @Inject(method = "runUpdates", at = @At("HEAD"), cancellable = true)
    private void lattice$runUpdates(int toUpdateCount, CallbackInfoReturnable<Integer> cir) {
        if (!LatticeNative.isLoaded()) return;
        if (lattice$nativeDisabled || lattice$nativeEngine == null) return;
        if (!lattice$nativeEngine.isNativeActive()) {
            lattice$nativeDisabled = true;
            return;
        }
        try {
            int remaining = lattice$nativeEngine.applyPendingUpdates(toUpdateCount);
            cir.setReturnValue(remaining);
        } catch (IllegalStateException e) {
            LatticeNative.logFallbackOnce("light_engine",
                    "native BFS failed, falling back to JVM: " + e.getMessage());
            lattice$nativeEngine.disable();
            lattice$nativeDisabled = true;
            // Don't cancel — let vanilla handle this call
        }
    }

    // ---- Shadow methods for callback delegation ----

    @Shadow
    protected abstract int computeLevelFromNeighbor(long startPos, long endPos, int startLevel);

    @Shadow
    protected abstract void checkNeighborsAfterUpdate(long pos, int level, boolean isDecreasing);

    @Shadow
    protected abstract int getComputedLevel(long pos, long excludedSourcePos, int level);

    @Shadow
    protected abstract int getLevel(long chunkPos);

    @Shadow
    protected abstract void setLevel(long chunkPos, int level);

    @Shadow
    protected boolean isSource(long pos) { return false; }

    // ---- LightEngineCallbacks implementation ----

    @Override
    public int callbackGetPropagatedLevel(long source, long target, int level) {
        return this.computeLevelFromNeighbor(source, target, level);
    }

    @Override
    public void callbackPropagateLevel(long nativePtr, long source, long target,
                                       int level, boolean decrease) {
        this.checkNeighborsAfterUpdate(target, level, decrease);
    }

    @Override
    public boolean callbackIsMarker(long id) {
        return this.isSource(id);
    }

    @Override
    public int callbackRecalculateLevel(long id, long excludedId, int maxLevel) {
        return this.getComputedLevel(id, excludedId, maxLevel);
    }

    @Override
    public int callbackGetCommittedLevel(long id) {
        return this.getLevel(id);
    }

    @Override
    public void callbackSetCommittedLevel(long id, int level) {
        this.setLevel(id, level);
    }
}
