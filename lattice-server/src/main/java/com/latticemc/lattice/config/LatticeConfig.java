package com.latticemc.lattice.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.yaml.NodeStyle;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

/**
 * Startup-only configuration for Lattice's existing JVM property switches.
 *
 * <p>The native and world-generation implementations initialise their switches statically. This
 * class must therefore run before any Lattice implementation class is loaded. A reload cannot
 * change already-initialised switches; change {@code lattice.yml} and restart the server instead.</p>
 */
public final class LatticeConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger("LatticeConfig");
    private static final Path DEFAULT_PATH = Path.of("lattice.yml");

    private static final List<Setting> SETTINGS = List.of(
            bool("global.disable-native", "lattice.disable", false, "Disable all Lattice native acceleration."),
            bool("global.verify-native", "lattice.verify", false, "Check native results against the Java implementation."),
            bool("global.disable-vanilla-profiler", "lattice.disableVanillaProfiler", true, "Disable Minecraft's vanilla profiler fast-path overhead."),

            string("native.library-path", "lattice.native.path", "", "Optional absolute path to a native library; leave empty to use the bundled library."),
            string("native.cache-directory", "lattice.native.cacheDir", "", "Optional native-library extraction cache directory; leave empty for the platform default."),
            bool("native.download", "lattice.native.download", true, "Allow downloading a release native library when it is not bundled."),
            nonEmptyString("native.release", "lattice.native.release", "native-latest", "Release tag used when downloading the native library."),
            nonEmptyString("native.release-base-url", "lattice.native.releaseBaseUrl", "https://github.com/LatticeMC/Lattice/releases/download", "Base HTTP(S) URL used when downloading the native library."),
            cpuTier("native.cpu-tier", "lattice.nativeCpu", "auto", "Native CPU tier: auto, scalar, avx2, or avx512. Unsupported hardware safely falls back to a compatible native implementation."),
            bool("native.aabb-query", "lattice.nativeAabbQuery", true, "Use native AABB intersection scans when available."),
            bool("native.scalar-perlin", "lattice.nativeScalarPerlin", false, "Use native scalar Perlin noise evaluation."),

            bool("entity.activation-kd-tree", "lattice.entityActivationKdTree", false, "Use the entity-activation KD-tree accelerator."),
            bool("entity.spatial-aabb-query", "lattice.spatialAabbQuery", true, "Use the spatial AABB-query cache."),
            bool("entity.spatial-cell-lookup-cache", "lattice.spatialCellLookupCache", true, "Use the spatial entity-cell lookup cache."),
            bool("entity.goal-selector-locked-priority-fast-path", "lattice.goalSelectorLockedPriorityFastPath", true, "Use the locked-priority goal-selector fast path."),

            bool("pathfinder.enabled", "lattice.nativePathfinder", true, "Enable the native pathfinder."),
            integer("pathfinder.minimum-native-volume", "lattice.pathfinderMinNativeVolume", 8192, 1, "Minimum pathfinding region volume eligible for native evaluation."),
            integer("pathfinder.short-java-gate", "lattice.pathfinderShortJavaGate", 24, 0, "Maximum short-path Manhattan distance kept on the Java path."),
            integer("pathfinder.mirror-warmup-per-tick", "lattice.pathfinderMirrorWarmupPerTick", 1, 0, "Per-thread native pathfinder mirror warm-up uploads allowed per tick."),

            bool("worldgen.surface", "lattice.nativeSurface", true, "Enable native surface-rule evaluation."),
            bool("worldgen.heightmap", "lattice.nativeHeightmap", true, "Enable native heightmap evaluation."),
            bool("worldgen.profiler-available", "lattice.worldgenProfilerAvailable", false, "Make world-generation profiler probes available."),
            bool("worldgen.profiler", "lattice.worldgenProfiler", false, "Enable world-generation profiler probes."),
            bool("worldgen.profiler-hot-loops", "lattice.worldgenProfilerHotLoops", false, "Enable high-frequency world-generation profiler probes."),

            bool("density.native-function", "lattice.nativeDensityFunction", false, "Enable native density-function evaluation."),
            bool("density.grid", "lattice.nativeDensityFunctionGrid", false, "Enable native density-function grid evaluation."),
            bool("density.cell", "lattice.nativeDensityFunctionCell", true, "Enable native density-function cell evaluation."),
            bool("density.direct-cell", "lattice.nativeDensityFunctionDirectCell", true, "Enable direct native density-function cell evaluation."),
            bool("density.direct-cell-column", "lattice.nativeDensityFunctionDirectCellColumn", true, "Enable direct native density-function cell-column evaluation."),
            bool("density.shifted-noise", "lattice.nativeDensityFunctionShiftedNoise", true, "Enable native shifted-noise density functions."),
            bool("density.spline", "lattice.nativeDensityFunctionSpline", true, "Enable native density-function splines."),
            bool("density.multipoint-spline", "lattice.nativeDensityFunctionMultipointSpline", true, "Enable native density-function multipoint splines."),
            bool("density.climate-batch", "lattice.nativeDensityFunctionClimateBatch", true, "Enable native climate density batches."),
            bool("density.statistics", "lattice.nativeDensityFunctionStats", false, "Collect native density-function statistics."),
            bool("density.profiling", "lattice.nativeDensityFunctionProfiling", false, "Collect native density-function timings."),
            bool("density.parity", "lattice.nativeDensityFunctionParity", false, "Compare sampled native density-function results with Java results."),
            integer("density.parity-interval", "lattice.nativeDensityFunctionParityInterval", 1024, 1, "Number of density evaluations between parity checks."),

            bool("palette.enabled", "lattice.nativePaletteOps", true, "Enable native palette operations."),
            integer("palette.minimum-bulk-count", "lattice.nativePaletteOpsMinCount", 64, 0, "Minimum palette bulk-operation size eligible for native evaluation."),
            bool("zlib.native-region-inflate", "lattice.nativeRegionZlibInflate", false, "Use native zlib inflation for region files."),
            bool("ore.native-vein-block-state-filler", "lattice.nativeOreVeinBlockStateFiller", false, "Use the native ore-vein block-state filler."),

            integer("ai.biological.minimum-stimuli", "lattice.nativeBiologicalAi.minStimuli", Integer.MAX_VALUE, 0, "Minimum biological-AI stimuli eligible for native evaluation."),
            integer("ai.target-sampler.minimum-work", "lattice.nativeTargetSampler.minWork", Integer.MAX_VALUE, 0, "Minimum target-sampler work eligible for native evaluation."),
            integer("brain.minimum-requirements", "lattice.nativeBrainEligibility.minRequirements", 64, 1, "Minimum Brain memory requirements eligible for native evaluation."),
            integer("brain.minimum-behaviors", "lattice.nativeBrainEligibility.minBehaviors", 256, 1, "Minimum Brain behaviors eligible for native evaluation."),
            integer("brain.minimum-word-checks", "lattice.nativeBrainEligibility.minWordChecks", 512, 1, "Minimum Brain bitset word checks eligible for native evaluation."));

    private static final Set<String> SETTING_PATHS = SETTINGS.stream()
            .map(Setting::path)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
    private static final Set<String> GROUP_PATHS = groups();

    private LatticeConfig() {
    }

    /** Loads {@code lattice.yml} from the server working directory during startup. */
    public static void preload() {
        preload(DEFAULT_PATH);
    }

    /**
     * Loads a startup configuration and bridges each resolved value to the legacy JVM property
     * consumed by the already-existing Lattice implementations. This method is for bootstrap and
     * tests only; changing the file after startup requires a server restart.
     */
    public static void preload(Path path) {
        CommentedConfigurationNode root = load(path);
        warnUnknownKeys(root, "");
        for (Setting setting : SETTINGS) {
            String explicit = System.getProperty(setting.property());
            if (explicit != null) {
                String validated = setting.parse(explicit, "-D" + setting.property());
                if (validated != null) {
                    continue;
                }
                // An invalid command-line value has no usable priority; YAML then defaults apply.
            }

            Object raw = root.node((Object[]) setting.pathSegments()).raw();
            String resolved = raw == null ? setting.defaultValue() : setting.parse(raw.toString(), setting.path());
            System.setProperty(setting.property(), resolved == null ? setting.defaultValue() : resolved);
        }
    }

    static List<String> managedProperties() {
        return SETTINGS.stream().map(Setting::property).toList();
    }

    private static CommentedConfigurationNode load(Path path) {
        YamlConfigurationLoader loader = YamlConfigurationLoader.builder().path(path).nodeStyle(NodeStyle.BLOCK).build();
        boolean createDefaults = !Files.exists(path);
        try {
            if (createDefaults) {
                Path parent = path.toAbsolutePath().getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                CommentedConfigurationNode defaults = loader.createNode();
                defaults.comment("Lattice startup configuration. Changes require a server restart.");
                for (Setting setting : SETTINGS) {
                    CommentedConfigurationNode node = defaults.node((Object[]) setting.pathSegments());
                    node.raw(setting.defaultValueAsYaml());
                    node.comment(setting.yamlComment());
                }
                writeCommentedDefaults(path);
                LOGGER.info("Created Lattice configuration at {}", path.toAbsolutePath());
                return defaults;
            }
            return loader.load();
        } catch (IOException exception) {
            LOGGER.warn("Unable to load Lattice configuration '{}'; using built-in defaults", path.toAbsolutePath(), exception);
            return loader.createNode();
        }
    }

    private static void writeCommentedDefaults(Path path) throws IOException {
        StringBuilder output = new StringBuilder();
        String[] previous = new String[0];
        for (Setting setting : SETTINGS) {
            String[] current = setting.pathSegments();
            int sharedGroups = 0;
            while (sharedGroups < previous.length - 1 && sharedGroups < current.length - 1
                    && previous[sharedGroups].equals(current[sharedGroups])) {
                sharedGroups++;
            }
            for (int index = sharedGroups; index < current.length - 1; index++) {
                output.append("  ".repeat(index)).append(current[index]).append(":\n");
            }
            String indentation = "  ".repeat(current.length - 1);
            for (String line : setting.yamlComment().split("\\n")) {
                output.append(indentation).append("# ").append(line).append('\n');
            }
            output.append(indentation)
                    .append(current[current.length - 1])
                    .append(": ")
                    .append(setting.yamlDefaultValue())
                    .append('\n');
            previous = current;
        }
        Files.writeString(path, output.toString());
    }

    private static void warnUnknownKeys(CommentedConfigurationNode node, String prefix) {
        for (var entry : node.childrenMap().entrySet()) {
            String path = prefix.isEmpty() ? String.valueOf(entry.getKey()) : prefix + '.' + entry.getKey();
            CommentedConfigurationNode child = (CommentedConfigurationNode) entry.getValue();
            if (child.childrenMap().isEmpty()) {
                if (!SETTING_PATHS.contains(path)) {
                    LOGGER.warn("Unknown key '{}' in lattice.yml; keeping it unchanged", path);
                }
            } else if (GROUP_PATHS.contains(path) || SETTING_PATHS.contains(path)) {
                warnUnknownKeys(child, path);
            } else {
                LOGGER.warn("Unknown key '{}' in lattice.yml; keeping it unchanged", path);
            }
        }
    }

    private static Set<String> groups() {
        Set<String> groups = new HashSet<>();
        for (Setting setting : SETTINGS) {
            String[] segments = setting.pathSegments();
            for (int index = 1; index < segments.length; index++) {
                groups.add(String.join(".", Arrays.copyOf(segments, index)));
            }
        }
        return Set.copyOf(groups);
    }

    private static Setting bool(String path, String property, boolean defaultValue, String comment) {
        return new Setting(path, property, Type.BOOLEAN, Boolean.toString(defaultValue), 0, comment);
    }

    private static Setting integer(String path, String property, int defaultValue, int minimum, String comment) {
        return new Setting(path, property, Type.INTEGER, Integer.toString(defaultValue), minimum, comment);
    }

    private static Setting string(String path, String property, String defaultValue, String comment) {
        return new Setting(path, property, Type.STRING, defaultValue, 0, comment);
    }

    private static Setting nonEmptyString(String path, String property, String defaultValue, String comment) {
        return new Setting(path, property, Type.NON_EMPTY_STRING, defaultValue, 0, comment);
    }

    private static Setting cpuTier(String path, String property, String defaultValue, String comment) {
        return new Setting(path, property, Type.CPU_TIER, defaultValue, 0, comment);
    }

    private enum Type {
        BOOLEAN,
        INTEGER,
        STRING,
        NON_EMPTY_STRING,
        CPU_TIER
    }

    private record Setting(String path, String property, Type type, String defaultValue, int minimum, String comment) {
        String[] pathSegments() {
            return this.path.split("\\.");
        }

        Object defaultValueAsYaml() {
            return switch (this.type) {
                case BOOLEAN -> Boolean.valueOf(this.defaultValue);
                case INTEGER -> Integer.valueOf(this.defaultValue);
                case STRING, NON_EMPTY_STRING, CPU_TIER -> this.defaultValue;
            };
        }

        String yamlComment() {
            return this.comment + "\nLegacy JVM property: -D" + this.property + "\nChanges require restart.";
        }

        String yamlDefaultValue() {
            return switch (this.type) {
                case BOOLEAN, INTEGER -> this.defaultValue;
                case STRING, NON_EMPTY_STRING, CPU_TIER -> "'" + this.defaultValue.replace("'", "''") + "'";
            };
        }

        String parse(String source, String origin) {
            String value = source.trim();
            try {
                return switch (this.type) {
                    case BOOLEAN -> parseBoolean(value, origin);
                    case INTEGER -> parseInteger(value, origin);
                    case STRING -> value;
                    case NON_EMPTY_STRING -> parseNonEmpty(value, origin);
                    case CPU_TIER -> parseCpuTier(value, origin);
                };
            } catch (NumberFormatException exception) {
                LOGGER.warn("Invalid value '{}' for Lattice setting '{}' from {}; using the next lower-priority value", source, this.path, origin);
                return null;
            }
        }

        private String parseBoolean(String value, String origin) {
            if ("true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value)) {
                return Boolean.toString(Boolean.parseBoolean(value));
            }
            LOGGER.warn("Invalid boolean '{}' for Lattice setting '{}' from {}; using the next lower-priority value", value, this.path, origin);
            return null;
        }

        private String parseInteger(String value, String origin) {
            int parsed = Integer.parseInt(value);
            if (parsed < this.minimum) {
                LOGGER.warn("Invalid integer '{}' for Lattice setting '{}' from {}; expected a value >= {}; using the next lower-priority value", value, this.path, origin, this.minimum);
                return null;
            }
            return Integer.toString(parsed);
        }

        private String parseNonEmpty(String value, String origin) {
            if (!value.isEmpty()) {
                return value;
            }
            LOGGER.warn("Invalid empty value for Lattice setting '{}' from {}; using the next lower-priority value", this.path, origin);
            return null;
        }

        private String parseCpuTier(String value, String origin) {
            String normalized = value.toLowerCase(java.util.Locale.ROOT);
            if ("auto".equals(normalized) || "scalar".equals(normalized)
                    || "avx2".equals(normalized) || "avx512".equals(normalized)) {
                return normalized;
            }
            LOGGER.warn("Invalid CPU tier '{}' for Lattice setting '{}' from {}; expected auto, scalar, avx2, or avx512; using the next lower-priority value", value, this.path, origin);
            return null;
        }
    }
}
