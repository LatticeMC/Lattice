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
    private static final int CHUNK_RADIUS = 32;

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
        for (int chunkZ = centerChunkZ - CHUNK_RADIUS; chunkZ <= centerChunkZ + CHUNK_RADIUS; chunkZ++) {
            for (int chunkX = centerChunkX - CHUNK_RADIUS; chunkX <= centerChunkX + CHUNK_RADIUS; chunkX++) {
                CompoundTag actual = readChunk(left, chunkX, chunkZ);
                CompoundTag expected = readChunk(right, chunkX, chunkZ);
                assertNotNull(actual, "missing Lattice chunk " + chunkX + "," + chunkZ);
                assertNotNull(expected, "missing reference chunk " + chunkX + "," + chunkZ);
                assertEquals(sectionBlocks(expected), sectionBlocks(actual), "block states differ at " + chunkX + "," + chunkZ);
                assertEquals(expected.get("Heightmaps"), actual.get("Heightmaps"), "heightmaps differ at " + chunkX + "," + chunkZ);
                compared++;
            }
        }
        assertEquals(4225, compared);
    }

    private static Map<Integer, Tag> sectionBlocks(CompoundTag chunk) {
        Map<Integer, Tag> blocks = new HashMap<>();
        ListTag sections = chunk.getListOrEmpty("sections");
        for (int i = 0; i < sections.size(); i++) {
            CompoundTag section = sections.getCompoundOrEmpty(i);
            Tag blockStates = section.get("block_states");
            if (blockStates != null) {
                blocks.put(section.getIntOr("Y", Integer.MIN_VALUE), blockStates);
            }
        }
        return blocks;
    }

    private static CompoundTag readChunk(Path regionDirectory, int chunkX, int chunkZ) throws IOException {
        Path region = regionDirectory.resolve("r." + Math.floorDiv(chunkX, 32) + "." + Math.floorDiv(chunkZ, 32) + ".mca");
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
