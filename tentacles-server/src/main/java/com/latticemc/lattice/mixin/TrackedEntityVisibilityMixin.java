package com.latticemc.lattice.mixin;

import com.latticemc.lattice.nativelib.LatticeNative;
import com.latticemc.lattice.nativelib.NativeEntityVisibility;
import java.util.ArrayList;
import java.util.Set;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerPlayerConnection;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(ChunkMap.TrackedEntity.class)
public abstract class TrackedEntityVisibilityMixin {
    @Shadow @Final private Entity entity;
    @Shadow @Final public Set<ServerPlayerConnection> seenBy;
    @Shadow private long lastChunkUpdate;
    @Shadow private ca.spottedleaf.moonrise.common.misc.NearbyPlayers.TrackedChunk lastTrackedChunk;

    @Shadow public abstract void updatePlayer(ServerPlayer player);
    @Shadow public abstract void removePlayer(ServerPlayer player);
    @Shadow private int getEffectiveRange() { throw new AssertionError(); }
    @Shadow public abstract void moonrise$clearPlayers();

    /**
     * Preserves the current Paper/Moonrise tracking semantics, but uses a native
     * horizontal-distance prefilter for large nearby-player sets before calling
     * the original per-player {@code updatePlayer} logic.
     */
    @Overwrite
    public final void moonrise$tick(final ca.spottedleaf.moonrise.common.misc.NearbyPlayers.TrackedChunk chunk) {
        if (chunk == null) {
            this.moonrise$clearPlayers();
            return;
        }

        final ca.spottedleaf.moonrise.common.list.ReferenceList<ServerPlayer> players = chunk.getPlayers(
                ca.spottedleaf.moonrise.common.misc.NearbyPlayers.NearbyMapType.VIEW_DISTANCE);

        if (players == null) {
            this.moonrise$clearPlayers();
            return;
        }

        final long lastChunkUpdate = this.lastChunkUpdate;
        final long currChunkUpdate = chunk.getUpdateCount();
        final ca.spottedleaf.moonrise.common.misc.NearbyPlayers.TrackedChunk lastTrackedChunk = this.lastTrackedChunk;
        this.lastChunkUpdate = currChunkUpdate;
        this.lastTrackedChunk = chunk;

        final ServerPlayer[] playersRaw = players.getRawDataUnchecked();
        final int playerCount = players.size();

        if (playerCount >= 16) {
            if (NativeEntityVisibility.isAvailable()) {
                final double[] entityXyz = { this.entity.getX(), 0.0, this.entity.getZ() };
                final double effectiveRange = this.getEffectiveRange();
                final double[] entityRangeSq = { effectiveRange * effectiveRange };
                final double[] playerXyz = new double[playerCount * 3];
                for (int i = 0; i < playerCount; ++i) {
                    final ServerPlayer player = playersRaw[i];
                    final int base = i * 3;
                    playerXyz[base] = player.getX();
                    playerXyz[base + 1] = 0.0;
                    playerXyz[base + 2] = player.getZ();
                }

                final long[] visibility = new long[NativeEntityVisibility.rowLongs(playerCount)];
                NativeEntityVisibility.scan(entityXyz, entityRangeSq, 1, playerXyz, playerCount, visibility);

                for (int i = 0; i < playerCount; ++i) {
                    final ServerPlayer player = playersRaw[i];
                    final boolean inCoarseRange = ((visibility[i >>> 6] >>> (i & 63)) & 1L) != 0L;
                    if (inCoarseRange) {
                        this.updatePlayer(player);
                    } else if (this.seenBy.contains(player.connection)) {
                        this.removePlayer(player);
                    }
                }
            } else {
                LatticeNative.logFallbackOnce("entity_visibility", "native tracked-player visibility scan unavailable");
                for (int i = 0; i < playerCount; ++i) {
                    this.updatePlayer(playersRaw[i]);
                }
            }
        } else {
            for (int i = 0; i < playerCount; ++i) {
                this.updatePlayer(playersRaw[i]);
            }
        }

        if (lastChunkUpdate != currChunkUpdate || lastTrackedChunk != chunk) {
            for (final ServerPlayerConnection conn : new ArrayList<>(this.seenBy)) {
                final ServerPlayer player = conn.getPlayer();
                if (!players.contains(player)) {
                    this.removePlayer(player);
                }
            }
        }
    }
}
