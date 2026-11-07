package net.minecraft.world.level.chunk.storage;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NbtPayloadDensityTestSuite {
    @Test
    void strictSingletonsDoNotConfuseAirVariantsOrOtherBiomes() {
        assertTrue(NbtPayloadDensityFixture.isDefault(blocks("minecraft:air"), true));
        for (String block : new String[] {"minecraft:cave_air", "minecraft:void_air", "minecraft:stone"}) {
            assertFalse(NbtPayloadDensityFixture.isDefault(blocks(block), true));
        }
        assertTrue(NbtPayloadDensityFixture.isDefault(biomes("minecraft:plains"), false));
        assertFalse(NbtPayloadDensityFixture.isDefault(biomes("minecraft:desert"), false));
        var data = blocks("minecraft:air");
        data.putLongArray("data", new long[256]);
        assertFalse(NbtPayloadDensityFixture.isDefault(data, true));
        data = blocks("minecraft:air");
        data.getListOrEmpty("palette").getCompoundOrEmpty(0).put("Properties", new CompoundTag());
        assertFalse(NbtPayloadDensityFixture.isDefault(data, true));
        assertFalse(NbtPayloadDensityFixture.isDefault(null, true));
        assertFalse(NbtPayloadDensityFixture.isDefault(new CompoundTag(), false));
    }

    @Test
    void inMemoryCandidatesRetainLightOnlySectionsOrderAndSourceOwnership() throws Exception {
        var original = new CompoundTag();
        var sections = new ListTag();
        var empty = new CompoundTag();
        empty.putByte("Y", (byte) -4);
        empty.put("block_states", blocks("minecraft:air"));
        empty.put("biomes", biomes("minecraft:plains"));
        empty.putByteArray("SkyLight", new byte[2048]);
        empty.putInt("starlight.skylight_state", 2);
        sections.add(empty);
        var cave = new CompoundTag();
        cave.putByte("Y", (byte) 3);
        cave.put("block_states", blocks("minecraft:cave_air"));
        cave.put("biomes", biomes("minecraft:desert"));
        sections.add(cave);
        var light = new CompoundTag();
        light.putByte("Y", (byte) 24);
        light.putByteArray("BlockLight", new byte[2048]);
        sections.add(light);
        original.put("sections", sections);
        original.putInt("DataVersion", 4671);
        CompoundTag frozen = original.copy();
        for (var policy : NbtPayloadDensityFixture.Policy.values()) {
            var candidate = NbtPayloadDensityFixture.candidate(original, policy);
            assertEquals(policy == NbtPayloadDensityFixture.Policy.UNCHANGED ? 0 : 1, candidate.airFields());
            assertEquals(policy == NbtPayloadDensityFixture.Policy.AIR_AND_PLAINS ? 1 : 0, candidate.plainsFields());
            byte[] encoded = NbtPayloadDensityFixture.serialize(candidate.tag());
            assertEquals(NbtPayloadDensityFixture.serialize(original).length - encoded.length, candidate.omittedFieldBytes());
            var decoded = NbtPayloadDensityFixture.read(
                    NbtPayloadDensityFixture.inflate(NbtPayloadDensityFixture.compress(encoded)));
            NbtPayloadDensityFixture.assertRestored(original, decoded);
            assertEquals(frozen, original);
            assertEquals(3, decoded.getListOrEmpty("sections").size());
        }
    }

    @Test
    void parityRejectsChangesOutsideTheTwoExactDefaultFields() throws Exception {
        var original = new CompoundTag();
        var section = new CompoundTag();
        section.putByte("Y", (byte) -4);
        section.put("block_states", blocks("minecraft:stone"));
        var sections = new ListTag();
        sections.add(section);
        original.put("sections", sections);
        var bad = original.copy();
        bad.getListOrEmpty("sections").getCompoundOrEmpty(0).remove("block_states");
        assertThrows(AssertionError.class, () -> NbtPayloadDensityFixture.assertRestored(original, bad));
        var wrongY = original.copy();
        wrongY.getListOrEmpty("sections").getCompoundOrEmpty(0).putByte("Y", (byte) -3);
        assertThrows(AssertionError.class, () -> NbtPayloadDensityFixture.assertRestored(original, wrongY));
        var missingSection = original.copy();
        missingSection.getListOrEmpty("sections").clear();
        assertThrows(AssertionError.class, () -> NbtPayloadDensityFixture.assertRestored(original, missingSection));
    }

    @Test
    void sectorAndExternalBoundariesIncludeTheFiveByteHeader() {
        assertEquals(new NbtPayloadDensityFixture.Layout(1, false, 0), NbtPayloadDensityFixture.layout(4091));
        assertEquals(new NbtPayloadDensityFixture.Layout(2, false, 0), NbtPayloadDensityFixture.layout(4092));
        assertEquals(new NbtPayloadDensityFixture.Layout(255, false, 0), NbtPayloadDensityFixture.layout(255 * 4096 - 5));
        assertEquals(new NbtPayloadDensityFixture.Layout(1, true, 255 * 4096 - 4),
                NbtPayloadDensityFixture.layout(255 * 4096 - 4));
        assertTrue(NbtPayloadDensityFixture.layout(Integer.MAX_VALUE).external());
        assertThrows(IllegalArgumentException.class, () -> NbtPayloadDensityFixture.layout(-1));
    }

    @Test
    void jdkCompressionRoundTripsAndRejectsTruncatedInput() throws Exception {
        for (int length : new int[] {0, 1024, 65536, 1048576}) {
            byte[] bytes = new byte[length];
            new Random(length).nextBytes(bytes);
            byte[] compressed = NbtPayloadDensityFixture.compress(bytes);
            assertArrayEquals(bytes, NbtPayloadDensityFixture.inflate(compressed));
            assertThrows(IOException.class, () -> NbtPayloadDensityFixture.inflate(
                    java.util.Arrays.copyOf(compressed, compressed.length - 2)));
        }
    }

    @Test
    void regionReaderCoversInlineExternalAndMalformedHeadersWithoutWrites(@TempDir Path directory) throws Exception {
        var tag = new CompoundTag();
        tag.putInt("xPos", -32);
        tag.putInt("zPos", 0);
        tag.putInt("DataVersion", 4671);
        tag.putString("Status", "minecraft:full");
        tag.put("sections", new ListTag());
        byte[] zipped = NbtPayloadDensityFixture.compress(NbtPayloadDensityFixture.serialize(tag));
        for (boolean external : new boolean[] {false, true}) {
            Path folder = Files.createDirectory(directory.resolve(external ? "external" : "inline"));
            Path path = folder.resolve("r.-1.0.mca");
            try (var file = new RandomAccessFile(path.toFile(), "rw")) {
                file.setLength(3 * 4096);
                file.writeInt((2 << 8) | 1);
                file.seek(8192);
                file.writeInt(external ? 1 : zipped.length + 1);
                file.writeByte(external ? 130 : 2);
                if (!external) file.write(zipped);
            }
            Path payload = folder.resolve("c.-32.0.mcc");
            if (external) Files.write(payload, zipped);
            byte[] originalFile = Files.readAllBytes(path);
            NbtPayloadDensityAudit.audit(path);
            assertArrayEquals(originalFile, Files.readAllBytes(path));
            if (external) assertArrayEquals(zipped, Files.readAllBytes(payload));
        }
        for (String failure : new String[] {"overlap", "type", "length", "stub", "coordinates", "schema"}) {
            Path folder = Files.createDirectory(directory.resolve(failure));
            Path path = folder.resolve("r.-1.0.mca");
            CompoundTag input = tag.copy();
            if (failure.equals("coordinates")) input.putInt("xPos", 1);
            if (failure.equals("schema")) input.putInt("DataVersion", 2841);
            byte[] bytes = NbtPayloadDensityFixture.compress(NbtPayloadDensityFixture.serialize(input));
            try (var file = new RandomAccessFile(path.toFile(), "rw")) {
                file.setLength(3 * 4096);
                file.writeInt((2 << 8) | 1);
                if (failure.equals("overlap")) file.writeInt((2 << 8) | 1);
                file.seek(8192);
                file.writeInt(failure.equals("length") ? 4096 : bytes.length + 1);
                file.writeByte(failure.equals("type") ? 3 : failure.equals("stub") ? 130 : 2);
                file.write(bytes);
            }
            byte[] frozen = Files.readAllBytes(path);
            assertThrows(IOException.class, () -> NbtPayloadDensityAudit.audit(path), failure);
            assertArrayEquals(frozen, Files.readAllBytes(path));
        }
    }

    private static CompoundTag blocks(String name) {
        var state = new CompoundTag();
        state.putString("Name", name);
        var palette = new ListTag();
        palette.add(state);
        var container = new CompoundTag();
        container.put("palette", palette);
        return container;
    }

    private static CompoundTag biomes(String name) {
        var palette = new ListTag();
        palette.add(StringTag.valueOf(name));
        var container = new CompoundTag();
        container.put("palette", palette);
        return container;
    }
}
