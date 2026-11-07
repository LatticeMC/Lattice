package com.latticemc.lattice.nativelib;

public final class NativeScalarNoiseControl {
    private static volatile boolean perlinEnabled = Boolean.parseBoolean(
            System.getProperty("lattice.nativeScalarPerlin", "true"));

    private NativeScalarNoiseControl() {
    }

    public static boolean perlinEnabled() {
        return perlinEnabled;
    }

    public static void setPerlinEnabled(boolean enabled) {
        perlinEnabled = enabled;
    }
}
