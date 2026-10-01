package com.latticemc.lattice.worldgen;

import com.latticemc.lattice.config.LatticeConfig;
import com.latticemc.lattice.config.SeedProtectionConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.function.Supplier;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;

/** World-scoped policy and key derivation for protected structure random streams. */
public final class SeedProtection {
    private static final ThreadLocal<ServerLevel> CURRENT_LEVEL = new ThreadLocal<>();
    private static final ThreadLocal<String> CURRENT_STRUCTURE = new ThreadLocal<>();
    private static volatile KeyMaterial keyMaterial;

    private SeedProtection() {
    }

    public static Scope enter(ServerLevel level) {
        ServerLevel previous = CURRENT_LEVEL.get();
        CURRENT_LEVEL.set(level);
        return () -> {
            if (previous == null) {
                CURRENT_LEVEL.remove();
            } else {
                CURRENT_LEVEL.set(previous);
            }
        };
    }

    public static ServerLevel currentLevel() {
        return CURRENT_LEVEL.get();
    }

    public static Scope enterStructure(String structureKey) {
        String previous = CURRENT_STRUCTURE.get();
        CURRENT_STRUCTURE.set(structureKey);
        return () -> {
            if (previous == null) {
                CURRENT_STRUCTURE.remove();
            } else {
                CURRENT_STRUCTURE.set(previous);
            }
        };
    }

    public static <T> T withLevel(ServerLevel level, Supplier<T> action) {
        try (Scope ignored = enter(level)) {
            return action.get();
        }
    }

    public static boolean protectsSet(String structureSetKey) {
        ServerLevel level = CURRENT_LEVEL.get();
        return level != null && protects(level, null, structureSetKey);
    }

    public static RandomSource forStructureSet(String structureSetKey, String phase, long x, long z) {
        ServerLevel level = CURRENT_LEVEL.get();
        return level == null ? null : forContext(level, structureSetKey, null, phase, x, z, 0L);
    }

    public static RandomSource forStructure(ServerLevel level, String structureKey, String phase, long x, long z, long extra) {
        return forContext(level, null, structureKey, phase, x, z, extra);
    }

    public static RandomSource forStructure(String structureKey, String phase, long x, long z, long extra) {
        ServerLevel level = CURRENT_LEVEL.get();
        return level == null ? null : forStructure(level, structureKey, phase, x, z, extra);
    }

    public static RandomSource forCurrentStructure(String phase, long x, long z, long extra) {
        String structureKey = CURRENT_STRUCTURE.get();
        return structureKey == null ? null : forStructure(structureKey, phase, x, z, extra);
    }

    private static RandomSource forContext(ServerLevel level, String structureSetKey, String structureKey, String phase, long x, long z, long extra) {
        SeedProtectionConfig config = LatticeConfig.seedProtection();
        String worldName = level.getWorld().getName();
        if (!config.enabledFor(level.uuid.toString(), worldName)
                || !config.protectsStructure(level.uuid.toString(), worldName, structureKey, structureSetKey)) {
            return null;
        }
        byte[] master = keyMaterial().key();
        String context = "lattice-seed-protection/v1|"
                + level.uuid + "|"
                + level.dimension().identifier() + "|"
                + value(structureSetKey) + "|"
                + value(structureKey) + "|"
                + phase + "|" + x + "|" + z + "|" + extra;
        byte[] prk = hmac("lattice-seed-protection/v1", master);
        return new SeedProtectionRandomSource(SeedProtectionRandomSource.derive(prk, context, 32));
    }

    private static boolean protects(ServerLevel level, String structureKey, String structureSetKey) {
        SeedProtectionConfig config = LatticeConfig.seedProtection();
        return config.enabledFor(level.uuid.toString(), level.getWorld().getName())
                && config.protectsStructure(level.uuid.toString(), level.getWorld().getName(), structureKey, structureSetKey);
    }

    private static String value(String value) {
        return value == null ? "-" : value;
    }

    private static KeyMaterial keyMaterial() {
        KeyMaterial existing = keyMaterial;
        if (existing != null) {
            return existing;
        }
        synchronized (SeedProtection.class) {
            existing = keyMaterial;
            if (existing == null) {
                existing = loadKey(LatticeConfig.seedProtection().masterKeyFile());
                keyMaterial = existing;
            }
            return existing;
        }
    }

    private static KeyMaterial loadKey(Path path) {
        try {
            byte[] key = Files.readAllBytes(path);
            if (key.length != 32) {
                throw new IllegalStateException("Seed protection key must contain exactly 32 bytes: " + path.toAbsolutePath());
            }
            return new KeyMaterial(key, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key), 0, 8));
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read seed protection key: " + path.toAbsolutePath(), exception);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static byte[] hmac(String salt, byte[] key) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(salt.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(key);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", exception);
        }
    }

    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }

    private record KeyMaterial(byte[] key, String id) {
        private KeyMaterial {
            key = key.clone();
        }
    }
}
