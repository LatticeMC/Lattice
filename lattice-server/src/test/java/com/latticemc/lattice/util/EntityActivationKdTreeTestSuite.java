package com.latticemc.lattice.util;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;

class EntityActivationKdTreeTestSuite {
    @Test
    void usesOffsetNonSquarePlayerBoundsAndStrictIntersection() throws ReflectiveOperationException {
        Object tree = newPlayerTree();
        build(tree, new AABB(10.0, 2.0, 20.0, 14.0, 4.0, 21.0));

        assertTrue(intersects(tree, new AABB(13.5, 2.5, 20.5, 13.75, 3.0, 20.75), 0.0));
        assertFalse(intersects(tree, new AABB(9.5, 2.5, 21.0, 10.0, 3.0, 22.0), 0.0));
        assertTrue(intersects(tree, new AABB(9.5, 2.5, 21.0, 10.0, 3.0, 22.0), 0.01));
        assertFalse(intersects(tree, new AABB(11.0, 4.0, 20.25, 12.0, 5.0, 20.75), 0.0));
    }

    @Test
    void prunesAndFindsUsingRealSubtreeBounds() throws ReflectiveOperationException {
        Object tree = newPlayerTree();
        Method build = tree.getClass().getDeclaredMethod("build", double[].class, double[].class, double[].class,
            double[].class, double[].class, double[].class, int.class);
        build.setAccessible(true);
        build.invoke(tree,
            new double[] {-40.0, 100.0}, new double[] {-39.0, 108.0},
            new double[] {0.0, 0.0}, new double[] {2.0, 2.0},
            new double[] {5.0, -12.0}, new double[] {9.0, -11.0}, 2);

        assertTrue(intersects(tree, new AABB(107.5, 0.5, -11.75, 107.75, 1.0, -11.25), 0.0));
        assertFalse(intersects(tree, new AABB(99.0, 0.5, -10.0, 100.0, 1.0, -9.0), 0.0));
    }

    private static Object newPlayerTree() throws ReflectiveOperationException {
        Class<?> type = Class.forName("com.latticemc.lattice.util.EntityActivationKdTree$PlayerTree");
        Constructor<?> constructor = type.getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }

    private static void build(Object tree, AABB box) throws ReflectiveOperationException {
        Method build = tree.getClass().getDeclaredMethod("build", double[].class, double[].class, double[].class,
            double[].class, double[].class, double[].class, int.class);
        build.setAccessible(true);
        build.invoke(tree,
            new double[] {box.minX}, new double[] {box.maxX},
            new double[] {box.minY}, new double[] {box.maxY},
            new double[] {box.minZ}, new double[] {box.maxZ}, 1);
    }

    private static boolean intersects(Object tree, AABB box, double range) throws ReflectiveOperationException {
        Method intersects = tree.getClass().getDeclaredMethod("intersects", AABB.class, double.class);
        intersects.setAccessible(true);
        return (boolean)intersects.invoke(tree, box, range);
    }
}
