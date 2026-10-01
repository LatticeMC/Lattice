package com.latticemc.lattice.config;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.serialize.SerializationException;

/** Immutable startup configuration for protected structure random streams. */
public record SeedProtectionConfig(
        Path masterKeyFile,
        boolean requireHardwareAes,
        WorldPolicy defaultPolicy,
        Map<String, WorldPolicy> worldsByName) {

    private static final Set<String> BUILTIN_UNDERGROUND_STRUCTURES = Set.of(
            "minecraft:mineshaft",
            "minecraft:mineshaft_mesa",
            "minecraft:stronghold",
            "minecraft:ancient_city",
            "minecraft:trial_chambers",
            "minecraft:buried_treasure",
            "minecraft:nether_fossil");

    private static final Set<String> BUILTIN_UNDERGROUND_STRUCTURE_SETS = Set.of(
            "minecraft:mineshafts",
            "minecraft:strongholds",
            "minecraft:ancient_cities",
            "minecraft:trial_chambers",
            "minecraft:buried_treasures",
            "minecraft:nether_fossils",
            "minecraft:trail_ruins");

    public static SeedProtectionConfig defaults() {
        return new SeedProtectionConfig(Path.of("config/lattice/seed-protection.key"), false,
                new WorldPolicy(false, Set.of("#lattice:underground_structures"), Set.of()), Map.of());
    }

    public static SeedProtectionConfig parse(ConfigurationNode root) {
        ConfigurationNode node = root.node("worldgen", "seed-protection");
        Path keyFile = Path.of(node.node("master-key-file").getString("config/lattice/seed-protection.key"));
        boolean requireHardwareAes = node.node("require-hardware-aes").getBoolean(false);
        WorldPolicy defaultPolicy = WorldPolicy.parse(node.node("default"), false, defaults().defaultPolicy());
        Map<String, WorldPolicy> worlds = new LinkedHashMap<>();
        for (String selector : List.of("by-uuid", "by-name")) {
            ConfigurationNode selectedWorlds = node.node("worlds", selector);
            for (Map.Entry<Object, ? extends ConfigurationNode> entry : selectedWorlds.childrenMap().entrySet()) {
                worlds.put(selector + ":" + entry.getKey(), WorldPolicy.parse(entry.getValue(), defaultPolicy.enabled(), defaultPolicy));
            }
        }
        return new SeedProtectionConfig(keyFile, requireHardwareAes, defaultPolicy, Map.copyOf(worlds));
    }

    public WorldPolicy policyFor(String worldUuid, String worldName) {
        return this.worldsByName.getOrDefault("by-uuid:" + worldUuid,
                this.worldsByName.getOrDefault("by-name:" + worldName, this.defaultPolicy));
    }

    public boolean enabledFor(String worldUuid, String worldName) {
        return this.policyFor(worldUuid, worldName).enabled();
    }

    public boolean protectsStructure(String worldUuid, String worldName, String structureKey, String structureSetKey) {
        return this.policyFor(worldUuid, worldName).matches(structureKey, structureSetKey);
    }

    public record WorldPolicy(boolean enabled, Set<String> include, Set<String> exclude) {
        private static WorldPolicy parse(ConfigurationNode node, boolean enabledDefault, WorldPolicy defaults) {
            boolean enabled = node.node("enabled").getBoolean(enabledDefault);
            Set<String> include = normalizedStrings(node.node("structures", "include"), defaults.include());
            Set<String> exclude = normalizedStrings(node.node("structures", "exclude"), defaults.exclude());
            if (include.isEmpty()) {
                include = Set.of("#lattice:underground_structures");
            }
            return new WorldPolicy(enabled, include, exclude);
        }

        private boolean matches(String structureKey, String structureSetKey) {
            if (!this.enabled()) {
                return false;
            }
            String structure = normalize(structureKey);
            String structureSet = normalize(structureSetKey);
            if (this.exclude().contains(structure) || this.exclude().contains(structureSet)) {
                return false;
            }
            for (String selector : this.include()) {
                if (selector.equals("*") || selector.equals(structure) || selector.equals(structureSet)) {
                    return true;
                }
                if (selector.equals("#lattice:underground_structures")
                        && (BUILTIN_UNDERGROUND_STRUCTURES.contains(structure)
                        || BUILTIN_UNDERGROUND_STRUCTURE_SETS.contains(structureSet))) {
                    return true;
                }
            }
            return false;
        }

        private static Set<String> normalizedStrings(ConfigurationNode node, Set<String> fallback) {
            if (node.virtual()) {
                return fallback;
            }
            List<String> values;
            try {
                values = node.getList(String.class, List.of());
            } catch (SerializationException | RuntimeException ignored) {
                values = List.of();
            }
            Set<String> normalized = new LinkedHashSet<>();
            for (String value : values) {
                String item = normalize(value);
                if (!item.isEmpty()) {
                    normalized.add(item);
                }
            }
            return Set.copyOf(normalized);
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
