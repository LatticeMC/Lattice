package net.minecraft.world.entity.item;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ItemEntityDeltaMovementTestSuite {

    @Test
    void scalarDifferenceMatchesVec3SubtractLengthSqr() {
        assertEquivalent(Vec3.ZERO, Vec3.ZERO);
        assertEquivalent(new Vec3(0.1, -0.2, 0.3), new Vec3(-0.4, 0.5, -0.6));
        assertEquivalent(new Vec3(Double.POSITIVE_INFINITY, 0.0, -0.0), new Vec3(Double.POSITIVE_INFINITY, -0.0, 0.0));
        assertEquivalent(new Vec3(Double.NaN, 1.0, -1.0), new Vec3(0.0, Double.NaN, 1.0));
    }

    @Test
    void syncThresholdMatchesVec3SubtractLengthSqr() {
        assertThresholdEquivalent(Vec3.ZERO, new Vec3(0.1, 0.0, 0.0));
        assertThresholdEquivalent(Vec3.ZERO, new Vec3(0.10000000000000002, 0.0, 0.0));
        assertThresholdEquivalent(new Vec3(Double.NaN, 0.0, 0.0), Vec3.ZERO);
    }

    private static void assertEquivalent(Vec3 current, Vec3 previous) {
        double expected = current.subtract(previous).lengthSqr();
        double actual = squaredDifference(current, previous);
        if (Double.isNaN(expected)) {
            assertEquals(true, Double.isNaN(actual));
        } else {
            assertEquals(Double.doubleToRawLongBits(expected), Double.doubleToRawLongBits(actual));
        }
    }

    private static void assertThresholdEquivalent(Vec3 current, Vec3 previous) {
        boolean expected = current.subtract(previous).lengthSqr() > 0.01;
        boolean actual = squaredDifference(current, previous) > 0.01;
        assertEquals(expected, actual);
    }

    private static double squaredDifference(Vec3 current, Vec3 previous) {
        double deltaX = current.x - previous.x;
        double deltaY = current.y - previous.y;
        double deltaZ = current.z - previous.z;
        return deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ;
    }
}
