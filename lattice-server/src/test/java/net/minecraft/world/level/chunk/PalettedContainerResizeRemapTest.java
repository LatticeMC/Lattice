package net.minecraft.world.level.chunk;

import static org.junit.jupiter.api.Assertions.assertSame;

import net.minecraft.core.IdMapper;
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
}
