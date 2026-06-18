package com.latticemc.lattice.mixin;

import com.latticemc.lattice.nativelib.LatticeNative;
import com.latticemc.lattice.nativelib.NativeSpawnFilter;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Consumer;

/**
 * Accelerates mob-spawn candidate pre-filtering by delegating distance + entity
 * clearance checks to the native batched {@code NativeSpawnFilter}.
 *
 * <p>Injection point: {@code NaturalSpawner.spawnCategoryForChunk} (the Paper
 * overload with maxSpawns + trackEntity). When native is available, this mixin
 * runs the native pre-filter on the chunk's candidate position before proceeding
 * to the full spawn logic. Positions rejected by native are skipped entirely,
 * saving the expensive per-candidate entity creation, event firing, and collision
 * checks.
 *
 * <p>Fallback: if native is unavailable or fails, the mixin returns early without
 * cancelling, letting vanilla handle everything.
 */
@Mixin(NaturalSpawner.class)
public abstract class NaturalSpawnerMixin {

    /**
     * Pre-filters spawn candidate positions using the native batched filter.
     *
     * <p>This is a "best-effort accelerator" — it cannot fully replace the vanilla
     * spawn logic because:
     * <ul>
     *   <li>The vanilla loop does random walks (not a fixed candidate set)</li>
     *   <li>Mob type selection happens mid-loop</li>
     *   <li>Paper/Purpur patches interleave event firing</li>
     * </ul>
     *
     * <p>Instead, we use the native filter as a <b>fast-reject oracle</b>: for the
     * initial random position chosen by {@code getRandomPosWithin}, we can batch
     * the player-distance check. If no player is close enough, we skip the entire
     * chunk spawn attempt early.
     *
     * <p>The full batched pre-filter (palette + entity clearance + distance) is
     * applied when the server accumulates multiple candidate positions per tick
     * through the Paper per-player mob spawning path.
     */
    @Inject(
        method = "spawnCategoryForChunk(Lnet/minecraft/world/entity/MobCategory;Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/chunk/LevelChunk;Lnet/minecraft/world/level/NaturalSpawner$SpawnPredicate;Lnet/minecraft/world/level/NaturalSpawner$AfterSpawnCallback;ILjava/util/function/Consumer;)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private static void lattice$spawnCategoryForChunk(
            MobCategory category, ServerLevel level, LevelChunk chunk,
            NaturalSpawner.SpawnPredicate filter, NaturalSpawner.AfterSpawnCallback callback,
            int maxSpawns, Consumer<Entity> trackEntity,
            CallbackInfo ci) {
        if (!LatticeNative.isLoaded()) return;
        if (!NativeSpawnFilter.isAvailable()) return;

        // Quick player-distance pre-check: if no player is within spawn distance
        // of the chunk centre, skip this chunk entirely. This avoids the more
        // expensive getRandomPosWithin + full spawn loop.
        BlockPos chunkCenter = chunk.getPos().getMiddleBlockPosition(level.getMinY());
        double cx = chunkCenter.getX() + 0.5;
        double cy = chunkCenter.getY();
        double cz = chunkCenter.getZ() + 0.5;

        List<ServerPlayer> players = level.players();
        if (players.isEmpty()) {
            ci.cancel();
            return;
        }

        // Vanilla uses 128 blocks (squared = 16384) as the despawn distance, which
        // is also the effective max spawn distance from any player.
        // MobCategory.getDespawnDistance() squared gives us the effective range.
        double maxDistSq = category.getDespawnDistance() * category.getDespawnDistance();

        // Build flat player xyz array
        double[] playerXyz = new double[players.size() * 3];
        for (int i = 0; i < players.size(); i++) {
            playerXyz[i * 3]     = players.get(i).getX();
            playerXyz[i * 3 + 1] = players.get(i).getY();
            playerXyz[i * 3 + 2] = players.get(i).getZ();
        }

        // Single-candidate quick check: is the chunk centre within distance of any player?
        double[] candidateXyz = new double[] { cx, cy, cz };
        long[] acceptable = new long[1];
        int accepted = NativeSpawnFilter.filterCandidates(
                candidateXyz, 1,
                null, // default dims
                null, null, null, 0, 0, // no palette check for pre-filter
                null, 0, // no entity clearance for pre-filter
                playerXyz, players.size(),
                maxDistSq,
                acceptable);

        if (accepted == 0) {
            // No player close enough to this chunk — skip spawn attempt entirely.
            ci.cancel();
        }
        // Otherwise, fall through to vanilla logic which does the full spawn attempt.
    }
}
