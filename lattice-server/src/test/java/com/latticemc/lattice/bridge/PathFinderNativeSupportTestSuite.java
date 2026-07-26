package com.latticemc.lattice.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
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

    @Test
    void describesFirstPathDifferenceForVerifyFallback() {
        Path nativePath = path(new Node(0, 64, 0), new Node(1, 64, 0));
        Path matchingPath = path(new Node(0, 64, 0), new Node(1, 64, 0));
        Path differentPath = path(new Node(0, 64, 0), new Node(1, 63, 0));

        assertNull(PathFinderNativeSupport.pathMismatch(nativePath, matchingPath));
        assertTrue(PathFinderNativeSupport.pathMismatch(nativePath, differentPath).contains("node[1] native=1,64,0 vanilla=1,63,0"));
    }

    @Test
    void selectsMobPathTypeFromVerticalTypeSetLikeVanilla() {
        PathType[] values = PathType.values();
        assertTrue(values.length <= Long.SIZE);
        float[] malus = new float[values.length];
        for (PathType type : values) malus[type.ordinal()] = type.getMalus();

        for (PathType bottomRaw : values) {
            for (PathType first : values) {
                for (PathType second : values) {
                    long types = 1L << first.ordinal() | 1L << second.ordinal();
                    assertEquals(selectVanilla(types, bottomRaw, malus),
                            PathFinderNativeSupport.selectPathType(types, bottomRaw, malus),
                            bottomRaw + "/" + first + "/" + second);
                }
            }
        }
    }

    private static PathType selectVanilla(long types, PathType bottomRawType, float[] malus) {
        EnumSet<PathType> set = EnumSet.noneOf(PathType.class);
        for (PathType type : PathType.values()) {
            if ((types & 1L << type.ordinal()) != 0L) set.add(type);
        }
        if (set.contains(PathType.FENCE)) return PathType.FENCE;
        if (set.contains(PathType.UNPASSABLE_RAIL)) return PathType.UNPASSABLE_RAIL;

        PathType selected = PathType.BLOCKED;
        for (PathType type : set) {
            if (malus[type.ordinal()] < 0.0F) return type;
            if (malus[type.ordinal()] >= malus[selected.ordinal()]) selected = type;
        }
        return selected != PathType.OPEN && malus[selected.ordinal()] == 0.0F && bottomRawType == PathType.OPEN
                ? PathType.OPEN
                : selected;
    }

    private static Path path(Node... nodes) {
        return new Path(List.of(nodes), new BlockPos(1, 64, 0), true);
    }
}
