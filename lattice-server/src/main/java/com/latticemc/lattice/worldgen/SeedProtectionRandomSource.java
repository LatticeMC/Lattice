package com.latticemc.lattice.worldgen;

import java.security.GeneralSecurityException;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.BitRandomSource;
import net.minecraft.world.level.levelgen.MarsagliaPolarGaussian;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;

/** AES-256 counter stream exposed through Minecraft's RandomSource contract. */
public final class SeedProtectionRandomSource implements BitRandomSource {
    private final byte[] key;
    private final Cipher cipher;
    private final byte[] counter = new byte[16];
    private final MarsagliaPolarGaussian gaussian = new MarsagliaPolarGaussian(this);

    public SeedProtectionRandomSource(byte[] key) {
        if (key.length != 32) {
            throw new IllegalArgumentException("AES-256 requires a 32-byte key");
        }
        this.key = key.clone();
        try {
            this.cipher = Cipher.getInstance("AES/ECB/NoPadding");
            this.cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(this.key, "AES"));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("AES-256 is unavailable in the active JCE provider", exception);
        }
    }

    @Override
    public RandomSource fork() {
        return new SeedProtectionRandomSource(SeedProtectionRandomSource.derive(this.key, "fork:" + this.nextLong(), 32));
    }

    @Override
    public PositionalRandomFactory forkPositional() {
        byte[] parent = this.key.clone();
        return new PositionalRandomFactory() {
            @Override
            public RandomSource fromHashOf(String name) {
                return new SeedProtectionRandomSource(derive(parent, "hash:" + name, 32));
            }

            @Override
            public RandomSource fromSeed(long seed) {
                return new SeedProtectionRandomSource(derive(parent, "seed:" + seed, 32));
            }

            @Override
            public RandomSource at(int x, int y, int z) {
                return new SeedProtectionRandomSource(derive(parent, "at:" + x + ":" + y + ":" + z, 32));
            }

            @Override
            public void parityConfigString(StringBuilder builder) {
                builder.append("SeedProtectionPositionalRandomFactory");
            }
        };
    }

    @Override
    public void setSeed(long seed) {
        throw new UnsupportedOperationException("Protected AES streams cannot be reseeded");
    }

    @Override
    public int next(int bits) {
        if (bits < 1 || bits > 32) {
            throw new IllegalArgumentException("bits must be between 1 and 32");
        }
        return this.nextRawInt() >>> (32 - bits);
    }

    @Override
    public double nextGaussian() {
        return this.gaussian.nextGaussian();
    }

    private int nextRawInt() {
        byte[] block;
        try {
            block = this.cipher.doFinal(this.counter);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("AES-256 stream generation failed", exception);
        }
        incrementCounter(this.counter);
        return ((block[0] & 0xff) << 24)
                | ((block[1] & 0xff) << 16)
                | ((block[2] & 0xff) << 8)
                | (block[3] & 0xff);
    }

    private static void incrementCounter(byte[] value) {
        for (int index = value.length - 1; index >= 0; index--) {
            if (++value[index] != 0) {
                return;
            }
        }
        throw new IllegalStateException("AES-256 counter exhausted");
    }

    static byte[] derive(byte[] key, String info, int length) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            byte[] output = new byte[length];
            byte[] previous = new byte[0];
            int written = 0;
            for (int counter = 1; written < length; counter++) {
                mac.reset();
                mac.update(previous);
                mac.update(info.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                mac.update((byte) counter);
                previous = mac.doFinal();
                int count = Math.min(previous.length, length - written);
                System.arraycopy(previous, 0, output, written, count);
                written += count;
            }
            return output;
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", exception);
        }
    }
}
