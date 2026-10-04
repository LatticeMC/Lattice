package net.minecraft.world.entity;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;
import java.util.Random;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.component.KineticWeapon;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import net.minecraft.world.entity.EntitySpeedTestSupport.SpeedEntity;

class EntitySpeedTestSuite {
    @BeforeAll static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static Object cachedSpeed(Entity entity) throws Exception {
        Field field = Entity.class.getDeclaredField("lastKnownSpeed");
        field.setAccessible(true);
        return field.get(entity);
    }

    private static void sameBits(Vec3 expected, Vec3 actual) {
        for (double[] pair : new double[][] {{expected.x, actual.x}, {expected.y, actual.y}, {expected.z, actual.z}}) {
            if (Double.isNaN(pair[0])) assertTrue(Double.isNaN(pair[1]));
            else assertEquals(Double.doubleToRawLongBits(pair[0]), Double.doubleToRawLongBits(pair[1]));
        }
    }

    @Test void preservesSamplingResetAndVectorIdentity() throws Exception {
        SpeedEntity entity = new SpeedEntity();
        assertSame(Vec3.ZERO, entity.getKnownSpeed());
        assertFalse(entity.hasMovedHorizontallyRecently());
        entity.suppliedPosition = new Vec3(3, 4, 5);
        entity.sample();
        assertNull(cachedSpeed(entity));
        assertFalse(entity.hasMovedHorizontallyRecently());
        assertNull(cachedSpeed(entity));
        Vec3 initial = entity.getKnownSpeed();
        sameBits(entity.position().subtract(entity.position()), initial);
        assertSame(initial, entity.getKnownSpeed());
        entity.suppliedPosition = new Vec3(5, 11, 2);
        assertSame(initial, entity.getKnownSpeed());
        entity.sample();
        Vec3 movement = entity.getKnownSpeed();
        sameBits(new Vec3(2, 7, -3), movement);
        assertNotSame(initial, movement);
        entity.reapplyPosition();
        assertSame(movement, entity.getKnownSpeed());
        entity.sample();
        Vec3 reset = entity.getKnownSpeed();
        sameBits(Vec3.ZERO, reset);
        assertNotSame(movement, reset);
        entity.sample();
        assertNotSame(reset, entity.getKnownSpeed());
    }

    @Test void keepsVerticalMotionAndPlayerDelegation() throws Exception {
        SpeedEntity entity = new SpeedEntity();
        entity.sample();
        entity.suppliedPosition = new Vec3(0, 2, 0);
        entity.sample();
        assertFalse(entity.hasMovedHorizontallyRecently());
        assertNull(cachedSpeed(entity));
        Player player = mock(Player.class);
        Vec3 playerSpeed = new Vec3(7, 8, 9);
        when(player.getKnownSpeed()).thenReturn(playerSpeed);
        entity.controller = player;
        assertSame(playerSpeed, entity.getKnownSpeed());
        assertNull(cachedSpeed(entity));
        entity.alive = false;
        sameBits(new Vec3(0, 2, 0), entity.getKnownSpeed());
        entity.alive = true;
        entity.controller = mock(LivingEntity.class);
        sameBits(new Vec3(0, 40, 0), KineticWeapon.getMotion(entity));
    }

    @Test void matchesOriginalVectorArithmeticAtThresholdsAndExtremeValues() {
        double t = 1.0E-5F;
        double[] special = {0., -0., t, Math.nextDown(t), Math.nextUp(t), -t,
            Double.MIN_VALUE, Double.MAX_VALUE, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NaN};
        SpeedEntity entity = new SpeedEntity();
        Vec3 previous = Vec3.ZERO;
        entity.sample();
        for (double x : special) for (double z : special) {
            Vec3 current = new Vec3(x, -x, z);
            entity.suppliedPosition = current;
            entity.sample();
            Vec3 expected = current.subtract(previous);
            sameBits(expected, entity.getKnownSpeed());
            assertEquals(Math.abs(expected.horizontalDistance()) > 1.0E-5F, entity.hasMovedHorizontallyRecently());
            previous = current;
        }
        Random random = new Random(0xB17);
        for (int i = 0; i < 10000; i++) {
            Vec3 current = new Vec3(random.nextGaussian(), random.nextGaussian(), random.nextGaussian());
            entity.suppliedPosition = current;
            entity.sample();
            Vec3 expected = current.subtract(previous);
            sameBits(expected, entity.getKnownSpeed());
            assertEquals(Math.abs(expected.horizontalDistance()) > 1.0E-5F, entity.hasMovedHorizontallyRecently());
            previous = current;
        }
    }

    @Test void horizontalThresholdKeepsFloatLiteralAndSqrtRounding() {
        double threshold = 1.0E-5F;
        for (double value : new double[] {Math.nextDown(threshold), threshold, Math.nextUp(threshold)}) {
            for (Vec3 position : new Vec3[] {new Vec3(value, 0, 0), new Vec3(0, 0, -value),
                new Vec3(value / Math.sqrt(2), 0, value / Math.sqrt(2))}) {
                SpeedEntity entity = new SpeedEntity();
                entity.sample();
                entity.suppliedPosition = position;
                entity.sample();
                assertEquals(Math.abs(position.subtract(Vec3.ZERO).horizontalDistance()) > 1.0E-5F,
                    entity.hasMovedHorizontallyRecently());
            }
        }
    }

    @Test void lastPositionCallbackObservesNewSpeedEvenIfItThrows() {
        class ObservingEntity extends SpeedEntity {
            int reads;
            boolean observe;
            @Override public Vec3 position() {
                if (observe && ++reads == 2) {
                    sameBits(new Vec3(2, 3, 4), getKnownSpeed());
                    throw new IllegalStateException("last position read");
                }
                return super.position();
            }
        }
        ObservingEntity entity = new ObservingEntity();
        entity.sample();
        entity.getKnownSpeed();
        entity.suppliedPosition = new Vec3(2, 3, 4);
        entity.observe = true;
        assertThrows(IllegalStateException.class, entity::sample);
        sameBits(new Vec3(2, 3, 4), entity.getKnownSpeed());
    }

    @Test void productionTrackerHasNoNativeVisibilityOrPrefilterAllocation() throws Exception {
        // 验证实际编译产物，避免仅测试默认配置；即使旧属性为 true 也不能接回 C7。
        try (var stream = Entity.class.getClassLoader().getResourceAsStream("net/minecraft/server/level/ChunkMap$TrackedEntity.class")) {
            assertNotNull(stream);
            ClassNode node = new ClassNode();
            new ClassReader(stream).accept(node, 0);
            var tick = node.methods.stream().filter(m -> m.name.equals("moonrise$tick")).findFirst().orElseThrow();
            boolean callsUpdate = false;
            for (var instruction : tick.instructions) {
                if (instruction instanceof MethodInsnNode method) {
                    assertFalse(method.owner.endsWith("/NativeEntityVisibility"));
                    callsUpdate |= method.name.equals("updatePlayer");
                }
                if (instruction instanceof FieldInsnNode field) assertFalse(field.name.startsWith("LATTICE_NATIVE_VISIBILITY"));
                assertNotEquals(org.objectweb.asm.Opcodes.NEWARRAY, instruction.getOpcode());
            }
            assertTrue(callsUpdate);
        }
    }
}
