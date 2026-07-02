package com.latticemc.lattice.nativelib;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseChunk;

public final class WorldStateSnapshot {
    private static final int SIZE = 16;

    private final Holder<Biome>[] biomes;
    private final int[] biomeIds;
    private final int[] surfaceTop;
    private final int[] preliminarySurfaceBase;

    private WorldStateSnapshot(Holder<Biome>[] biomes, int[] biomeIds, int[] surfaceTop, int[] preliminarySurfaceBase) {
        this.biomes = biomes;
        this.biomeIds = biomeIds;
        this.surfaceTop = surfaceTop;
        this.preliminarySurfaceBase = preliminarySurfaceBase;
    }

    @SuppressWarnings("unchecked")
    public static WorldStateSnapshot create(ChunkAccess chunk,
                                            BiomeManager biomeManager,
                                            NoiseChunk noiseChunk,
                                            Registry<Biome> biomeRegistry,
                                            boolean useLegacyRandomSource) {
        Holder<Biome>[] biomes = new Holder[SIZE * SIZE];
        int[] biomeIds = new int[SIZE * SIZE];
        int[] surfaceTop = new int[SIZE * SIZE];
        int[] preliminarySurfaceBase = new int[SIZE * SIZE];

        ChunkPos pos = chunk.getPos();
        int minBlockX = pos.getMinBlockX();
        int minBlockZ = pos.getMinBlockZ();
        int cellX = minBlockX >> 4;
        int cellZ = minBlockZ >> 4;
        int s00 = noiseChunk.preliminarySurfaceLevel(cellX << 4, cellZ << 4);
        int s10 = noiseChunk.preliminarySurfaceLevel((cellX + 1) << 4, cellZ << 4);
        int s01 = noiseChunk.preliminarySurfaceLevel(cellX << 4, (cellZ + 1) << 4);
        int s11 = noiseChunk.preliminarySurfaceLevel((cellX + 1) << 4, (cellZ + 1) << 4);
        BlockPos.MutableBlockPos topPos = new BlockPos.MutableBlockPos();

        for (int lx = 0; lx < SIZE; lx++) {
            for (int lz = 0; lz < SIZE; lz++) {
                int index = index(lx, lz);
                int x = minBlockX + lx;
                int z = minBlockZ + lz;
                int top = chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, lx, lz) + 1;
                Holder<Biome> biome = biomeManager.getBiome(topPos.set(x, useLegacyRandomSource ? 0 : top, z));

                biomes[index] = biome;
                biomeIds[index] = biomeRegistry.getId(biome.value());
                surfaceTop[index] = top;
                preliminarySurfaceBase[index] = preliminarySurfaceBase(x, z, s00, s10, s01, s11);
            }
        }

        return new WorldStateSnapshot(biomes, biomeIds, surfaceTop, preliminarySurfaceBase);
    }

    public Holder<Biome> biome(int lx, int lz) {
        return biomes[index(lx, lz)];
    }

    public int biomeId(int lx, int lz) {
        return biomeIds[index(lx, lz)];
    }

    public int surfaceTop(int lx, int lz) {
        return surfaceTop[index(lx, lz)];
    }

    public int minSurfaceLevel(int lx, int lz, int surfaceDepth) {
        return preliminarySurfaceBase[index(lx, lz)] + surfaceDepth - 8;
    }

    private static int preliminarySurfaceBase(int blockX, int blockZ, int s00, int s10, int s01, int s11) {
        double tx = (blockX & 15) / 16.0;
        double tz = (blockZ & 15) / 16.0;
        double a = s00 + (s10 - s00) * tx;
        double b = s01 + (s11 - s01) * tx;
        return (int) Math.floor(a + (b - a) * tz);
    }

    private static int index(int lx, int lz) {
        return (lx << 4) | lz;
    }
}
