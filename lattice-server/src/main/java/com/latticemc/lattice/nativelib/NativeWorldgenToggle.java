package com.latticemc.lattice.nativelib;

public final class NativeWorldgenToggle {
    private static volatile boolean SURFACE_ENABLED = Boolean.parseBoolean(System.getProperty("lattice.nativeSurface", "true"));
    private static volatile boolean HEIGHTMAP_ENABLED = Boolean.parseBoolean(System.getProperty("lattice.nativeHeightmap", "true"));

    private NativeWorldgenToggle() {}

    public static boolean surfaceEnabled() {
        return SURFACE_ENABLED;
    }

    public static boolean heightmapEnabled() {
        return HEIGHTMAP_ENABLED;
    }

    public static boolean setOption(String option, boolean value) {
        switch (option) {
            case "surface" -> SURFACE_ENABLED = value;
            case "heightmap" -> HEIGHTMAP_ENABLED = value;
            default -> {
                return false;
            }
        }
        return true;
    }

    public static String status() {
        return " surface=" + SURFACE_ENABLED + " heightmap=" + HEIGHTMAP_ENABLED;
    }
}
