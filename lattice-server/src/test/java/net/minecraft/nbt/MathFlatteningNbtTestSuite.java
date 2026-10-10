package net.minecraft.nbt;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MathFlatteningNbtTestSuite {
    private static void originalCompound(CompoundTag tag, DataOutput out) throws IOException {
        for (String key : tag.keySet()) {
            Tag value = tag.get(key);
            out.writeByte(value.getId());
            if (value.getId() != 0) {
                out.writeUTF(key);
                if (value instanceof CompoundTag child) originalCompound(child, out);
                else value.write(out);
            }
        }
        out.writeByte(0);
    }

    private static final class LimitedOutput extends OutputStream {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        final int limit;
        LimitedOutput(int limit) { this.limit = limit; }
        @Override public void write(int value) throws IOException {
            if (bytes.size() == limit) throw new IOException("injected");
            bytes.write(value);
        }
    }

    @Test
    void compoundPreservesBytesAndEveryIOExceptionPrefix() throws Exception {
        CompoundTag child = new CompoundTag(); child.putString("nested", "中文\u0000");
        for (Map<String, Tag> values : java.util.List.<Map<String, Tag>>of(
            new HashMap<>(), new it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap<>())) {
            values.put("Aa", IntTag.valueOf(14)); values.put("BB", LongTag.valueOf(-72));
            values.put("child", child); values.put("empty", EndTag.INSTANCE);
            values.put("float", FloatTag.valueOf(-0.0F)); values.put("array", new ByteArrayTag(new byte[]{-1, 0, 1}));
            CompoundTag compound = new CompoundTag(values);
            ByteArrayOutputStream expected = new ByteArrayOutputStream();
            originalCompound(compound, new DataOutputStream(expected));
            for (int limit = 0; limit <= expected.size(); limit++) {
                LimitedOutput old = new LimitedOutput(limit), actual = new LimitedOutput(limit);
                if (limit < expected.size()) {
                    assertThrows(IOException.class, () -> originalCompound(compound, new DataOutputStream(old)));
                    assertThrows(IOException.class, () -> compound.write(new DataOutputStream(actual)));
                } else {
                    originalCompound(compound, new DataOutputStream(old)); compound.write(new DataOutputStream(actual));
                }
                assertArrayEquals(old.bytes.toByteArray(), actual.bytes.toByteArray());
            }
        }
    }

    @Test
    void anyAndUnnamedTagsPreserveTypeHeaders() throws Exception {
        for (Tag tag : new Tag[]{EndTag.INSTANCE, ByteTag.valueOf((byte)7), IntTag.valueOf(42), StringTag.valueOf("hi"), new CompoundTag()}) {
            for (boolean unnamed : new boolean[]{false, true}) {
                ByteArrayOutputStream expected = new ByteArrayOutputStream(), actual = new ByteArrayOutputStream();
                DataOutputStream out = new DataOutputStream(expected);
                out.writeByte(tag.getId());
                if (tag.getId() != 0) { if (unnamed) out.writeUTF(""); tag.write(out); }
                if (unnamed) NbtIo.writeUnnamedTag(tag, new DataOutputStream(actual));
                else NbtIo.writeAnyTag(tag, new DataOutputStream(actual));
                assertArrayEquals(expected.toByteArray(), actual.toByteArray());
            }
        }
    }

    @Test
    void mergePreservesSelfMergeSharedChildrenAndCopies() {
        CompoundTag shared = new CompoundTag(); shared.putInt("a", 1);
        CompoundTag target = new CompoundTag(); target.put("child", shared); target.putInt("old", 2);
        CompoundTag source = new CompoundTag(); source.put("child", shared); source.putInt("new", 3);
        assertSame(target, target.merge(source));
        assertSame(shared, target.get("child"));
        CompoundTag before = target.copy();
        assertSame(target, target.merge(target)); assertEquals(before, target);
        CompoundTag copied = new CompoundTag(); copied.merge(target);
        assertNotSame(shared, copied.get("child")); assertEquals(shared, copied.get("child"));
    }
}
