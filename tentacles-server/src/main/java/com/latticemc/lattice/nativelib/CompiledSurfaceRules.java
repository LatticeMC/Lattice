package com.latticemc.lattice.nativelib;

import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

public final class CompiledSurfaceRules implements AutoCloseable {
    private final NativeMaterialRules rules;
    private final List<Map.Entry<String, NormalNoise>> namedNoises;
    private final int[] ints = new int[10];
    private final byte[] bools = new byte[2];
    private double[] doubles;

    public CompiledSurfaceRules(NativeMaterialRules rules, List<Map.Entry<String, NormalNoise>> namedNoises) {
        this.rules = rules;
        this.namedNoises = namedNoises;
        this.doubles = new double[3 + namedNoises.size()];
    }

    public BlockState tryApply(SurfaceSystemAccess system,
                               ChunkAccess chunk,
                               Holder<Biome> biome,
                               int x,
                               int y,
                               int z,
                               int fluidHeight,
                               int stoneDepthFloor,
                               int stoneDepthCeiling,
                               int surfaceDepth,
                               int minSurfaceLevel,
                               boolean hole,
                               boolean steepSlope) {
        ints[0] = x;
        ints[1] = y;
        ints[2] = z;
        ints[3] = chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x & 15, z & 15) + 1;
        ints[4] = fluidHeight;
        ints[5] = stoneDepthFloor;
        ints[6] = stoneDepthCeiling;
        ints[7] = system.biomeId(biome.value());
        ints[8] = surfaceDepth;
        ints[9] = minSurfaceLevel;

        doubles[0] = biome.value().coldEnoughToSnow(new BlockPos(x, y, z), system.seaLevel()) ? 0.0 : 1.0;
        doubles[1] = system.surfaceNoiseValue(x, z);
        doubles[2] = system.surfaceSecondaryValue(x, z);
        for (int i = 0; i < namedNoises.size(); ++i) {
            doubles[3 + i] = namedNoises.get(i).getValue().getValue(x, 0.0, z);
        }

        bools[0] = (byte) (hole ? 1 : 0);
        bools[1] = (byte) (steepSlope ? 1 : 0);

        int id = rules.evaluate(ints, doubles, bools);
        if (id == NativeMaterialRules.NO_MATCH) return null;
        if (id == NativeMaterialRules.BANDLANDS_SENTINEL) return system.bandlands(x, y, z);
        return Block.stateById(id);
    }

    @Override
    public void close() {
        rules.close();
    }
}
