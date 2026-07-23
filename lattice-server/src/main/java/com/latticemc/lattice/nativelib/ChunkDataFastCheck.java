package com.latticemc.lattice.nativelib;

import ca.spottedleaf.concurrentutil.map.ConcurrentLong2ReferenceChainedHashTable;
import ca.spottedleaf.moonrise.common.util.CoordinateUtils;
import ca.spottedleaf.moonrise.patches.chunk_system.io.ChunkSystemRegionFileStorage;
import ca.spottedleaf.moonrise.patches.chunk_system.io.MoonriseRegionFileIO;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFile;

public final class ChunkDataFastCheck {
    private static final VarHandle CHUNK_TASKS = findChunkTasksHandle();

    private ChunkDataFastCheck() {
    }

    private static VarHandle findChunkTasksHandle() {
        try {
            return MethodHandles.privateLookupIn(MoonriseRegionFileIO.RegionDataController.class, MethodHandles.lookup())
                .findVarHandle(
                    MoonriseRegionFileIO.RegionDataController.class,
                    "chunkTasks",
                    ConcurrentLong2ReferenceChainedHashTable.class
                );
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    public static boolean isDataKnownAbsent(
        MoonriseRegionFileIO.RegionDataController controller, int chunkX, int chunkZ
    ) throws IOException {
        if (CHUNK_TASKS == null) {
            return false;
        }

        ConcurrentLong2ReferenceChainedHashTable<Object> chunkTasks =
            (ConcurrentLong2ReferenceChainedHashTable<Object>)CHUNK_TASKS.get(controller);
        boolean[] knownAbsent = new boolean[1];
        IOException[] failure = new IOException[1];

        chunkTasks.compute(CoordinateUtils.getChunkKey(chunkX, chunkZ), (keyInMap, running) -> {
            if (running != null) {
                return running;
            }

            try {
                RegionFile regionFile = ((ChunkSystemRegionFileStorage)controller.getCache())
                    .moonrise$getRegionFileIfExists(chunkX, chunkZ);
                if (regionFile == null) {
                    knownAbsent[0] = true;
                } else {
                    synchronized (regionFile) {
                        knownAbsent[0] = !regionFile.hasChunk(new ChunkPos(chunkX, chunkZ));
                    }
                }
            } catch (IOException ex) {
                failure[0] = ex;
            }

            return null;
        });

        if (failure[0] != null) {
            throw failure[0];
        }
        return knownAbsent[0];
    }
}
