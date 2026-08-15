package com.latticemc.lattice.bootstrap;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class LatticeBootstrapTestSuite {
    @Test
    void configurationPreloadMustPrecedeNativeEnsureLoaded() throws Exception {
        Path source = Path.of("src/main/java/com/latticemc/lattice/bootstrap/LatticeBootstrap.java");
        if (!Files.exists(source)) {
            source = Path.of("lattice-server/src/main/java/com/latticemc/lattice/bootstrap/LatticeBootstrap.java");
        }
        String contents = Files.readString(source);
        int preload = contents.indexOf("LatticeConfig.preload();");
        int ensureLoaded = contents.indexOf("LatticeNative.ensureLoaded();");
        assertTrue(preload >= 0, "bootstrap must preload lattice.yml");
        assertTrue(ensureLoaded > preload, "lattice.yml must be loaded before native initialization");
    }
}
