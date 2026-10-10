package net.minecraft.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Iterator;
import java.util.Random;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.synth.PerlinNoise;
import org.junit.jupiter.api.Test;

class MathFlatteningTestSuite {
    @Test
    void rollingCoordinatesPreserveIntArithmeticAndMutableAliases() {
        int[][] dimensions = {{3, 4, 5}, {1, 1, 1}, {0, 2, 3}, {-1, 1, 1}, {2, -3, 2},
            {65536, 65536, 1}, {65537, 65537, 1}, {Integer.MAX_VALUE, 2, 1}, {1, 1, -3}};
        for (int origin : new int[]{0, -17, Integer.MAX_VALUE - 1, Integer.MIN_VALUE}) {
            for (int[] d : dimensions) {
                int w = d[0], h = d[1], depth = d[2], end = w * h * depth;
                Cursor3D cursor = new Cursor3D(origin, origin, origin, origin + w - 1, origin + h - 1, origin + depth - 1);
                Iterator<BlockPos> iterator = BlockPos.betweenClosed(origin, origin, origin, origin + w - 1, origin + h - 1, origin + depth - 1).iterator();
                assertEquals(origin, cursor.nextX());
                BlockPos previous = null;
                for (int index = 0; index < 150000; index++) {
                    if (index == end) {
                        int lastX = cursor.nextX(), lastY = cursor.nextY(), lastZ = cursor.nextZ();
                        assertFalse(cursor.advance()); assertFalse(cursor.advance());
                        assertFalse(iterator.hasNext());
                        assertEquals(lastX, cursor.nextX()); assertEquals(lastY, cursor.nextY()); assertEquals(lastZ, cursor.nextZ());
                        break;
                    }
                    int x = index % w, quotient = index / w, y = quotient % h, z = quotient / h;
                    assertTrue(cursor.advance());
                    assertEquals(origin + x, cursor.nextX()); assertEquals(origin + y, cursor.nextY()); assertEquals(origin + z, cursor.nextZ());
                    int type = (x == 0 || x == w - 1 ? 1 : 0) + (y == 0 || y == h - 1 ? 1 : 0) + (z == 0 || z == depth - 1 ? 1 : 0);
                    assertEquals(type, cursor.getNextType());
                    assertTrue(iterator.hasNext()); assertTrue(iterator.hasNext());
                    BlockPos pos = iterator.next();
                    if (previous != null) assertSame(previous, pos);
                    assertEquals(new BlockPos(origin + x, origin + y, origin + z), pos);
                    ((BlockPos.MutableBlockPos)pos).set(918, 29, -822);
                    previous = pos;
                }
            }
        }
    }

    @Test
    void wrapPreservesRawBitsAtRoundingBoundariesAndRandomInputs() {
        for (double value : new double[]{0, -0.0, Double.MIN_VALUE, -Double.MIN_VALUE, Double.MIN_NORMAL,
            Double.MAX_VALUE, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NaN,
            Double.longBitsToDouble(0xfff8123456789abcL), Double.longBitsToDouble(0x7ff0123456789abcL)}) checkWrap(value);
        for (int k = -100; k <= 100; k++) {
            double boundary = (k + 0.5) * 0x1.0p25;
            checkWrap(boundary); checkWrap(Math.nextDown(boundary)); checkWrap(Math.nextUp(boundary));
        }
        Random random = new Random(89543);
        for (int i = 0; i < 100000; i++) checkWrap(Double.longBitsToDouble(random.nextLong()));
    }

    private static void checkWrap(double value) {
        double expected = value - Mth.lfloor(value / 3.3554432E7 + 0.5) * 3.3554432E7;
        assertEquals(Double.doubleToRawLongBits(expected), Double.doubleToRawLongBits(PerlinNoise.wrap(value)));
    }

    @Test
    void identifierHashPreservesOverflowAndFactoryPaths() {
        for (String text : new String[]{"minecraft:stone", "a:b/c", "long_namespace:very_long_path.12345", "stone", ":stone"}) {
            Identifier id = Identifier.parse(text);
            for (Identifier value : new Identifier[]{id, id.withPrefix("x/"), id.withSuffix("_z"), id.withPath("different")}) {
                assertEquals(31 * value.getNamespace().hashCode() + value.getPath().hashCode(), value.hashCode());
                assertEquals(value.hashCode(), Identifier.parse(value.toString()).hashCode());
            }
        }
    }
}
