package com.latticemc.lattice.nativelib;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import org.junit.jupiter.api.Test;

class PathfinderAuditParityTestSuite {
    @Test
    void rejectsSameHeaderCoordinateMutationBeforeNextBufferReuse() {
        Path expected = new Path(List.of(new Node(2, 0, 2), new Node(3, 0, 2)), new BlockPos(3, 0, 2), true);
        int[] shared = {2, 1, 0, 2, 0, 2, 3, 0, 2};
        NativePathfinder.PathResult result = new NativePathfinder.PathResult(shared, 2, true, 0);
        PathfinderAuditParity.verify(expected, result);
        shared[6] = 4;
        assertThrows(AssertionError.class, () -> PathfinderAuditParity.verify(expected, result));
    }

    @Test
    void rejectsWrongTargetAndReachState() {
        Path expected = new Path(List.of(new Node(2, 0, 2)), new BlockPos(2, 0, 2), true);
        int[] raw = {1, 1, 0, 2, 0, 2};
        assertThrows(AssertionError.class,
                () -> PathfinderAuditParity.verify(expected, new NativePathfinder.PathResult(raw, 1, true, 1)));
        assertThrows(AssertionError.class,
                () -> PathfinderAuditParity.verify(expected, new NativePathfinder.PathResult(raw, 1, false, 0)));
    }
}
