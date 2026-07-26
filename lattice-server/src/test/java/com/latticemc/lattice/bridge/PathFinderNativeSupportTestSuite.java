package com.latticemc.lattice.bridge;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import net.minecraft.world.level.pathfinder.PathType;
import org.junit.jupiter.api.Test;

class PathFinderNativeSupportTestSuite {
    private static final EnumSet<PathType> SAFE_NON_NEGATIVE_TYPES = EnumSet.of(
            PathType.BLOCKED,
            PathType.OPEN,
            PathType.WALKABLE,
            PathType.DOOR_OPEN,
            PathType.WALKABLE_DOOR,
            PathType.COCOA
    );

    @Test
    void acceptsEveryNegativeMalusAsBlocked() {
        for (PathType type : PathType.values()) {
            assertTrue(PathFinderNativeSupport.isNativePathTypeSupported(type, -1.0F, false), type.name());
        }
    }

    @Test
    void acceptsExistingSafeNonNegativeTypes() {
        for (PathType type : SAFE_NON_NEGATIVE_TYPES) {
            assertTrue(PathFinderNativeSupport.isNativePathTypeSupported(type, 0.0F, false), type.name());
        }
    }

    @Test
    void acceptsWaterOnlyWhenEvaluatorCanFloat() {
        assertFalse(PathFinderNativeSupport.isNativePathTypeSupported(PathType.WATER, 8.0F, false));
        assertTrue(PathFinderNativeSupport.isNativePathTypeSupported(PathType.WATER, 8.0F, true));
    }

    @Test
    void rejectsUnsupportedNonNegativeTypes() {
        for (PathType type : PathType.values()) {
            if (SAFE_NON_NEGATIVE_TYPES.contains(type) || type == PathType.WATER) continue;
            assertFalse(PathFinderNativeSupport.isNativePathTypeSupported(type, 0.0F, true), type.name());
        }
    }
}
