package com.latticemc.lattice.diagnostics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.FileAlreadyExistsException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BrainMemoryWriteCensusTestSuite {
    @TempDir Path directory;

    @Test
    void csvIncludesExactCountsAndDoesNotOverwriteExistingEvidence() throws Exception {
        BrainMemoryWriteCensus.Collector census = new BrainMemoryWriteCensus.Collector();
        Object brain = new Object();
        tick(census, brain, 0, false);
        tick(census, brain, 2, false);
        tick(census, brain, 3, true);
        Path output = directory.resolve("census.csv");
        census.writeCsv(output);
        String csv = Files.readString(output);
        assertEquals("brainIdentity,recordType,value,count,failedTicks\n"
                + "1,writesPerTick,0,1,1\n1,writesPerTick,2,1,1\n1,writesPerTick,3,1,1\n"
                + "1,consecutiveWriteTicks,2,1,1\n", csv.replace("\r\n", "\n"));
        assertThrows(FileAlreadyExistsException.class, () -> census.writeCsv(output));
        assertEquals(csv, Files.readString(output));
    }

    @Test
    void recordsZeroWritesAndConsecutiveWriteTickStreaks() {
        BrainMemoryWriteCensus.Collector census = new BrainMemoryWriteCensus.Collector();
        Object brain = new Object();
        tick(census, brain, 0, false);
        tick(census, brain, 2, false);
        tick(census, brain, 1, false);
        tick(census, brain, 0, false);
        tick(census, brain, 4, false);

        assertEquals(Map.of(0, 2L, 1, 1L, 2, 1L, 4, 1L), census.writeCountDistribution(brain));
        assertEquals(Map.of(2, 1L, 1, 1L), census.streakDistribution(brain));
    }

    @Test
    void separatesBrainsByIdentityRatherThanEquals() {
        BrainMemoryWriteCensus.Collector census = new BrainMemoryWriteCensus.Collector();
        Object first = new EqualObject();
        Object second = new EqualObject();
        tick(census, first, 1, false);
        tick(census, second, 0, false);

        assertEquals(Map.of(1, 1L), census.writeCountDistribution(first));
        assertEquals(Map.of(0, 1L), census.writeCountDistribution(second));
    }

    @Test
    void collectedIdentityDoesNotRetainBrainOrLoseCompletedCensus() throws Exception {
        BrainMemoryWriteCensus.Collector census = new BrainMemoryWriteCensus.Collector();
        Object first = new EqualObject();
        tick(census, first, 2, false);
        tick(census, first, 3, false);
        WeakReference<Object> reference = census.referenceFor(first);
        reference.clear();
        assertTrue(reference.enqueue());
        assertEquals(Map.of(), census.writeCountDistribution(first));
        Object second = new EqualObject();
        tick(census, second, 0, false);
        Path output = directory.resolve("collected.csv");
        census.writeCsv(output);
        assertEquals("brainIdentity,recordType,value,count,failedTicks\n"
                + "1,writesPerTick,2,1,0\n1,writesPerTick,3,1,0\n"
                + "1,consecutiveWriteTicks,2,1,0\n2,writesPerTick,0,1,0\n",
                Files.readString(output).replace("\r\n", "\n"));
    }

    @Test
    void failedTickStillRecordsWritesAndFailure() {
        BrainMemoryWriteCensus.Collector census = new BrainMemoryWriteCensus.Collector();
        Object brain = new Object();
        assertThrows(IllegalStateException.class, () -> {
            census.beginTick(brain);
            boolean completed = false;
            try {
                census.recordWrite(brain);
                census.recordWrite(brain);
                census.recordWrite(brain);
                throw new IllegalStateException("replay failure");
            } finally {
                census.endTick(brain, !completed);
            }
        });

        assertEquals(Map.of(3, 1L), census.writeCountDistribution(brain));
        assertEquals(Map.of(1, 1L), census.streakDistribution(brain));
        assertEquals(1L, census.failedTicks(brain));
    }

    @Test
    void clearDropsCompletedAndActiveTickState() {
        BrainMemoryWriteCensus.Collector census = new BrainMemoryWriteCensus.Collector();
        Object brain = new Object();
        tick(census, brain, 2, false);
        census.beginTick(brain);
        census.recordWrite(brain);
        census.clear();

        assertEquals(Map.of(), census.writeCountDistribution(brain));
        assertEquals(Map.of(), census.streakDistribution(brain));
        assertThrows(IllegalStateException.class, () -> census.endTick(brain, false));
    }

    private static void tick(BrainMemoryWriteCensus.Collector census, Object brain, int writes, boolean failed) {
        census.beginTick(brain);
        for (int i = 0; i < writes; i++) census.recordWrite(brain);
        census.endTick(brain, failed);
    }

    private static final class EqualObject {
        @Override public boolean equals(Object other) { return other instanceof EqualObject; }
        @Override public int hashCode() { return 1; }
    }
}
