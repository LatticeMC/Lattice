package com.latticemc.lattice.bootstrap;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
    private static final String SYS_TRUSTED_SHA256 = "lattice.native.sha256";
    private static final String SYS_ALLOW_INSECURE = "lattice.native.allowInsecureHttp";
    private static final String DEFAULT_RELEASE_BASE = "https://github.com/LatticeMC/Lattice/releases/download";
    private static final String DEFAULT_RELEASE = "native-latest";
    private static final Pattern CHECKSUM_PATTERN = Pattern.compile("^([0-9A-Fa-f]{64})  (.*?)(?:\\r?\\n)?$");
    private static final Pattern SHA256_PATTERN = Pattern.compile("^[0-9A-Fa-f]{64}$");
    private static final int MAX_REDIRECTS = 5;
    private static final Set<String> ALLOWED_REDIRECT_HOSTS = Set.of(
            "github.com", "objects.githubusercontent.com", "release-assets.githubusercontent.com", "codeload.github.com");
    private static final String BUNDLED_DIGESTS_RESOURCE = "META-INF/native/digests.properties";
    private static final Map<String, String> BUNDLED_RELEASE_DIGESTS = loadBundledReleaseDigests();

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
        final String trustedDigest;
        try {
            trustedDigest = trustedDigestFor(asset);
        } catch (IllegalArgumentException invalidDigest) {
            throw newUnsatisfied("invalid -D" + SYS_TRUSTED_SHA256 + " value: " + invalidDigest.getMessage(), invalidDigest);
        }
        if (trustedDigest == null) {
            throw newUnsatisfied("refusing to download native library '" + asset
                    + "': no trusted SHA-256 is pinned. Bundle the library in the jar, or set -D"
                    + SYS_TRUSTED_SHA256 + "=<sha256> (or native.sha256 in lattice.yml).", null);
        }
        final String endpoint = buildReleaseAssetUrl(base, release, asset);
        LOGGER.info("Downloading Lattice native release asset '{}'", asset);
        return downloadToCache(endpoint, libFile, trustedDigest);
    }

    /** Resolves a locally pinned SHA-256; the adjacent remote .sha256 is deliberately ignored. */
    static String trustedDigestFor(String asset) {
        return trustedDigestFor(asset,
                System.getProperty(SYS_RELEASE, DEFAULT_RELEASE),
                BUNDLED_RELEASE_DIGESTS,
                System.getProperty(SYS_TRUSTED_SHA256, "").trim());
    }

    static String trustedDigestFor(String asset, String release,
                                   Map<String, String> bundledDigests, String configuredDigest) {
        final Map<String, String> selectedDigests = DEFAULT_RELEASE.equals(normalizeRelease(release))
                ? bundledDigests : Map.of();
        return trustedDigestFor(asset, selectedDigests, configuredDigest);
    }

    static String trustedDigestFor(String asset, Map<String, String> bundledDigests, String configuredDigest) {
        final String configured = configuredDigest == null ? "" : configuredDigest.trim();
        if (!configured.isEmpty() && !SHA256_PATTERN.matcher(configured).matches()) {
            throw new IllegalArgumentException("invalid SHA-256 for -D" + SYS_TRUSTED_SHA256
                    + ": expected 64 hexadecimal characters");
        }
        if (!configured.isEmpty()) return configured.toLowerCase(Locale.ROOT);
        final String builtIn = bundledDigests.get(asset);
        if (builtIn != null && SHA256_PATTERN.matcher(builtIn).matches()) {
            return builtIn.toLowerCase(Locale.ROOT);
        }
        return null;
    }

    static Map<String, String> parseBundledReleaseDigests(InputStream input) throws IOException {
        if (input == null) return Map.of();
        final Map<String, String> result = new HashMap<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                final String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("!")) continue;
                final int separator = trimmed.indexOf('=');
                if (separator <= 0) {
                    LOGGER.warn("Ignoring malformed bundled native digest record at line {}", lineNumber);
                    continue;
                }
                final String asset = trimmed.substring(0, separator).trim();
                final String digest = trimmed.substring(separator + 1).trim();
                if (!isSupportedAsset(asset)) {
                    LOGGER.warn("Ignoring unknown bundled native digest asset '{}'", asset);
                    continue;
                }
                if (!isSha256(digest)) {
                    LOGGER.warn("Ignoring invalid bundled native digest for '{}'", asset);
                    continue;
                }
                if (result.putIfAbsent(asset, digest.toLowerCase(Locale.ROOT)) != null) {
                    LOGGER.warn("Ignoring duplicate bundled native digest for '{}'", asset);
                }
            }
        }
        return Collections.unmodifiableMap(result);
    }

    private static Map<String, String> loadBundledReleaseDigests() {
        final ClassLoader loader = LatticeNativeLoader.class.getClassLoader();
        if (loader == null) return Map.of();
        try (InputStream input = loader.getResourceAsStream(BUNDLED_DIGESTS_RESOURCE)) {
            return parseBundledReleaseDigests(input);
        } catch (IOException failure) {
            LOGGER.warn("Failed to read bundled native digest manifest '{}'; downloads will require an explicit digest",
                    BUNDLED_DIGESTS_RESOURCE, failure);
            return Map.of();
        }
    }

    private static boolean isSupportedAsset(String asset) {
        for (Os os : Os.values()) {
            if (os == Os.UNKNOWN) continue;
            for (Arch arch : Arch.values()) {
                if (arch != Arch.UNKNOWN && asset.equals(assetName(new Platform(os, arch)))) return true;
            }
        }
        return false;
    }

    static void verifyTrustedDigest(byte[] bytes, String trustedHex, String asset) throws IOException {
        if (!isSha256(trustedHex)) throw new IOException("invalid trusted SHA-256 for " + asset);
        final String actual = sha256Hex(bytes);
        if (!actual.equalsIgnoreCase(trustedHex)) {
            throw new IOException("SHA-256 mismatch for " + asset + ": trusted "
                    + trustedHex.toLowerCase(Locale.ROOT) + ", got " + actual);
        }
    }

    static boolean isSha256(String value) {
        return value != null && SHA256_PATTERN.matcher(value).matches();
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

    private static Path downloadToCache(String endpoint, String libFile, String trustedDigest) throws IOException {
        final boolean allowInsecure = Boolean.parseBoolean(System.getProperty(SYS_ALLOW_INSECURE, "false"));
        URI uri = requireHttpUri(endpoint, allowInsecure);
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            final HttpURLConnection connection = openConnection(uri);
            connection.setInstanceFollowRedirects(false);
            try {
                final int responseCode = connection.getResponseCode();
                if (responseCode >= 300 && responseCode < 400) {
                    final String location = connection.getHeaderField("Location");
                    if (location == null) throw new IOException("redirect without Location header for " + uri);
                    final URI next = uri.resolve(location);
                    requireAllowedRedirect(next, allowInsecure);
                    uri = next;
                    continue;
                }
                if (responseCode != HttpURLConnection.HTTP_OK) {
                    throw new IOException("HTTP " + responseCode + " for " + uri);
                }
                final long contentLength = connection.getContentLengthLong();
                if (contentLength > NativeLibraryCache.MAX_NATIVE_BYTES) {
                    throw new IOException("native library exceeds " + NativeLibraryCache.MAX_NATIVE_BYTES + " bytes");
                }
                try (InputStream in = connection.getInputStream()) {
                    return extractToCache(libFile, in, trustedDigest);
                }
            } finally {
                connection.disconnect();
            }
        }
        throw new IOException("too many redirects while downloading " + endpoint);
    }

    static URI requireHttpUri(String endpoint, boolean allowInsecure) throws IOException {
        final URI uri;
        try {
            uri = URI.create(endpoint);
        } catch (IllegalArgumentException invalidEndpoint) {
            throw new IOException("invalid native release URL: " + endpoint, invalidEndpoint);
        }
        final String scheme = uri.getScheme();
        if (scheme == null || uri.getHost() == null) throw new IOException("invalid native release URL: " + endpoint);
        if ("https".equalsIgnoreCase(scheme)) return uri;
        if (allowInsecure && "http".equalsIgnoreCase(scheme)) return uri;
        throw new IOException("refusing non-HTTPS native release URL: " + endpoint
                + " (set -D" + SYS_ALLOW_INSECURE + "=true to explicitly allow insecure transport)");
    }

    static void requireAllowedRedirect(URI next, boolean allowInsecure) throws IOException {
        final String scheme = next.getScheme();
        final String host = next.getHost() == null ? "" : next.getHost().toLowerCase(Locale.ROOT);
        if (!("https".equalsIgnoreCase(scheme) || (allowInsecure && "http".equalsIgnoreCase(scheme)))) {
            throw new IOException("refusing redirect to insecure URL: " + next);
        }
        if (!allowInsecure && !ALLOWED_REDIRECT_HOSTS.contains(host)) {
            throw new IOException("refusing redirect to untrusted host: " + host);
        }
    }

    private static HttpURLConnection openConnection(URI uri) throws IOException {
        final Object rawConnection = uri.toURL().openConnection();
        if (!(rawConnection instanceof HttpURLConnection httpConnection)) {
            throw new IOException("native release URL is not HTTP(S): " + uri);
        }
        httpConnection.setConnectTimeout(15_000);
        httpConnection.setReadTimeout(60_000);
        httpConnection.setRequestProperty("User-Agent", "Lattice-native-loader");
        return httpConnection;
    }

    private static String encodePath(String value) {
        return value.replace("%", "%25").replace("/", "%2F").replace(" ", "%20");
    }

    private static Path extractToCache(String libFile, InputStream in) throws IOException {
        return extractToCache(libFile, in, null);
    }

    static Path extractToCache(String libFile, InputStream in, String trustedDigest) throws IOException {
        final String override = System.getProperty(SYS_CACHE_DIR, "").trim();
        final Path parent = override.isEmpty() ? Path.of(System.getProperty("java.io.tmpdir")) : Path.of(override);
        return NativeLibraryCache.extract(parent, libFile, in, trustedDigest);
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
