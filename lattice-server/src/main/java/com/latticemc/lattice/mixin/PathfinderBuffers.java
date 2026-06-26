package com.latticemc.lattice.mixin;

import java.util.Set;
import net.minecraft.core.BlockPos;

public final class PathfinderBuffers {
    private byte[] pathTypes = new byte[0];
    private BlockPos[] targets = new BlockPos[0];
    private int[] targetX = new int[0];
    private int[] targetY = new int[0];
    private int[] targetZ = new int[0];
    private int[] outPath = new int[0];

    int copyTargets(Set<BlockPos> source) {
        if (this.targets.length < source.size()) {
            this.targets = new BlockPos[source.size()];
        }
        int index = 0;
        for (BlockPos target : source) {
            this.targets[index++] = target;
        }
        return index;
    }

    BlockPos target(int index) {
        return this.targets[index];
    }

    byte[] pathTypes(int required) {
        if (this.pathTypes.length < required) {
            this.pathTypes = new byte[required];
        }
        return this.pathTypes;
    }

    int[] targetX(int required) {
        if (this.targetX.length < required) {
            this.targetX = new int[required];
        }
        return this.targetX;
    }

    int[] targetY(int required) {
        if (this.targetY.length < required) {
            this.targetY = new int[required];
        }
        return this.targetY;
    }

    int[] targetZ(int required) {
        if (this.targetZ.length < required) {
            this.targetZ = new int[required];
        }
        return this.targetZ;
    }

    int[] outPath(int maxVisitedNodes) {
        int required = Math.addExact(3, Math.multiplyExact(maxVisitedNodes, 3));
        if (this.outPath.length < required) {
            this.outPath = new int[required];
        }
        return this.outPath;
    }
}
