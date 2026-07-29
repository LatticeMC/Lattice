package com.latticemc.lattice.bridge;

import java.util.Set;
import net.minecraft.core.BlockPos;

public final class PathfinderBuffers {
    private byte[] pathTypes = new byte[0];
    private float[] floorLevels = new float[0];
    private final RawPathTypeCache rawPathTypes = new RawPathTypeCache();
    private BlockPos[] targets = new BlockPos[0];
    private int[] targetX = new int[0];
    private int[] targetY = new int[0];
    private int[] targetZ = new int[0];
    private int[] outPath = new int[0];

    public int copyTargets(Set<BlockPos> source) {
        if (this.targets.length < source.size()) {
            this.targets = new BlockPos[source.size()];
        }
        int index = 0;
        for (BlockPos target : source) {
            this.targets[index++] = target;
        }
        return index;
    }

    public BlockPos target(int index) {
        return this.targets[index];
    }

    public byte[] pathTypes(int required) {
        if (this.pathTypes.length < required) {
            this.pathTypes = new byte[required];
        }
        return this.pathTypes;
    }

    public float[] floorLevels(int required) {
        if (this.floorLevels.length < required) {
            this.floorLevels = new float[required];
        }
        return this.floorLevels;
    }

    public RawPathTypeCache rawPathTypes(int minX, int minY, int minZ, int sizeX, int sizeY, int sizeZ) {
        this.rawPathTypes.reset(minX, minY, minZ, sizeX, sizeY, sizeZ);
        return this.rawPathTypes;
    }

    public int[] targetX(int required) {
        if (this.targetX.length < required) {
            this.targetX = new int[required];
        }
        return this.targetX;
    }

    public int[] targetY(int required) {
        if (this.targetY.length < required) {
            this.targetY = new int[required];
        }
        return this.targetY;
    }

    public int[] targetZ(int required) {
        if (this.targetZ.length < required) {
            this.targetZ = new int[required];
        }
        return this.targetZ;
    }

    public int[] outPath(int maxVisitedNodes) {
        int required = Math.addExact(3, Math.multiplyExact(maxVisitedNodes, 3));
        if (this.outPath.length < required) {
            this.outPath = new int[required];
        }
        return this.outPath;
    }

    static final class RawPathTypeCache {
        private byte[] values = new byte[0];
        private int[] stamps = new int[0];
        private int generation;
        private int minX;
        private int minY;
        private int minZ;
        private int sizeX;
        private int sizeY;
        private int sizeZ;
        private int hits;
        private int misses;
        private int outside;

        private void reset(int minX, int minY, int minZ, int sizeX, int sizeY, int sizeZ) {
            int required = Math.multiplyExact(Math.multiplyExact(sizeX, sizeY), sizeZ);
            if (this.values.length < required) {
                this.values = new byte[required];
                this.stamps = new int[required];
            }
            if (++this.generation == 0) {
                java.util.Arrays.fill(this.stamps, 0);
                this.generation = 1;
            }
            this.minX = minX;
            this.minY = minY;
            this.minZ = minZ;
            this.sizeX = sizeX;
            this.sizeY = sizeY;
            this.sizeZ = sizeZ;
            this.hits = 0;
            this.misses = 0;
            this.outside = 0;
        }

        int get(int x, int y, int z) {
            int index = this.index(x, y, z);
            if (index < 0) {
                this.outside++;
                return -1;
            }
            if (this.stamps[index] != this.generation) {
                this.misses++;
                return -1;
            }
            this.hits++;
            return Byte.toUnsignedInt(this.values[index]);
        }

        void put(int x, int y, int z, int ordinal) {
            int index = this.index(x, y, z);
            if (index < 0) return;
            this.values[index] = (byte)ordinal;
            this.stamps[index] = this.generation;
        }

        int hits() {
            return this.hits;
        }

        int misses() {
            return this.misses;
        }

        int outside() {
            return this.outside;
        }

        private int index(int x, int y, int z) {
            int localX = x - this.minX;
            int localY = y - this.minY;
            int localZ = z - this.minZ;
            if (localX < 0 || localX >= this.sizeX
                    || localY < 0 || localY >= this.sizeY
                    || localZ < 0 || localZ >= this.sizeZ) {
                return -1;
            }
            return (localY * this.sizeZ + localZ) * this.sizeX + localX;
        }
    }
}
