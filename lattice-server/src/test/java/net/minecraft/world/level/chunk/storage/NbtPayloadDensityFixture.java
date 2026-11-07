package net.minecraft.world.level.chunk.storage;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Set;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;

final class NbtPayloadDensityFixture {
    enum Policy { UNCHANGED, AIR_ONLY, AIR_AND_PLAINS }

    record Candidate(CompoundTag tag, int airFields, int plainsFields, long omittedFieldBytes) {}
    record Layout(int sectors, boolean external, long externalBytes) {}

    static Candidate candidate(CompoundTag original, Policy policy) throws IOException {
        CompoundTag result = original.copy();
        ListTag sections = result.getList("sections").orElseThrow(() -> new IllegalArgumentException("Missing sections"));
        int air = 0, plains = 0;
        long fieldBytes = 0;
        for (Tag entry : sections) {
            if (!(entry instanceof CompoundTag section)) throw new IllegalArgumentException("Non-compound section");
            if (policy != Policy.UNCHANGED && isDefault(section.get("block_states"), true)) {
                fieldBytes += fieldBytes("block_states", section.get("block_states"));
                section.remove("block_states");
                air++;
            }
            if (policy == Policy.AIR_AND_PLAINS && isDefault(section.get("biomes"), false)) {
                fieldBytes += fieldBytes("biomes", section.get("biomes"));
                section.remove("biomes");
                plains++;
            }
        }
        return new Candidate(result, air, plains, fieldBytes);
    }

    // Only canonical singleton containers qualify; air-like blocks are not the AIR default.
    static boolean isDefault(Tag field, boolean blockStates) {
        if (!(field instanceof CompoundTag container) || !container.keySet().equals(Set.of("palette"))) return false;
        if (!(container.get("palette") instanceof ListTag palette) || palette.size() != 1) return false;
        if (!blockStates) return palette.getString(0).filter("minecraft:plains"::equals).isPresent();
        return palette.get(0) instanceof CompoundTag state && state.keySet().equals(Set.of("Name"))
                && state.getString("Name").filter("minecraft:air"::equals).isPresent();
    }

    static void assertRestored(CompoundTag original, CompoundTag candidate) {
        CompoundTag restored = candidate.copy();
        ListTag before = original.getList("sections").orElseThrow();
        ListTag after = restored.getList("sections").orElseThrow();
        if (before.size() != after.size()) throw new AssertionError("Section count changed");
        for (int i = 0; i < before.size(); i++) {
            CompoundTag left = before.getCompound(i).orElseThrow();
            CompoundTag right = after.getCompound(i).orElseThrow();
            for (String field : new String[] {"block_states", "biomes"}) {
                Tag expected = left.get(field);
                if (expected != null && !right.contains(field)) {
                    if (!isDefault(expected, field.equals("block_states"))) {
                        throw new AssertionError("Non-default field omitted: " + field);
                    }
                    right.put(field, expected.copy());
                }
            }
        }
        if (!original.equals(restored)) throw new AssertionError("Non-default NBT changed");
    }

    static byte[] serialize(CompoundTag tag) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var output = new DataOutputStream(bytes)) {
            NbtIo.write(tag, output);
        }
        return bytes.toByteArray();
    }

    static CompoundTag read(byte[] bytes) throws IOException {
        try (var input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            CompoundTag tag = NbtIo.read(input);
            if (input.read() != -1) throw new IOException("Trailing NBT bytes");
            return tag;
        }
    }

    static byte[] compress(byte[] bytes) throws IOException {
        var output = new ByteArrayOutputStream();
        var compressor = new Deflater(6);
        try (var stream = new DeflaterOutputStream(output, compressor)) {
            stream.write(bytes);
        } finally {
            compressor.end();
        }
        return output.toByteArray();
    }

    static byte[] inflate(byte[] bytes) throws IOException {
        try (var stream = new InflaterInputStream(new ByteArrayInputStream(bytes))) {
            return stream.readAllBytes();
        }
    }

    static Layout layout(int compressedBytes) {
        if (compressedBytes < 0) throw new IllegalArgumentException("Negative payload");
        long sectors = (compressedBytes + 5L + 4095L) / 4096L;
        return sectors >= 256 ? new Layout(1, true, compressedBytes) : new Layout((int) sectors, false, 0);
    }

    private static int fieldBytes(String name, Tag value) throws IOException {
        var wrapper = new CompoundTag();
        wrapper.put(name, value);
        return serialize(wrapper).length - serialize(new CompoundTag()).length;
    }
}
