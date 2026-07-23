package net.minecraft.world.level.chunk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.lang.reflect.Field;
import java.util.List;
import org.junit.jupiter.api.Test;

class LinearPaletteLastValueTest {
    @Test
    void repeatedIdentityUsesLastValueWithoutChangingPaletteSemantics() throws ReflectiveOperationException {
        Object first = new Object();
        Object second = new Object();
        Palette<Object> palette = LinearPalette.create(2, List.of(first, second));
        PaletteResize<Object> failResize = (bits, value) -> {
            throw new AssertionError("unexpected resize");
        };

        assertEquals(1, palette.idFor(second, failResize));
        assertEquals(1, palette.idFor(second, failResize));
        assertEquals(0, palette.idFor(first, failResize));
        assertEquals(0, palette.idFor(first, failResize));
        Object third = new Object();
        Object fourth = new Object();
        Palette<Object> expanded = LinearPalette.create(2, List.of(first, second, third, fourth));
        assertEquals(2, expanded.idFor(third, failResize));
        assertEquals(3, expanded.idFor(fourth, failResize));
        assertEquals(2, expanded.idFor(third, failResize));
        assertEquals(3, expanded.idFor(fourth, failResize));
        assertSame(first, palette.valueFor(0));
        assertSame(second, palette.valueFor(1));

        Field lastId = LinearPalette.class.getDeclaredField("lastId");
        lastId.setAccessible(true);
        assertEquals(0, lastId.getInt(palette));
    }
}
