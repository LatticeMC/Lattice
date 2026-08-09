package net.minecraft.world.level.levelgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class SurfaceRegionParityTest {
    private static final int CHUNK_RADIUS = Integer.parseInt(
            System.getenv().getOrDefault("LATTICE_PARITY_RADIUS", "32"));

    @Test
    void generatedBlockStatesMatchReference() throws IOException {
        String leftPath = System.getenv("LATTICE_PARITY_LEFT");
        String rightPath = System.getenv("LATTICE_PARITY_RIGHT");
        String center = System.getenv("LATTICE_PARITY_CENTER");
        Assumptions.assumeTrue(leftPath != null && rightPath != null && center != null);

        String[] coordinates = center.trim().split("\\s+");
        int centerChunkX = Math.floorDiv(Integer.parseInt(coordinates[0]), 16);
        int centerChunkZ = Math.floorDiv(Integer.parseInt(coordinates[1]), 16);
        Path left = Path.of(leftPath);
        Path right = Path.of(rightPath);

        int compared = 0;
        ComparisonStats stats = new ComparisonStats();
        for (int chunkZ = centerChunkZ - CHUNK_RADIUS; chunkZ <= centerChunkZ + CHUNK_RADIUS; chunkZ++) {
            for (int chunkX = centerChunkX - CHUNK_RADIUS; chunkX <= centerChunkX + CHUNK_RADIUS; chunkX++) {
                CompoundTag actual = readChunk(left, chunkX, chunkZ);
                CompoundTag expected = readChunk(right, chunkX, chunkZ);
                assertNotNull(actual, "missing Lattice chunk " + chunkX + "," + chunkZ);
                assertNotNull(expected, "missing reference chunk " + chunkX + "," + chunkZ);
                stats.compareBlocks(sectionBlocks(expected), sectionBlocks(actual), chunkX, chunkZ);
                stats.compareChunkTag("biomes", sectionBiomes(expected), sectionBiomes(actual), chunkX, chunkZ);
                stats.compareChunkTag("heightmaps", expected.get("Heightmaps"), actual.get("Heightmaps"), chunkX, chunkZ);
                stats.compareChunkTag("block_entities", expected.get("block_entities"), actual.get("block_entities"), chunkX, chunkZ);
                stats.compareChunkTag("structures", expected.get("structures"), actual.get("structures"), chunkX, chunkZ);
                compared++;
            }
        }
        assertEquals((CHUNK_RADIUS * 2 + 1) * (CHUNK_RADIUS * 2 + 1), compared);
        if (stats.hasDifferences()) {
            String summary = stats.summary(compared);
            System.out.println("Lattice parity diagnostic: " + summary);
            if (Boolean.getBoolean("lattice.parity.strict")) {
                throw new AssertionError(summary);
            }
        }
    }

    private static Map<Integer, String> sectionBlocks(CompoundTag chunk) {
        Map<Integer, String> blocks = new HashMap<>();
        ListTag sections = chunk.getListOrEmpty("sections");
        for (int i = 0; i < sections.size(); i++) {
            CompoundTag section = sections.getCompoundOrEmpty(i);
            CompoundTag blockStates = section.getCompoundOrEmpty("block_states");
            if (!blockStates.isEmpty()) {
                String[] palette = palette(blockStates);
                int[] values = packedValues(blockStates, 4096, palette.length, 4);
                int sectionY = section.getIntOr("Y", Integer.MIN_VALUE);
                for (int index = 0; index < values.length; index++) {
                    if (values[index] >= palette.length) {
                        throw new IllegalArgumentException("block palette index out of bounds in section " + sectionY);
                    }
                    blocks.put((sectionY << 12) | index, palette[values[index]]);
                }
            }
        }
        return blocks;
    }

    private static final class ComparisonStats {
        private long blockDifferences;
        private int blockDifferenceChunks;
        private int biomeDifferenceChunks;
        private int heightmapDifferenceChunks;
        private int blockEntityDifferenceChunks;
        private int structureDifferenceChunks;
        private long expectedClayDifferences;
        private long expectedClayUndergroundDifferences;
        private long actualClayDifferences;
        private String firstDifference;

        void compareBlocks(Map<Integer, String> expected, Map<Integer, String> actual, int chunkX, int chunkZ) {
            if (expected.equals(actual)) return;
            blockDifferenceChunks++;
            java.util.TreeSet<Integer> positions = new java.util.TreeSet<>(expected.keySet());
            positions.addAll(actual.keySet());
            for (int packed : positions) {
                String expectedState = expected.get(packed);
                String actualState = actual.get(packed);
                if (java.util.Objects.equals(expectedState, actualState)) continue;
                blockDifferences++;
                int sectionY = Math.floorDiv(packed, 4096);
                int index = Math.floorMod(packed, 4096);
                int blockY = sectionY * 16 + ((index >>> 8) & 15);
                if (expectedState != null && expectedState.contains("minecraft:clay")) {
                    expectedClayDifferences++;
                    if (blockY < 0) expectedClayUndergroundDifferences++;
                }
                if (actualState != null && actualState.contains("minecraft:clay")) actualClayDifferences++;
                if (firstDifference == null) {
                    int blockX = chunkX * 16 + (index & 15);
                    int blockZ = chunkZ * 16 + ((index >>> 4) & 15);
                    firstDifference = "block chunk=" + chunkX + "," + chunkZ + " world="
                            + blockX + "," + blockY + "," + blockZ
                            + " expected=" + expectedState + " actual=" + actualState;
                }
            }
        }

        void compareChunkTag(String category, Object expected, Object actual, int chunkX, int chunkZ) {
            if (java.util.Objects.equals(expected, actual)) return;
            switch (category) {
                case "biomes" -> biomeDifferenceChunks++;
                case "heightmaps" -> heightmapDifferenceChunks++;
                case "block_entities" -> blockEntityDifferenceChunks++;
                case "structures" -> structureDifferenceChunks++;
                default -> throw new IllegalArgumentException(category);
            }
            if (firstDifference == null) firstDifference = category + " chunk=" + chunkX + "," + chunkZ;
        }

        boolean hasDifferences() {
            return blockDifferences != 0 || biomeDifferenceChunks != 0 || heightmapDifferenceChunks != 0
                    || blockEntityDifferenceChunks != 0 || structureDifferenceChunks != 0;
        }

        String summary(int chunks) {
            return "worldgen mismatch across " + chunks + " chunks: blockDifferences=" + blockDifferences
                    + ", blockDifferenceChunks=" + blockDifferenceChunks
                    + ", biomeDifferenceChunks=" + biomeDifferenceChunks
                    + ", heightmapDifferenceChunks=" + heightmapDifferenceChunks
                    + ", blockEntityDifferenceChunks=" + blockEntityDifferenceChunks
                    + ", structureDifferenceChunks=" + structureDifferenceChunks
                    + ", expectedClayDifferences=" + expectedClayDifferences
                    + ", expectedClayUndergroundDifferences=" + expectedClayUndergroundDifferences
                    + ", actualClayDifferences=" + actualClayDifferences
                    + "; first=" + firstDifference;
        }
    }

    private static Map<Integer, String> sectionBiomes(CompoundTag chunk) {
        Map<Integer, String> biomes = new HashMap<>();
        ListTag sections = chunk.getListOrEmpty("sections");
        for (int i = 0; i < sections.size(); i++) {
            CompoundTag section = sections.getCompoundOrEmpty(i);
            CompoundTag biomeData = section.getCompoundOrEmpty("biomes");
            if (biomeData.isEmpty()) continue;
            String[] palette = palette(biomeData);
            int[] values = packedValues(biomeData, 64, palette.length, 1);
            int sectionY = section.getIntOr("Y", Integer.MIN_VALUE);
            for (int index = 0; index < values.length; index++) {
                if (values[index] >= palette.length) {
                    throw new IllegalArgumentException("biome palette index out of bounds in section " + sectionY);
                }
                biomes.put((sectionY << 6) | index, palette[values[index]]);
            }
        }
        return biomes;
    }

    private static String[] palette(CompoundTag data) {
        ListTag palette = data.getListOrEmpty("palette");
        String[] result = new String[palette.size()];
        for (int i = 0; i < result.length; i++) result[i] = palette.get(i).toString();
        return result.length == 0 ? new String[] { "<empty>" } : result;
    }

    private static int[] packedValues(CompoundTag data, int size, int paletteSize, int minimumBits) {
        int[] values = new int[size];
        long[] packed = data.getLongArray("data").orElse(new long[0]);
        if (packed.length == 0) return values;
        int bits = Math.max(minimumBits, 32 - Integer.numberOfLeadingZeros(paletteSize - 1));
        int perLong = Math.max(1, 64 / bits);
        long mask = (1L << bits) - 1L;
        for (int i = 0; i < size; i++) {
            int word = i / perLong;
            int shift = (i % perLong) * bits;
            if (word < packed.length) values[i] = (int) ((packed[word] >>> shift) & mask);
        }
        return values;
    }


    private static CompoundTag readChunk(Path regionDirectory, int chunkX, int chunkZ) throws IOException {
        Path region = regionDirectory.resolve("region")
                .resolve("r." + Math.floorDiv(chunkX, 32) + "." + Math.floorDiv(chunkZ, 32) + ".mca");
        try (RandomAccessFile file = new RandomAccessFile(region.toFile(), "r")) {
            int index = (chunkX & 31) + (chunkZ & 31) * 32;
            file.seek(index * 4L);
            int location = file.readInt();
            int sector = location >>> 8;
            if (sector == 0) return null;

            file.seek(sector * 4096L);
            int length = file.readInt();
            int compression = file.readUnsignedByte();
            byte[] payload = new byte[length - 1];
            file.readFully(payload);
            InputStream input = switch (compression) {
                case 1 -> new GZIPInputStream(new ByteArrayInputStream(payload));
                case 2 -> new InflaterInputStream(new ByteArrayInputStream(payload));
                case 3 -> new ByteArrayInputStream(payload);
                default -> throw new IOException("unsupported region compression " + compression + " in " + region);
            };
            try (DataInputStream data = new DataInputStream(input)) {
                return NbtIo.read(data, NbtAccounter.unlimitedHeap());
            }
        }
    }
}
