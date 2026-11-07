package net.minecraft.world.level;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.latticemc.lattice.nativelib.LatticeNative;
import com.latticemc.lattice.nativelib.NativeSpawnFilter;
import java.lang.reflect.Field;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import org.junit.jupiter.api.Test;

class NaturalSpawnerEntryTestSuite {
    @Test
    void loadedNativeDoesNotRejectAChunkBeforeChoosingItsRandomCandidate() throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        ServerLevel level = mock(ServerLevel.class);
        LevelChunk chunk = mock(LevelChunk.class);
        RandomSource random = mock(RandomSource.class);
        Field randomField = Level.class.getDeclaredField("random");
        randomField.setAccessible(true);
        randomField.set(level, random);
        when(chunk.getPos()).thenReturn(new ChunkPos(0, 0));
        when(chunk.getHeight(eq(Heightmap.Types.WORLD_SURFACE), anyInt(), anyInt())).thenReturn(-1);
        when(level.getMinY()).thenReturn(0);
        when(level.players()).thenReturn(List.of());
        try (var nativeLibrary = mockStatic(LatticeNative.class);
             var spawnFilter = mockStatic(NativeSpawnFilter.class)) {
            nativeLibrary.when(LatticeNative::isLoaded).thenReturn(true);
            spawnFilter.when(NativeSpawnFilter::isAvailable).thenReturn(true);
            NaturalSpawner.spawnCategoryForChunk(MobCategory.MONSTER, level, chunk,
                    (type, pos, access) -> fail("minY candidate must not reach predicates"),
                    (mob, access) -> fail("minY candidate must not spawn"), 10, entity -> {});
            verify(random, times(2)).nextInt(16);
            verify(random).nextInt(1);
            verify(level, never()).players();
            spawnFilter.verifyNoInteractions();
        }
    }

    @Test
    void minYCenterCannotRepresentSurfaceOrCaveCandidates() {
        double[] player = {8.5, 64, 40.5};
        double[] center = {8.5, -64, 8.5};
        double[] candidates = {8.5, 64, 8.5, 8.5, 20, 8.5};
        long[] accepted = new long[1];
        assertEquals(0, filter(center, 1, player, accepted));
        assertEquals(2, filter(candidates, 2, player, accepted));
        assertEquals(3, accepted[0]);
    }

    private static int filter(double[] positions, int count, double[] player, long[] accepted) {
        return NativeSpawnFilter.javaFilter(positions, count, null, null, null, null,
                0, 0, null, 0, player, 1, 128 * 128, accepted);
    }
}
