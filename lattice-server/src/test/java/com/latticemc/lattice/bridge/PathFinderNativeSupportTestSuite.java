package com.latticemc.lattice.bridge;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.latticemc.lattice.nativelib.NativePathfinder;
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
    void acceptsWaterForDirectWalkNodeEvaluatorOnly() {
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
    void detectsWaterAndOutOfBoundsNodesInNativeResult() {
        NativePathfinder.PathResult waterPath = result(new Node(0, 64, 0), new Node(1, 64, 0));
        NativePathfinder.PathResult dryPath = result(new Node(0, 64, 0));
        byte[] pathTypes = {(byte)PathType.WALKABLE.ordinal(), (byte)PathType.WATER.ordinal()};

        assertTrue(PathFinderNativeSupport.usesWater(waterPath, pathTypes, 0, 64, 0, 2, 1, 1));
        assertFalse(PathFinderNativeSupport.usesWater(dryPath, pathTypes, 0, 64, 0, 2, 1, 1));
        assertTrue(PathFinderNativeSupport.usesWater(result(new Node(2, 64, 0)), pathTypes, 0, 64, 0, 2, 1, 1));
    }

    private static Path path(Node... nodes) {
        return new Path(List.of(nodes), new BlockPos(1, 64, 0), true);
    }

    private static NativePathfinder.PathResult result(Node... nodes) {
        int[] encoded = new int[3 + nodes.length * 3];
        for (int i = 0; i < nodes.length; ++i) {
            int offset = 3 + i * 3;
            encoded[offset] = nodes[i].x;
            encoded[offset + 1] = nodes[i].y;
            encoded[offset + 2] = nodes[i].z;
        }
        return new NativePathfinder.PathResult(encoded, nodes.length, true, 0);
    }
}
