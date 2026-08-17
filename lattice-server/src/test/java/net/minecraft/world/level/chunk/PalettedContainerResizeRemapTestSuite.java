package net.minecraft.world.level.chunk;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import net.minecraft.core.IdMapper;
import net.minecraft.util.BitStorage;
import net.minecraft.util.SimpleBitStorage;
import org.junit.jupiter.api.Test;

class PalettedContainerResizeRemapTest {
    @Test
    void paletteResizesPreserveRepeatedAndDefaultValues() {
        IdMapper<Object> registry = new IdMapper<>(64);
        Object[] values = new Object[40];
        for (int i = 0; i < values.length; i++) {
            values[i] = new Object();
            registry.add(values[i]);
        }

        PalettedContainer<Object> container = new PalettedContainer<>(values[0], Strategy.createForBlockStates(registry));

        for (int i = 1; i < values.length; i++) {
            container.set(i & 15, i >> 4, 0, values[i]);
        }
        for (int i = 0; i < values.length; i++) {
            container.set(i & 15, 3 + (i >> 4), 1, values[i]);
        }

        for (int i = 1; i < values.length; i++) {
            assertSame(values[i], container.get(i & 15, i >> 4, 0));
        }
        for (int i = 0; i < values.length; i++) {
            assertSame(values[i], container.get(i & 15, 3 + (i >> 4), 1));
        }
        assertSame(values[0], container.get(15, 15, 15));
    }

    @Test
    void reencodeScratchIsResetReentrantAndDoesNotOwnReturnedContents() {
        HashMapPalette<Integer> oldPalette = new HashMapPalette<>(3, List.of(0, 1, 2, 3));
        SimpleBitStorage storage = storage(2, 3, 1, 3, 2, 0, 1);
        int[] expected = {0, 1, 0, 2, 3, 1};

        HashMapPalette<Integer> firstTarget = new HashMapPalette<>(3);
        int[] first = PalettedContainer.reencodeContents(storage, oldPalette, firstTarget);
        assertArrayEquals(expected, first);
        assertEquals(4, firstTarget.getSize());
        int[] firstCopy = first.clone();

        HashMapPalette<Integer> secondTarget = new HashMapPalette<>(3);
        int[] second = PalettedContainer.reencodeContents(storage, oldPalette, secondTarget);
        assertArrayEquals(expected, second);
        assertEquals(4, secondTarget.getSize());
        assertArrayEquals(firstCopy, first);
        assertNotSame(first, second);

        ReentrantPalette reentrantPalette = new ReentrantPalette(storage(1, 0, 1, 0));
        HashMapPalette<Integer> reentrantTarget = new HashMapPalette<>(3);
        assertArrayEquals(expected, PalettedContainer.reencodeContents(storage, reentrantPalette, reentrantTarget));
        assertArrayEquals(new int[] {0, 1, 0}, reentrantPalette.nestedResult);
        assertEquals(4, reentrantTarget.getSize());

        ThrowOncePalette throwingPalette = new ThrowOncePalette();
        assertThrows(IllegalStateException.class, () -> PalettedContainer.reencodeContents(storage, throwingPalette, new HashMapPalette<>(3)));
        HashMapPalette<Integer> afterThrowTarget = new HashMapPalette<>(3);
        assertArrayEquals(expected, PalettedContainer.reencodeContents(storage, throwingPalette, afterThrowTarget));
        assertEquals(4, afterThrowTarget.getSize());

        HashMapPalette<Integer> largePalette = new HashMapPalette<>(13, java.util.stream.IntStream.range(0, 4097).boxed().toList());
        HashMapPalette<Integer> largeTarget = new HashMapPalette<>(13);
        assertArrayEquals(new int[] {0, 1, 0}, PalettedContainer.reencodeContents(storage(13, 4096, 1, 4096), largePalette, largeTarget));
        assertEquals(2, largeTarget.getSize());
    }

    private static SimpleBitStorage storage(int bits, int... values) {
        SimpleBitStorage storage = new SimpleBitStorage(bits, values.length);
        for (int i = 0; i < values.length; i++) {
            storage.set(i, values[i]);
        }
        return storage;
    }

    private static final class ReentrantPalette extends HashMapPalette<Integer> {
        private final BitStorage nestedStorage;
        private boolean reentered;
        private int[] nestedResult;

        ReentrantPalette(BitStorage nestedStorage) {
            super(3, List.of(0, 1, 2, 3));
            this.nestedStorage = nestedStorage;
        }

        @Override
        public Integer valueFor(int id) {
            if (!this.reentered) {
                this.reentered = true;
                this.nestedResult = PalettedContainer.reencodeContents(this.nestedStorage, this, new HashMapPalette<>(3));
            }
            return super.valueFor(id);
        }
    }

    private static final class ThrowOncePalette extends HashMapPalette<Integer> {
        private boolean shouldThrow = true;

        ThrowOncePalette() {
            super(3, List.of(0, 1, 2, 3));
        }

        @Override
        public Integer valueFor(int id) {
            if (this.shouldThrow) {
                this.shouldThrow = false;
                throw new IllegalStateException("test exception");
            }
            return super.valueFor(id);
        }
    }
}
