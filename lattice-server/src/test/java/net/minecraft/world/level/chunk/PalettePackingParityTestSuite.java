package net.minecraft.world.level.chunk;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;
import static org.mockito.ArgumentMatchers.anyInt;

import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.LongStream;
import net.minecraft.core.IdMapper;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.util.ZeroBitStorage;
import org.junit.jupiter.api.Test;

class PalettePackingParityTestSuite {
    @Test
    void packMatchesOriginalOrderingBitsAndStorageForBlockAndBiomeSizes() {
        for (int size : new int[] {64, 4096}) {
            for (int paletteSize : new int[] {1, 2, 3, 16, 17, 64, 65, 256, 300}) {
                Fixture fixture = fixture(size, paletteSize);
                var expected = originalPack(fixture.container, fixture.strategy);
                var actual = fixture.container.pack(fixture.strategy);
                assertEntries(expected.paletteEntries(), actual.paletteEntries());
                assertEquals(expected.bitsPerEntry(), actual.bitsPerEntry());
                assertEquals(expected.storage().isPresent(), actual.storage().isPresent());
                assertArrayEquals(raw(expected), raw(actual));
            }
        }
    }

    @Test
    void deferredStreamsAndListsSurviveSubsequentPackingAndMutation() {
        Fixture fixture = fixture(4096, 65);
        var expected = originalPack(fixture.container, fixture.strategy);
        var first = fixture.container.pack(fixture.strategy);
        for (int index = 0; index < 4096; index++) {
            fixture.container.data.storage().set(index, 0);
        }
        fixture.container.pack(fixture.strategy);
        Fixture biomes = fixture(64, 3);
        biomes.container.pack(biomes.strategy);
        assertEntries(expected.paletteEntries(), first.paletteEntries());
        assertArrayEquals(raw(expected), raw(first));
    }

    @Test
    void realPaletteTransitionsAndAntiXrayPresetsKeepPackingParity() {
        IdMapper<Object> registry = new IdMapper<>(512);
        Object[] values = new Object[512];
        for (int index = 0; index < values.length; index++) {
            values[index] = new Object();
            registry.add(values[index]);
        }
        for (int size : new int[] {64, 4096}) {
            for (boolean presets : new boolean[] {false, true}) {
                Strategy<Object> strategy = size == 64 ? Strategy.createForBiomes(registry) : Strategy.createForBlockStates(registry);
                PalettedContainer<Object> container = new PalettedContainer<>(values[0], strategy,
                    presets ? new Object[] {values[510], values[511]} : null);
                assertInstanceOf(ZeroBitStorage.class, container.data.storage());
                assertPackingParity(container, strategy);
                container.set(1, 0, 0, values[1]);
                if (presets && size == 4096) {
                    assertInstanceOf(HashMapPalette.class, container.data.palette());
                } else {
                    assertInstanceOf(LinearPalette.class, container.data.palette());
                }
                if (presets) {
                    assertTrue(java.util.stream.IntStream.range(0, container.data.palette().getSize())
                        .anyMatch(id -> container.data.palette().valueFor(id) == values[510]));
                }
                assertPackingParity(container, strategy);
                int axisBits = size == 64 ? 2 : 4;
                int axisMask = (1 << axisBits) - 1;
                int distinct = size == 64 ? 32 : 300;
                for (int index = 2; index < distinct; index++) {
                    container.set(index & axisMask, index >> (axisBits * 2), (index >> axisBits) & axisMask, values[index]);
                    if (size == 4096 && index == 32) {
                        assertInstanceOf(HashMapPalette.class, container.data.palette());
                        assertPackingParity(container, strategy);
                    }
                }
                assertInstanceOf(GlobalPalette.class, container.data.palette());
                assertPackingParity(container, strategy);
            }
        }
    }

    @Test
    void packingIsReentrantBeyondCachedDepthAndRecoversAfterPaletteErrors() {
        Fixture fixture = fixture(64, 17);
        var expected = originalPack(fixture.container, fixture.strategy);
        Palette<Object> original = fixture.container.data.palette();
        Palette<Object> reentrant = spy(original);
        int[] depth = {0};
        boolean[] entered = new boolean[6];
        doAnswer(invocation -> {
            int id = invocation.getArgument(0);
            if (depth[0] < 6 && !entered[depth[0]]) {
                entered[depth[0]] = true;
                depth[0]++;
                try {
                    var nested = fixture.container.pack(fixture.strategy);
                    assertEntries(expected.paletteEntries(), nested.paletteEntries());
                } finally {
                    depth[0]--;
                }
            }
            return original.valueFor(id);
        }).when(reentrant).valueFor(anyInt());
        var oldData = fixture.container.data;
        fixture.container.data = new PalettedContainer.Data<>(oldData.configuration(), oldData.storage(), reentrant);
        assertArrayEquals(raw(expected), raw(fixture.container.pack(fixture.strategy)));

        Palette<Object> throwing = spy(original);
        int[] calls = {0};
        doAnswer(invocation -> {
            if (++calls[0] == 2) {
                throw new MissingPaletteEntryException(invocation.getArgument(0));
            }
            return original.valueFor(invocation.getArgument(0));
        }).when(throwing).valueFor(anyInt());
        fixture.container.data = new PalettedContainer.Data<>(oldData.configuration(), oldData.storage(), throwing);
        assertThrows(MissingPaletteEntryException.class, () -> fixture.container.pack(fixture.strategy));
        var recovered = fixture.container.pack(fixture.strategy);
        assertEntries(expected.paletteEntries(), recovered.paletteEntries());
        assertArrayEquals(raw(originalPack(fixture.container, fixture.strategy)), raw(recovered));
    }

