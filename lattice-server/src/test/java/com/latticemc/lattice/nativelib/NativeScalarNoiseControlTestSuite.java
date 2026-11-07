package com.latticemc.lattice.nativelib;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URLClassLoader;
import org.junit.jupiter.api.Test;

class NativeScalarNoiseControlTestSuite {
    @Test
    void isolatedInitializationDefaultsOnAndHonorsExplicitOverrides() throws Exception {
        String property = "lattice.nativeScalarPerlin";
        String original = System.getProperty(property);
        try {
            System.clearProperty(property);
            assertTrue(isolatedEnabled());
            System.setProperty(property, "false");
            assertFalse(isolatedEnabled());
            System.setProperty(property, "true");
            assertTrue(isolatedEnabled());
        } finally {
            if (original == null) System.clearProperty(property);
            else System.setProperty(property, original);
        }
    }

    private static boolean isolatedEnabled() throws Exception {
        try (URLClassLoader loader = new URLClassLoader(
                new java.net.URL[] {NativeScalarNoiseControl.class.getProtectionDomain().getCodeSource().getLocation()},
                ClassLoader.getPlatformClassLoader())) {
            Class<?> control = Class.forName(NativeScalarNoiseControl.class.getName(), true, loader);
            return (boolean) control.getMethod("perlinEnabled").invoke(null);
        }
    }
}
