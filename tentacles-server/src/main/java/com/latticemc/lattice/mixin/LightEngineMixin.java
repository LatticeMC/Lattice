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
 */
@Mixin(DynamicGraphMinFixedPoint.class)
public abstract class LightEngineMixin {

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
            this.lattice$nativeEngine = new NativeLightEngine(firstQueuedLevel, width, height) {
                @Override
                protected int callbackGetPropagatedLevel(long source, long target, int level) {
                    return LightEngineMixin.this.lattice$computeLevelFromNeighbor(source, target, level);
                }

                @Override
                protected void callbackPropagateLevel(long nativePtr, long source, long target,
                                                      int level, boolean decrease) {
                    LightEngineMixin.this.lattice$checkNeighborsAfterUpdate(target, level, decrease);
                }

                @Override
                protected boolean callbackIsMarker(long id) {
                    return LightEngineMixin.this.lattice$isSource(id);
                }

                @Override
                protected int callbackRecalculateLevel(long id, long excludedId, int maxLevel) {
                    return LightEngineMixin.this.lattice$getComputedLevel(id, excludedId, maxLevel);
                }

                @Override
                protected int callbackGetCommittedLevel(long id) {
                    return LightEngineMixin.this.lattice$getLevel(id);
                }

                @Override
                protected void callbackSetCommittedLevel(long id, int level) {
                    LightEngineMixin.this.lattice$setLevel(id, level);
                }
            };
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

    // ---- Unique bridges to avoid name conflicts ----

    @Unique
    private int lattice$computeLevelFromNeighbor(long start, long end, int level) {
        return this.computeLevelFromNeighbor(start, end, level);
    }

    @Unique
    private void lattice$checkNeighborsAfterUpdate(long pos, int level, boolean decrease) {
        this.checkNeighborsAfterUpdate(pos, level, decrease);
    }

    @Unique
    private int lattice$getComputedLevel(long pos, long excluded, int level) {
        return this.getComputedLevel(pos, excluded, level);
    }

    @Unique
    private int lattice$getLevel(long pos) {
        return this.getLevel(pos);
    }

    @Unique
    private void lattice$setLevel(long pos, int level) {
        this.setLevel(pos, level);
    }

    @Unique
    private boolean lattice$isSource(long pos) {
        return this.isSource(pos);
    }
}
