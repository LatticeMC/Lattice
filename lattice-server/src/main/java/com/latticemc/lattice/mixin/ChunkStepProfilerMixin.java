package com.latticemc.lattice.mixin;

import com.latticemc.lattice.nativelib.WorldgenProfiler;
import java.util.concurrent.CompletableFuture;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.status.ChunkStep;
import net.minecraft.world.level.chunk.status.WorldGenContext;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ChunkStep.class)
public abstract class ChunkStepProfilerMixin {
    @Shadow @Final private ChunkStatus targetStatus;
    @Unique private final ThreadLocal<Long> lattice$applyStart = ThreadLocal.withInitial(() -> 0L);

    @Inject(method = "apply", at = @At("HEAD"))
    private void lattice$profileApplyStart(WorldGenContext worldGenContext, StaticCache2D<GenerationChunkHolder> cache, ChunkAccess chunk, CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        this.lattice$applyStart.set(WorldgenProfiler.start());
    }

    @Inject(method = "apply", at = @At("RETURN"))
    private void lattice$profileApplyEnd(WorldGenContext worldGenContext, StaticCache2D<GenerationChunkHolder> cache, ChunkAccess chunk, CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        WorldgenProfiler.end("status." + this.targetStatus.getName(), this.lattice$applyStart.get().longValue());
    }
}