    @Test
    void countKeepsCallbackOrderAndNeverEmitsUnusedEntries() {
        for (int paletteSize : new int[] {1, 3, 17, 65, 300}) {
            Fixture fixture = fixture(4096, paletteSize);
            Int2IntOpenHashMap counts = new Int2IntOpenHashMap();
            fixture.container.data.storage().getAll(id -> counts.addTo(id, 1));
            List<Object> expectedValues = new ArrayList<>();
            List<Integer> expectedCounts = new ArrayList<>();
            counts.int2IntEntrySet().forEach(entry -> {
                expectedValues.add(fixture.container.data.palette().valueFor(entry.getIntKey()));
                expectedCounts.add(entry.getIntValue());
            });
            List<Object> actualValues = new ArrayList<>();
            List<Integer> actualCounts = new ArrayList<>();
            fixture.container.count((value, count) -> {
                actualValues.add(value);
                actualCounts.add(count);
            });
            assertEntries(expectedValues, actualValues);
            assertEquals(expectedCounts, actualCounts);
        }
    }

    static PalettedContainerRO.PackedData<Object> originalPack(PalettedContainer<Object> container, Strategy<Object> strategy) {
        var storage = container.data.storage();
        var oldPalette = container.data.palette();
        HashMapPalette<Object> palette = new HashMapPalette<>(storage.getBits());
        int[] values = new int[storage.getSize()];
        storage.unpack(values);
        int[] remap = new int[oldPalette.getSize()];
        java.util.Arrays.fill(remap, -1);
        for (int index = 0; index < values.length; index++) {
            int oldId = values[index];
            if (remap[oldId] < 0) {
                remap[oldId] = palette.idFor(oldPalette.valueFor(oldId), PaletteResize.noResizeExpected());
            }
            values[index] = remap[oldId];
        }
        int bits = strategy.getConfigurationForPaletteSize(palette.getSize()).bitsInStorage();
        var stream = bits == 0 ? java.util.Optional.<LongStream>empty()
            : java.util.Optional.of(java.util.Arrays.stream(new SimpleBitStorage(bits, strategy.entryCount(), values).getRaw()));
        return new PalettedContainerRO.PackedData<>(palette.getEntries(), stream, bits);
    }

    static Fixture fixture(int size, int paletteSize) {
        IdMapper<Object> registry = new IdMapper<>(512);
        List<Object> entries = new ArrayList<>();
        for (int index = 0; index < paletteSize; index++) {
            // Equal values remain distinct identities; every fifth entry is a duplicate reference.
            Object value = index > 0 && index % 5 == 0 ? entries.get(index - 1) : new String("equal");
            entries.add(value);
            registry.add(value);
        }
        Strategy<Object> strategy = size == 64 ? Strategy.createForBiomes(registry) : Strategy.createForBlockStates(registry);
        PalettedContainer<Object> container = new PalettedContainer<>(entries.getFirst(), strategy);
        int bits = Math.max(1, 32 - Integer.numberOfLeadingZeros(paletteSize - 1));
        SimpleBitStorage storage = new SimpleBitStorage(bits, size);
        Random random = new Random(size + paletteSize);
        for (int index = 0; index < size; index++) {
            storage.set(index, random.nextInt(Math.max(1, paletteSize - 1)));
        }
        container.data = new PalettedContainer.Data<>(strategy.getConfigurationForBitCount(bits), storage, new HashMapPalette<>(bits, entries));
        return new Fixture(container, strategy);
    }

    static long[] raw(PalettedContainerRO.PackedData<Object> data) {
        return data.storage().map(LongStream::toArray).orElseGet(() -> new long[0]);
    }

    private static void assertPackingParity(PalettedContainer<Object> container, Strategy<Object> strategy) {
        var expected = originalPack(container, strategy);
        var actual = container.pack(strategy);
        assertEntries(expected.paletteEntries(), actual.paletteEntries());
        assertEquals(expected.bitsPerEntry(), actual.bitsPerEntry());
        assertEquals(expected.storage().isPresent(), actual.storage().isPresent());
        assertArrayEquals(raw(expected), raw(actual));
    }

    private static void assertEntries(List<Object> expected, List<Object> actual) {
        assertEquals(expected.size(), actual.size());
        for (int index = 0; index < expected.size(); index++) {
            assertSame(expected.get(index), actual.get(index));
        }
    }

    record Fixture(PalettedContainer<Object> container, Strategy<Object> strategy) {
    }
}
