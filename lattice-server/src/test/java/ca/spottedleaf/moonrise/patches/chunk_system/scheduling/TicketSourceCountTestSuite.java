package ca.spottedleaf.moonrise.patches.chunk_system.scheduling;

import static org.junit.jupiter.api.Assertions.*;

import ca.spottedleaf.concurrentutil.lock.ReentrantAreaLock;
import ca.spottedleaf.moonrise.common.util.CoordinateUtils;
import ca.spottedleaf.moonrise.patches.chunk_system.scheduling.task.ChunkProgressionTask;
import it.unimi.dsi.fastutil.longs.Long2ByteLinkedOpenHashMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

class TicketSourceCountTestSuite {
    private static class Propagator extends ThreadedTicketLevelPropagator {
        final Map<Long, Byte> levels = new HashMap<>();
        final List<Long> order = new ArrayList<>();
        @Override protected void processLevelUpdates(Long2ByteLinkedOpenHashMap updates) {
            updates.long2ByteEntrySet().forEach(entry -> { levels.put(entry.getLongKey(), entry.getByteValue()); order.add(entry.getLongKey()); });
            updates.clear(); // No external scheduler is needed for the propagation oracle.
        }
        @Override protected void processSchedulingUpdates(Long2ByteLinkedOpenHashMap updates, List<ChunkProgressionTask> tasks, List<NewChunkHolder> changed) {
            throw new AssertionError("unexpected scheduler call");
        }
        void flush() { performUpdates(new ReentrantAreaLock(6), new ReentrantAreaLock(6), new ArrayList<>(), new ArrayList<>()); }
    }

    @Test void pendingSourceOrderAndDuplicateRemovalArePreserved() {
        Propagator p = new Propagator();
        p.setSource(2, 0, 1); p.setSource(1, 0, 1); p.setSource(2, 0, 1); p.flush();
        assertEquals(List.of(CoordinateUtils.getChunkKey(2, 0), CoordinateUtils.getChunkKey(1, 0)), p.order);
        p.removeSource(2, 0); p.removeSource(2, 0); p.removeSource(1, 0); p.flush();
        assertFalse(p.hasPendingUpdates());
        p.setSource(2, 0, 1); p.removeSource(2, 0); p.flush();
        assertEquals(0, p.levels.get(CoordinateUtils.getChunkKey(2, 0)).intValue());
    }

    @Test void randomUpdatesMatchChebyshevPropagationAcrossSectionBoundaries() {
        Propagator p = new Propagator();
        Map<Long, Integer> sources = new HashMap<>();
        Random random = new Random(548532);
        int[] positions = {-65, -64, -1, 0, 1, 63, 64, 65};
        for (int step = 0; step < 80; step++) {
            int x = positions[random.nextInt(positions.length)], z = positions[random.nextInt(positions.length)];
            long key = CoordinateUtils.getChunkKey(x, z);
            if (random.nextBoolean()) { int level = 1 + random.nextInt(5); sources.put(key, level); p.setSource(x, z, level); }
            else { sources.remove(key); p.removeSource(x, z); }
            p.flush();
            for (var source : sources.entrySet()) {
                int sx = CoordinateUtils.getChunkX(source.getKey()), sz = CoordinateUtils.getChunkZ(source.getKey());
                for (int dx = -4; dx <= 4; dx++) for (int dz = -4; dz <= 4; dz++) {
                    int expected = 0;
                    for (var other : sources.entrySet()) expected = Math.max(expected, other.getValue() - Math.max(
                        Math.abs(sx + dx - CoordinateUtils.getChunkX(other.getKey())), Math.abs(sz + dz - CoordinateUtils.getChunkZ(other.getKey()))));
                    assertEquals(expected, Byte.toUnsignedInt(p.levels.getOrDefault(CoordinateUtils.getChunkKey(sx + dx, sz + dz), (byte)0)));
                }
            }
            for (var cell : p.levels.entrySet()) {
                int cx = CoordinateUtils.getChunkX(cell.getKey()), cz = CoordinateUtils.getChunkZ(cell.getKey()), expected = 0;
                for (var source : sources.entrySet()) expected = Math.max(expected, source.getValue() - Math.max(
                    Math.abs(cx - CoordinateUtils.getChunkX(source.getKey())), Math.abs(cz - CoordinateUtils.getChunkZ(source.getKey()))));
                assertEquals(expected, Byte.toUnsignedInt(cell.getValue()));
            }
        }
        sources.keySet().forEach(key -> p.removeSource(CoordinateUtils.getChunkX(key), CoordinateUtils.getChunkZ(key)));
        p.flush(); assertTrue(p.levels.values().stream().allMatch(value -> value == 0)); assertFalse(p.hasPendingUpdates());
    }
}
