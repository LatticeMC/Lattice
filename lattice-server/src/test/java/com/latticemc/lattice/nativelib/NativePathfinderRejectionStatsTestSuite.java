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
        NativePathfinder.recordComparableNativeNanos(5_000L);
        NativePathfinder.recordVanillaNanos(7_000L);
        NativePathfinder.recordShadowVanillaNanos(11_000L);
        NativePathfinder.recordStateSnapshot(13_000L, 17, 3, false);
        NativePathfinder.recordShortPathGate();

        String status = NativePathfinder.stats();
        assertTrue(status.contains("rejects={smallRegion=1, emptyResult=1, pathTypes=TRAPDOOR=2}"));
        assertTrue(status.contains("rawPathTypeCache=7/3/1"));
        assertTrue(status.contains("comparableNative=1/5"));
        assertTrue(status.contains("vanilla=1/7"));
        assertTrue(status.contains("shadowVanilla=1/11"));
        assertTrue(status.contains("stateSnapshot=1/13/17/3/1"));
        assertTrue(status.contains("shortPathGates=1"));

        NativePathfinder.resetStats();
        assertTrue(NativePathfinder.stats().contains("rejects={smallRegion=0, emptyResult=0, pathTypes=none}"));
        assertTrue(NativePathfinder.stats().contains("rawPathTypeCache=0/0/0"));
        assertTrue(NativePathfinder.stats().contains("comparableNative=0/0"));
        assertTrue(NativePathfinder.stats().contains("vanilla=0/0"));
        assertTrue(NativePathfinder.stats().contains("shadowVanilla=0/0"));
        assertTrue(NativePathfinder.stats().contains("stateSnapshot=0/0/0/0/0"));
        assertTrue(NativePathfinder.stats().contains("shortPathGates=0"));
    }
}
