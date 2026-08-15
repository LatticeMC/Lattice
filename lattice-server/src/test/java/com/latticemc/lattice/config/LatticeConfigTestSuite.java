package com.latticemc.lattice.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LatticeConfigTestSuite {
    private final Map<String, String> originalProperties = new HashMap<>();

    @AfterEach
    void restoreProperties() {
        for (String property : LatticeConfig.managedProperties()) {
            String original = this.originalProperties.get(property);
            if (original == null) {
                System.clearProperty(property);
            } else {
                System.setProperty(property, original);
            }
        }
    }

    @Test
    void createsCommentedDefaultsAndBridgesThemToLegacyProperties(@TempDir Path directory) throws IOException {
        snapshotAndClearManagedProperties();
        Path config = directory.resolve("lattice.yml");

        LatticeConfig.preload(config);

        String contents = Files.readString(config);
        assertTrue(contents.contains("# Legacy JVM property: -Dlattice.disable"));
        assertTrue(contents.contains("disable-native"));
        assertEquals("false", System.getProperty("lattice.disable"));
        assertEquals("false", System.getProperty("lattice.nativeDensityFunction"));
        assertEquals("1024", System.getProperty("lattice.nativeDensityFunctionParityInterval"));
        assertEquals("false", System.getProperty("lattice.nativeIocp"));
        assertEquals("false", System.getProperty("lattice.nativeIoUring"));
        assertEquals("false", System.getProperty("lattice.nativeKqueue"));
        assertEquals("16", System.getProperty("lattice.nativeEntityVisibilityMinPlayers"));
        assertEquals("false", System.getProperty("lattice.nativeEntityVisibilityBatchEnabled"));
        assertEquals("512", System.getProperty("lattice.nativeEntityVisibilityBatchMinEntities"));
        assertEquals("1048576", System.getProperty("lattice.nativeEntityVisibilityBatchMaxScratchBytes"));
    }

    @Test
    void yamlOverridesBuiltInDefaults(@TempDir Path directory) throws IOException {
        snapshotAndClearManagedProperties();
        Path config = directory.resolve("lattice.yml");
        Files.writeString(config, """
                global:
                  disable-native: true
                density:
                  native-function: false
                native:
                  cpu-tier: AVX2
                entity:
                  visibility:
                    minimum-players: 0
                    batch-enabled: true
                    batch-minimum-entities: 2048
                    batch-max-scratch-bytes: 2097152
                brain:
                  minimum-behaviors: 300
                """);

        LatticeConfig.preload(config);

        assertEquals("true", System.getProperty("lattice.disable"));
        assertEquals("false", System.getProperty("lattice.nativeDensityFunction"));
        assertEquals("avx2", System.getProperty("lattice.nativeCpu"));
        assertEquals("300", System.getProperty("lattice.nativeBrainEligibility.minBehaviors"));
        assertEquals("0", System.getProperty("lattice.nativeEntityVisibilityMinPlayers"));
        assertEquals("true", System.getProperty("lattice.nativeEntityVisibilityBatchEnabled"));
        assertEquals("2048", System.getProperty("lattice.nativeEntityVisibilityBatchMinEntities"));
        assertEquals("2097152", System.getProperty("lattice.nativeEntityVisibilityBatchMaxScratchBytes"));
    }

    @Test
    void explicitSystemPropertyOverridesYaml(@TempDir Path directory) throws IOException {
        snapshotAndClearManagedProperties();
        Path config = directory.resolve("lattice.yml");
        Files.writeString(config, """
                palette:
                  enabled: true
                density:
                  native-function: false
                """);
        System.setProperty("lattice.nativePaletteOps", "false");
        System.setProperty("lattice.nativeDensityFunction", "true");

        LatticeConfig.preload(config);

        assertEquals("false", System.getProperty("lattice.nativePaletteOps"));
        assertEquals("true", System.getProperty("lattice.nativeDensityFunction"));
    }

    @Test
    void invalidValuesFallBackToTheNextLowerPrioritySource(@TempDir Path directory) throws IOException {
        snapshotAndClearManagedProperties();
        Path config = directory.resolve("lattice.yml");
        Files.writeString(config, """
                global:
                  disable-native: false
                density:
                  parity-interval: 0
                native:
                  cpu-tier: unsupported
                entity:
                  visibility:
                    minimum-players: -1
                    batch-minimum-entities: 1
                    batch-max-scratch-bytes: 0
                """);
        System.setProperty("lattice.disable", "not-a-boolean");

        LatticeConfig.preload(config);

        assertEquals("false", System.getProperty("lattice.disable"));
        assertEquals("1024", System.getProperty("lattice.nativeDensityFunctionParityInterval"));
        assertEquals("auto", System.getProperty("lattice.nativeCpu"));
        assertEquals("16", System.getProperty("lattice.nativeEntityVisibilityMinPlayers"));
        assertEquals("512", System.getProperty("lattice.nativeEntityVisibilityBatchMinEntities"));
        assertEquals("1048576", System.getProperty("lattice.nativeEntityVisibilityBatchMaxScratchBytes"));
    }

    private void snapshotAndClearManagedProperties() {
        for (String property : LatticeConfig.managedProperties()) {
            this.originalProperties.put(property, System.getProperty(property));
            System.clearProperty(property);
        }
    }
}
