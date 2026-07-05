package com.latticemc.lattice.nativelib;

import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

public final class CompiledSurfaceRules implements AutoCloseable {
    private final NativeMaterialRules rules;
    private final List<Map.Entry<String, NormalNoise>> namedNoises;
    private final int namedNoiseCount;

    public CompiledSurfaceRules(NativeMaterialRules rules, List<Map.Entry<String, NormalNoise>> namedNoises) {
        this.rules = rules;
        this.namedNoises = namedNoises;
        this.namedNoiseCount = namedNoises.size();
    }

    public int getDoublesLength() { return 3 + namedNoiseCount; }

    public double getNamedNoiseValue(int index, int x, int z) {
        return namedNoises.get(index).getValue().getValue(x, 0.0, z);
    }

    public int getNamedNoiseCount() {
        return namedNoiseCount;
    }

    public BlockState tryApply(SurfaceSystemAccess system,
                               Holder<Biome> biome,
                               int x,
                               int y,
                               int z,
                               int surfaceTop,
                               int fluidHeight,
                               int stoneDepthFloor,
                               int stoneDepthCeiling,
                               int surfaceDepth,
                               int minSurfaceLevel,
                               boolean hole,
                               boolean steepSlope,
                               int[] ints,
                               double surfaceNoise,
                               double surfaceSecondaryNoise,
                               double[] namedNoiseValues,
                               byte[] bools) {
        ints[0] = x;
        ints[1] = y;
        ints[2] = z;
        ints[3] = surfaceTop;
        ints[4] = fluidHeight;
        ints[5] = stoneDepthFloor;
        ints[6] = stoneDepthCeiling;
        ints[7] = system.biomeId(biome.value());
        ints[8] = surfaceDepth;
        ints[9] = minSurfaceLevel;

        double temperature = biome.value().coldEnoughToSnow(new BlockPos(x, y, z), system.seaLevel()) ? 0.0 : 1.0;

        bools[0] = (byte) (hole ? 1 : 0);
        bools[1] = (byte) (steepSlope ? 1 : 0);

        int id = rules.evaluate(ints, temperature, surfaceNoise, surfaceSecondaryNoise, namedNoiseValues, bools);
        if (id == NativeMaterialRules.NO_MATCH) return null;
        if (id == NativeMaterialRules.BANDLANDS_SENTINEL) return system.bandlands(x, y, z);
        return Block.stateById(id);
    }

    public int[] tryApplyBatch(SurfaceSystemAccess system,
                               int biomeId,
                               int x,
                               int z,
                               int surfaceTop,
                               int surfaceDepth,
                               int minSurfaceLevel,
                               boolean hole,
                               boolean steepSlope,
                               double surfaceNoise,
                               double surfaceSecondaryNoise,
                               double[] namedNoiseValues,
                               int[] columnCtx,
                               byte[] bools,
                               int count,
                               int[] blockData) {
        columnCtx[0] = x;
        columnCtx[1] = z;
        columnCtx[2] = surfaceTop;
        columnCtx[3] = biomeId;
        columnCtx[4] = surfaceDepth;
        columnCtx[5] = minSurfaceLevel;
        bools[0] = (byte) (hole ? 1 : 0);
        bools[1] = (byte) (steepSlope ? 1 : 0);

        return rules.evaluateBatch(
                count,
                columnCtx,
                surfaceNoise,
                surfaceSecondaryNoise,
                namedNoiseValues,
                bools,
                blockData);
    }

    public void appendBatchBlockData(SurfaceSystemAccess system,
                                     Holder<Biome> biome,
                                     int x,
                                     int y,
                                     int z,
                                     int fluidHeight,
                                     int stoneDepthFloor,
                                     int stoneDepthCeiling,
                                     int[] blockData,
                                     int index,
                                     BlockPos.MutableBlockPos mutablePos) {
        int base = index * 5;
        blockData[base] = y;
        blockData[base + 1] = fluidHeight;
        blockData[base + 2] = stoneDepthFloor;
        blockData[base + 3] = stoneDepthCeiling;
        blockData[base + 4] = biome.value().coldEnoughToSnow(mutablePos.set(x, y, z), system.seaLevel()) ? 1 : 0;
    }

    public void appendBatchBlockData(int seaLevel,
                                     Holder<Biome> biome,
                                     int x,
                                     int y,
                                     int z,
                                     int fluidHeight,
                                     int stoneDepthFloor,
                                     int stoneDepthCeiling,
                                     int[] blockData,
                                     int index,
                                     BlockPos.MutableBlockPos mutablePos) {
        int base = index * 5;
        blockData[base] = y;
        blockData[base + 1] = fluidHeight;
        blockData[base + 2] = stoneDepthFloor;
        blockData[base + 3] = stoneDepthCeiling;
        blockData[base + 4] = biome.value().coldEnoughToSnow(mutablePos.set(x, y, z), seaLevel) ? 1 : 0;
    }

    @Override
    public void close() {
        rules.close();
    }
}
