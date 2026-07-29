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
        NativePathfinder.recordUnsupportedPathType(PathType.TRAPDOOR);
        NativePathfinder.recordUnsupportedPathType(PathType.TRAPDOOR);
        NativePathfinder.recordRawPathTypeCache(7, 3, 1);

        String status = NativePathfinder.stats();
        assertTrue(status.contains("rejects={smallRegion=1, emptyResult=1, pathTypes=TRAPDOOR=2}"));
        assertTrue(status.contains("rawPathTypeCache=7/3/1"));

        NativePathfinder.resetStats();
        assertTrue(NativePathfinder.stats().contains("rejects={smallRegion=0, emptyResult=0, pathTypes=none}"));
        assertTrue(NativePathfinder.stats().contains("rawPathTypeCache=0/0/0"));
    }
}
