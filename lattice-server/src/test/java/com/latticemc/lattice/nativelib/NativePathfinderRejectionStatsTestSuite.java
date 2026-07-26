package com.latticemc.lattice.nativelib;

import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.world.level.pathfinder.PathType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class NativePathfinderRejectionStatsTestSuite {
    @AfterEach
    void resetStats() {
        NativePathfinder.resetStats();
    }

    @Test
    void reportsAndResetsNativeRejectionCauses() {
        NativePathfinder.recordRegionTooSmall();
        NativePathfinder.recordEmptyResult();
        NativePathfinder.recordWaterPathResult();
        NativePathfinder.recordUnsupportedPathType(PathType.TRAPDOOR);
        NativePathfinder.recordUnsupportedPathType(PathType.TRAPDOOR);

        String status = NativePathfinder.stats();
        assertTrue(status.contains("rejects={smallRegion=1, emptyResult=1, waterPath=1, pathTypes=TRAPDOOR=2}"));

        NativePathfinder.resetStats();
        assertTrue(NativePathfinder.stats().contains("rejects={smallRegion=0, emptyResult=0, waterPath=0, pathTypes=none}"));
    }
}
