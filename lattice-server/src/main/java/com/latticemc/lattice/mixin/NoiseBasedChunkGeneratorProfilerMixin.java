package com.latticemc.lattice.mixin;

import com.latticemc.lattice.nativelib.WorldgenProfiler;
import java.util.concurrent.CompletableFuture;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(NoiseBasedChunkGenerator.class)
public abstract class NoiseBasedChunkGeneratorProfilerMixin {
    @Unique private final ThreadLocal<Long> lattice$fillFromNoiseStart = WorldgenProfiler.available()
            ? ThreadLocal.withInitial(() -> 0L)
            : null;
    @Unique private final ThreadLocal<Long> lattice$doFillStart = WorldgenProfiler.available()
            ? ThreadLocal.withInitial(() -> 0L)
            : null;

    @Inject(method = "fillFromNoise", at = @At("HEAD"))
    private void lattice$profileFillFromNoiseStart(Blender blender, RandomState randomState, StructureManager structureManager, ChunkAccess chunk, CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        if (!WorldgenProfiler.available()) return;
        this.lattice$fillFromNoiseStart.set(WorldgenProfiler.start());
    }

    @Inject(method = "fillFromNoise", at = @At("RETURN"))
    private void lattice$profileFillFromNoiseEnd(Blender blender, RandomState randomState, StructureManager structureManager, ChunkAccess chunk, CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        if (!WorldgenProfiler.available()) return;
        WorldgenProfiler.end("noise.fillFromNoise", this.lattice$fillFromNoiseStart.get().longValue());
    }

    @Inject(method = "doFill", at = @At("HEAD"))
    private void lattice$profileDoFillStart(Blender blender, StructureManager structureManager, RandomState random, ChunkAccess chunk, int minCellY, int cellCountY, CallbackInfoReturnable<ChunkAccess> cir) {
        if (!WorldgenProfiler.available()) return;
        this.lattice$doFillStart.set(WorldgenProfiler.start());
    }

    @Inject(method = "doFill", at = @At("RETURN"))
    private void lattice$profileDoFillEnd(Blender blender, StructureManager structureManager, RandomState random, ChunkAccess chunk, int minCellY, int cellCountY, CallbackInfoReturnable<ChunkAccess> cir) {
        if (!WorldgenProfiler.available()) return;
        WorldgenProfiler.end("noise.doFill", this.lattice$doFillStart.get().longValue());
    }

}
