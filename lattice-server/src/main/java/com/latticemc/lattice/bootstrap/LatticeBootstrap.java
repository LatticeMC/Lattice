package com.latticemc.lattice.bootstrap;

import com.latticemc.lattice.config.LatticeConfig;
import com.latticemc.lattice.nativelib.LatticeNative;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class LatticeBootstrap {

    private static final Logger LOGGER = LoggerFactory.getLogger("Lattice");

    private static volatile boolean startupLogged = false;

    private LatticeBootstrap() {}

    public static synchronized void onServerStart() {
        if (startupLogged) return;
        startupLogged = true;
        // Configuration must be bridged before any native consumer initializes its static switches.
        LatticeConfig.preload();
        LatticeNative.ensureLoaded();
        if (LatticeNative.isLoaded()) {
            LOGGER.info("Lattice native acceleration active: {}",
                    LatticeNative.cpuSummary());
        } else {
            LOGGER.warn("Lattice running without native acceleration (reason: {}). All hot paths fall back to JVM implementations.",
                    LatticeNative.failureReason());
        }
    }
}
