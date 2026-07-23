package com.latticemc.lattice.mixin;

import com.latticemc.lattice.nativelib.WorldgenProfiler;
import java.util.concurrent.CompletableFuture;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStep;
import net.minecraft.world.level.chunk.status.ChunkStatusTasks;
import net.minecraft.world.level.chunk.status.WorldGenContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ChunkStatusTasks.class)
public abstract class ChunkStatusTasksProfilerMixin {
    @Unique private static final ThreadLocal<Long> lattice$generateNoiseStart = WorldgenProfiler.available()
            ? ThreadLocal.withInitial(() -> 0L)
            : null;

    @Inject(method = "generateNoise", at = @At("HEAD"))
    private static void lattice$profileGenerateNoiseStart(WorldGenContext worldGenContext, ChunkStep step, StaticCache2D<GenerationChunkHolder> cache, ChunkAccess chunk, CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        if (!WorldgenProfiler.available()) return;
        lattice$generateNoiseStart.set(WorldgenProfiler.start());
    }

    @Inject(method = "generateNoise", at = @At("RETURN"))
    private static void lattice$profileGenerateNoiseEnd(WorldGenContext worldGenContext, ChunkStep step, StaticCache2D<GenerationChunkHolder> cache, ChunkAccess chunk, CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        if (!WorldgenProfiler.available()) return;
        WorldgenProfiler.end("status.generateNoise", lattice$generateNoiseStart.get().longValue());
    }
}
