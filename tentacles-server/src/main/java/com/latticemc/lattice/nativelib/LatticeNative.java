package com.latticemc.lattice.nativelib;

import com.latticemc.lattice.bootstrap.LatticeNativeLoader;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class LatticeNative {
    private static final Logger LOGGER = LoggerFactory.getLogger("LatticeNative");
    private static final boolean DISABLED = Boolean.getBoolean("lattice.disable");
    public static final boolean VERIFY = Boolean.getBoolean("lattice.verify");
    private static final Set<String> FALLBACK_WARNINGS = ConcurrentHashMap.newKeySet();

    private static volatile boolean loaded = false;
    private static volatile boolean loadFailed = false;
    private static volatile String failureReason = null;

    private LatticeNative() {}

    public static synchronized void load() {
        if (loaded || loadFailed) return;
        if (DISABLED) {
            LOGGER.info("Lattice native disabled by -Dlattice.disable=true");
            loadFailed = true;
            failureReason = "disabled";
            return;
        }
        try {
            LatticeNativeLoader.load("lattice");
            loaded = true;
            LOGGER.info("Lattice native library loaded; cpu={} verify={}", safeCpuSummary(), VERIFY);
        } catch (UnsatisfiedLinkError e) {
            loadFailed = true;
            failureReason = e.getMessage();
            LOGGER.warn("Lattice native library unavailable: {}", e.getMessage());
        } catch (Throwable t) {
            loadFailed = true;
            failureReason = t.getMessage();
            LOGGER.warn("Lattice native library failed to load", t);
        }
    }

    public static void ensureLoaded() {
        if (!loaded && !loadFailed) load();
    }

    public static boolean isLoaded() { return loaded; }
    public static boolean isUnavailable() { return loadFailed; }
    public static String failureReason() { return failureReason; }
    public static String cpuSummary() { return safeCpuSummary(); }

    public static void logFallbackOnce(String module, String detail) {
        if (!FALLBACK_WARNINGS.add(module)) return;
        if (detail == null || detail.isBlank()) {
            LOGGER.warn("Lattice module '{}' fell back to JVM implementation.", module);
            return;
        }
        LOGGER.warn("Lattice module '{}' fell back to JVM implementation: {}", module, detail);
    }

    private static String safeCpuSummary() {
        if (!loaded) return "(not loaded)";
        try {
            return nativeCpuSummary();
        } catch (UnsatisfiedLinkError e) {
            return "(unavailable)";
        }
    }

    private static native String nativeCpuSummary();
}
