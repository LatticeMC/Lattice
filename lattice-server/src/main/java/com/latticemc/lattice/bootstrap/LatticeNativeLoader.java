package com.latticemc.lattice.bootstrap;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.ByteArrayInputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class LatticeNativeLoader {

    private static final Logger LOGGER = LoggerFactory.getLogger("LatticeNativeLoader");
    private static final String SYS_OVERRIDE = "lattice.native.path";
    private static final String SYS_CACHE_DIR = "lattice.native.cacheDir";
    private static final String SYS_DOWNLOAD = "lattice.native.download";
    private static final String SYS_RELEASE = "lattice.native.release";
    private static final String SYS_RELEASE_BASE = "lattice.native.releaseBaseUrl";
    private static final String DEFAULT_RELEASE_BASE = "https://github.com/LatticeMC/Lattice/releases/download";
    private static final String DEFAULT_RELEASE = "native-latest";
    private static final Pattern CHECKSUM_PATTERN = Pattern.compile("^([0-9A-Fa-f]{64})  (.*?)(?:\\r?\\n)?$");

    private LatticeNativeLoader() {}

    public enum Os {
        LINUX("linux", "so", "lib"),
        MAC("macos", "dylib", "lib"),
        WINDOWS("windows", "dll", ""),
        FREEBSD("freebsd", "so", "lib"),
        UNKNOWN("unknown", "", "");

        public final String dirName;
        public final String libExt;
        public final String libPrefix;

        Os(String dirName, String libExt, String libPrefix) {
            this.dirName = dirName;
            this.libExt = libExt;
            this.libPrefix = libPrefix;
        }
    }

    public enum Arch {
        X86_64("x86_64"),
        AARCH64("aarch64"),
        UNKNOWN("unknown");

        public final String dirName;

        Arch(String dirName) {
            this.dirName = dirName;
        }
    }

    public record Platform(Os os, Arch arch) {
        public String tag() { return os.dirName + "-" + arch.dirName; }
        public boolean isSupported() { return os != Os.UNKNOWN && arch != Arch.UNKNOWN; }
    }

    public static Platform detect() {
        return detect(System.getProperty("os.name", ""), System.getProperty("os.arch", ""));
    }

    static Platform detect(String osNameValue, String osArchValue) {
        final String osName = osNameValue == null ? "" : osNameValue.toLowerCase(Locale.ROOT);
        final String osArch = osArchValue == null ? "" : osArchValue.toLowerCase(Locale.ROOT);

        Os os;
        if (osName.contains("linux")) os = Os.LINUX;
        else if (osName.contains("mac") || osName.contains("darwin")) os = Os.MAC;
        else if (osName.contains("win")) os = Os.WINDOWS;
        else if (osName.contains("freebsd")) os = Os.FREEBSD;
        else os = Os.UNKNOWN;

        Arch arch;
        if (osArch.equals("amd64") || osArch.equals("x86_64") || osArch.equals("x64")) arch = Arch.X86_64;
        else if (osArch.equals("aarch64") || osArch.equals("arm64")) arch = Arch.AARCH64;
        else arch = Arch.UNKNOWN;

        return new Platform(os, arch);
    }

    public static void load(String baseLibName) {
        final String override = System.getProperty(SYS_OVERRIDE, "").trim();
        if (!override.isEmpty()) {
            try {
                System.load(override);
                LOGGER.info("Loaded lattice native from override path: {}", override);
                return;
            } catch (Throwable t) {
                throw newUnsatisfied("override path failed: " + override, t);
            }
        }

        final Platform pf = detect();
        if (!pf.isSupported()) {
            throw newUnsatisfied("no native library bundled for os=" + System.getProperty("os.name")
                    + " arch=" + System.getProperty("os.arch"), null);
        }

        final String libFile = pf.os.libPrefix + baseLibName + "." + pf.os.libExt;
        final String resourcePath = "META-INF/native/" + pf.tag() + "/" + libFile;
        final ClassLoader cl = LatticeNativeLoader.class.getClassLoader();

        Path extracted;
            try (InputStream in = cl.getResourceAsStream(resourcePath)) {
            if (in != null) {
                extracted = extractToCache(libFile, in);
            } else {
                extracted = extractFromFallbackResource(cl, libFile, resourcePath);
            }
        } catch (IOException e) {
            throw newUnsatisfied("failed to read " + resourcePath + " from classpath", e);
        }

        try {
            System.load(extracted.toAbsolutePath().toString());
        } catch (Throwable t) {
            throw newUnsatisfied("System.load failed for " + extracted, t);
        }

        LOGGER.info("Loaded lattice native ({}): {}", pf.tag(), extracted);
    }

    private static Path extractFromFallbackResource(ClassLoader cl,
                                                    String libFile,
                                                    String originalResourcePath) throws IOException {
        try (InputStream fallback = cl.getResourceAsStream(libFile)) {
            if (fallback == null) {
                return downloadReleaseNative(libFile, originalResourcePath);
            }
            LOGGER.warn("Lattice native loaded from fallback classpath resource '{}'; prefer packaging under META-INF/native/<platform>/{}", libFile, libFile);
            return extractToCache(libFile, fallback);
        }
    }

    private static Path downloadReleaseNative(String libFile, String resourcePath) throws IOException {
        if (!Boolean.parseBoolean(System.getProperty(SYS_DOWNLOAD, "true"))) {
            throw newUnsatisfied("lattice native missing from jar: " + resourcePath
                    + " (download disabled with -D" + SYS_DOWNLOAD + "=false)", null);
        }
        final Platform platform = detect();
        final String asset = assetName(platform);
        final String release = normalizeRelease(System.getProperty(SYS_RELEASE, DEFAULT_RELEASE));
        final String base = System.getProperty(SYS_RELEASE_BASE, DEFAULT_RELEASE_BASE).trim();
        final String endpoint = buildReleaseAssetUrl(base, release, asset);
        final byte[] nativeBytes = downloadBytes(endpoint);
        final byte[] checksumBytes = downloadBytes(endpoint + ".sha256");
        verifyChecksum(nativeBytes, new String(checksumBytes, StandardCharsets.UTF_8), asset);
        LOGGER.info("Downloading Lattice native release asset '{}'", asset);
        return extractToCache(libFile, new ByteArrayInputStream(nativeBytes));
    }

    static String assetName(Platform platform) {
        return "lattice-native-" + platform.tag() + "." + platform.os.libExt;
    }

    static String buildReleaseAssetUrl(String base, String release, String asset) {
        String normalizedBase = base.trim();
        while (normalizedBase.endsWith("/")) {
            normalizedBase = normalizedBase.substring(0, normalizedBase.length() - 1);
        }
        return normalizedBase + "/" + encodePath(normalizeRelease(release)) + "/" + encodePath(asset);
    }

    static String normalizeRelease(String release) {
        final String value = release == null ? "" : release.trim();
        return value.isEmpty() || value.equalsIgnoreCase("latest") ? DEFAULT_RELEASE : value;
    }

    static String parseChecksum(String content, String asset) {
        Matcher matcher = CHECKSUM_PATTERN.matcher(content);
        if (!matcher.matches() || !matcher.group(2).equals(asset)) {
            throw new IllegalArgumentException("invalid SHA-256 checksum record for " + asset);
        }
        return matcher.group(1).toLowerCase(Locale.ROOT);
    }

    static void verifyChecksum(byte[] nativeBytes, String checksumContent, String asset) throws IOException {
        final String expectedHash;
        try {
            expectedHash = parseChecksum(checksumContent, asset);
        } catch (IllegalArgumentException invalidChecksum) {
            throw new IOException("invalid SHA-256 checksum for " + asset, invalidChecksum);
        }
        final String actualHash = sha256Hex(nativeBytes);
        if (!actualHash.equalsIgnoreCase(expectedHash)) {
            throw new IOException("SHA-256 mismatch for " + asset + ": expected " + expectedHash + ", got " + actualHash);
        }
    }

    private static byte[] downloadBytes(String endpoint) throws IOException {
        final HttpURLConnection connection;
        try {
            final Object rawConnection = URI.create(endpoint).toURL().openConnection();
            if (!(rawConnection instanceof HttpURLConnection httpConnection)) {
                throw new IOException("native release URL is not HTTP(S): " + endpoint);
            }
            connection = httpConnection;
        } catch (IllegalArgumentException invalidEndpoint) {
            throw new IOException("invalid native release URL: " + endpoint, invalidEndpoint);
        }
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(60_000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", "Lattice-native-loader");
        try {
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                throw new IOException("HTTP " + connection.getResponseCode() + " for " + endpoint);
            }
            try (InputStream in = connection.getInputStream()) {
                return in.readAllBytes();
            }
        } finally {
            connection.disconnect();
        }
    }

    private static String encodePath(String value) {
        return value.replace("%", "%25").replace("/", "%2F").replace(" ", "%20");
    }

    private static Path extractToCache(String libFile, InputStream in) throws IOException {
        final Path cacheDir = resolveCacheDir();
        Files.createDirectories(cacheDir);
        final byte[] bytes = in.readAllBytes();
        final String hashHex = shortHash(bytes);
        final Path target = cacheDir.resolve(libFile + "." + hashHex);

        if (Files.exists(target) && Files.size(target) == bytes.length) {
            return target;
        }

        final Path tmp = Files.createTempFile(cacheDir, libFile + ".", ".part");
        try (OutputStream out = Files.newOutputStream(tmp)) {
            out.write(bytes);
        }
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicFailed) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
        target.toFile().deleteOnExit();
        return target;
    }

    private static Path resolveCacheDir() {
        final String override = System.getProperty(SYS_CACHE_DIR, "").trim();
        if (!override.isEmpty()) {
            return Path.of(override);
        }
        return Path.of(System.getProperty("java.io.tmpdir"), "lattice-native");
    }

    private static String shortHash(byte[] bytes) {
        try {
            final byte[] full = MessageDigest.getInstance("SHA-256").digest(bytes);
            final StringBuilder sb = new StringBuilder(16);
            for (int i = 0; i < 8; ++i) {
                sb.append(String.format("%02x", full[i] & 0xFF));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            final byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            final StringBuilder sb = new StringBuilder(64);
            for (byte value : digest) {
                sb.append(String.format("%02x", value & 0xFF));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    private static UnsatisfiedLinkError newUnsatisfied(String msg, Throwable cause) {
        final UnsatisfiedLinkError e = new UnsatisfiedLinkError(msg);
        if (cause != null) e.initCause(cause);
        return e;
    }
}
