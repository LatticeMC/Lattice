package net.minecraft.world.entity;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.pathfinder.PathType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class MobMalusArrayTestSuite {
    @BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

    private static Mob mob() throws Exception {
        Mob mob = mock(Mob.class, CALLS_REAL_METHODS);
        Field defaults = Mob.class.getDeclaredField("DEFAULT_PATHFINDING_MALUS"); defaults.setAccessible(true);
        Field field = Mob.class.getDeclaredField("pathfindingMalus"); field.setAccessible(true);
        field.set(mob, ((float[])defaults.get(null)).clone());
        doReturn(null).when(mob).getControlledVehicle();
        return mob;
    }

    @Test void defaultsSpecialFloatsAndIndependentInstances() throws Exception {
        Mob first = mob(), second = mob();
        for (PathType type : PathType.values()) {
            assertEquals(Float.floatToRawIntBits(type.getMalus()), Float.floatToRawIntBits(first.getPathfindingMalus(type)));
            for (int bits : new int[]{0, 0x80000000, 0x7f800000, 0xff800000, 0x7fc12345, 0xffc23456, 0xbf800000}) {
                first.setPathfindingMalus(type, Float.intBitsToFloat(bits));
                assertEquals(bits, Float.floatToRawIntBits(first.getPathfindingMalus(type)));
                assertEquals(Float.floatToRawIntBits(type.getMalus()), Float.floatToRawIntBits(second.getPathfindingMalus(type)));
            }
        }
        assertThrows(NullPointerException.class, () -> first.setPathfindingMalus(null, 1));
        assertThrows(NullPointerException.class, () -> first.getPathfindingMalus(null));
    }

    @Test void inheritsOnlyImmediateControlledVehicleWithoutRecursiveGetter() throws Exception {
        Mob rider = mob(), vehicle = mob(), outer = mob();
        rider.setPathfindingMalus(PathType.WATER, 2); vehicle.setPathfindingMalus(PathType.WATER, 3); outer.setPathfindingMalus(PathType.WATER, 4);
        doReturn(vehicle).when(rider).getControlledVehicle();
        doReturn(outer).when(vehicle).getControlledVehicle();
        doReturn(true).when(vehicle).shouldPassengersInheritMalus();
        doReturn(true).when(outer).shouldPassengersInheritMalus();
        clearInvocations(rider, vehicle, outer);
        assertEquals(3, rider.getPathfindingMalus(PathType.WATER));
        verify(rider).getControlledVehicle(); verify(vehicle).shouldPassengersInheritMalus();
        verify(vehicle, never()).getControlledVehicle(); verify(vehicle, never()).getPathfindingMalus(any());
        doReturn(false).when(vehicle).shouldPassengersInheritMalus();
        assertEquals(2, rider.getPathfindingMalus(PathType.WATER));
    }
}
