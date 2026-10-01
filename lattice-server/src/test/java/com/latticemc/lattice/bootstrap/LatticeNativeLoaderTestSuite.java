package com.latticemc.lattice.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;

class LatticeNativeLoaderTestSuite {

    @Test
    void assetNamesCoverSupportedPlatforms() {
        assertEquals("lattice-native-linux-x86_64.so", LatticeNativeLoader.assetName(new LatticeNativeLoader.Platform(LatticeNativeLoader.Os.LINUX, LatticeNativeLoader.Arch.X86_64)));
        assertEquals("lattice-native-linux-aarch64.so", LatticeNativeLoader.assetName(new LatticeNativeLoader.Platform(LatticeNativeLoader.Os.LINUX, LatticeNativeLoader.Arch.AARCH64)));
        assertEquals("lattice-native-windows-x86_64.dll", LatticeNativeLoader.assetName(new LatticeNativeLoader.Platform(LatticeNativeLoader.Os.WINDOWS, LatticeNativeLoader.Arch.X86_64)));
        assertEquals("lattice-native-windows-aarch64.dll", LatticeNativeLoader.assetName(new LatticeNativeLoader.Platform(LatticeNativeLoader.Os.WINDOWS, LatticeNativeLoader.Arch.AARCH64)));
        assertEquals("lattice-native-macos-x86_64.dylib", LatticeNativeLoader.assetName(new LatticeNativeLoader.Platform(LatticeNativeLoader.Os.MAC, LatticeNativeLoader.Arch.X86_64)));
        assertEquals("lattice-native-macos-aarch64.dylib", LatticeNativeLoader.assetName(new LatticeNativeLoader.Platform(LatticeNativeLoader.Os.MAC, LatticeNativeLoader.Arch.AARCH64)));
        assertEquals("lattice-native-freebsd-x86_64.so", LatticeNativeLoader.assetName(new LatticeNativeLoader.Platform(LatticeNativeLoader.Os.FREEBSD, LatticeNativeLoader.Arch.X86_64)));
        assertEquals("lattice-native-freebsd-aarch64.so", LatticeNativeLoader.assetName(new LatticeNativeLoader.Platform(LatticeNativeLoader.Os.FREEBSD, LatticeNativeLoader.Arch.AARCH64)));
    }

    @Test
    void detectCoversSupportedPlatformsAndArchitectures() {
        assertEquals(new LatticeNativeLoader.Platform(LatticeNativeLoader.Os.LINUX, LatticeNativeLoader.Arch.X86_64), LatticeNativeLoader.detect("Linux", "amd64"));
        assertEquals(new LatticeNativeLoader.Platform(LatticeNativeLoader.Os.LINUX, LatticeNativeLoader.Arch.AARCH64), LatticeNativeLoader.detect("Linux", "aarch64"));
        assertEquals(new LatticeNativeLoader.Platform(LatticeNativeLoader.Os.WINDOWS, LatticeNativeLoader.Arch.X86_64), LatticeNativeLoader.detect("Windows 11", "x86_64"));
        assertEquals(new LatticeNativeLoader.Platform(LatticeNativeLoader.Os.WINDOWS, LatticeNativeLoader.Arch.AARCH64), LatticeNativeLoader.detect("Windows", "ARM64"));
        assertEquals(new LatticeNativeLoader.Platform(LatticeNativeLoader.Os.MAC, LatticeNativeLoader.Arch.X86_64), LatticeNativeLoader.detect("Mac OS X", "x64"));
        assertEquals(new LatticeNativeLoader.Platform(LatticeNativeLoader.Os.MAC, LatticeNativeLoader.Arch.AARCH64), LatticeNativeLoader.detect("Darwin", "arm64"));
        assertEquals(new LatticeNativeLoader.Platform(LatticeNativeLoader.Os.FREEBSD, LatticeNativeLoader.Arch.X86_64), LatticeNativeLoader.detect("FreeBSD", "amd64"));
        assertEquals(new LatticeNativeLoader.Platform(LatticeNativeLoader.Os.FREEBSD, LatticeNativeLoader.Arch.AARCH64), LatticeNativeLoader.detect("FreeBSD", "aarch64"));
        assertEquals(LatticeNativeLoader.Os.UNKNOWN, LatticeNativeLoader.detect("Plan9", "amd64").os());
    }

    @Test
    void releaseUrlsUseNativeLatestAndExplicitTags() {
        assertEquals("https://github.com/LatticeMC/Lattice/releases/download/native-latest/lattice-native-linux-x86_64.so",
                LatticeNativeLoader.buildReleaseAssetUrl(
                        "https://github.com/LatticeMC/Lattice/releases/download/",
                        "native-latest", "lattice-native-linux-x86_64.so"));
        assertEquals("https://github.com/LatticeMC/Lattice/releases/download/native-latest/lattice-native-linux-x86_64.so",
                LatticeNativeLoader.buildReleaseAssetUrl(
                        "https://github.com/LatticeMC/Lattice/releases/download", "latest",
                        "lattice-native-linux-x86_64.so"));
        assertEquals("https://example.test/releases/download/v1.2.3/lattice-native-windows-x86_64.dll",
                LatticeNativeLoader.buildReleaseAssetUrl(
                        "https://example.test/releases/download", "v1.2.3",
                        "lattice-native-windows-x86_64.dll"));
    }

    @Test
    void checksumParsingAcceptsMatchingSha256Record() throws Exception {
        byte[] bytes = "native".getBytes(StandardCharsets.UTF_8);
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        assertEquals(digest, LatticeNativeLoader.parseChecksum(
                digest + "  lattice-native-linux-x86_64.so\n", "lattice-native-linux-x86_64.so"));
        LatticeNativeLoader.verifyChecksum(bytes,
                digest + "  lattice-native-linux-x86_64.so\n", "lattice-native-linux-x86_64.so");
    }

    @Test
    void checksumParsingRejectsWrongFilename() {
        String digest = "a".repeat(64);
        assertThrows(IllegalArgumentException.class, () -> LatticeNativeLoader.parseChecksum(
                digest + "  wrong.so\n", "lattice-native-linux-x86_64.so"));
    }

    @Test
    void checksumParsingRejectsNon64Hex() {
        assertThrows(IllegalArgumentException.class, () -> LatticeNativeLoader.parseChecksum(
                "abc  lattice-native-linux-x86_64.so\n", "lattice-native-linux-x86_64.so"));
    }

    @Test
    void checksumParsingRejectsMalformedStructure() {
        assertThrows(IllegalArgumentException.class, () -> LatticeNativeLoader.parseChecksum(
                "a".repeat(64) + " lattice-native-linux-x86_64.so\n", "lattice-native-linux-x86_64.so"));
    }

    @Test
    void checksumVerificationRejectsHashMismatch() {
        byte[] bytes = "native".getBytes(StandardCharsets.UTF_8);
        assertThrows(IOException.class, () -> LatticeNativeLoader.verifyChecksum(bytes,
                "0".repeat(64) + "  lattice-native-linux-x86_64.so\n",
                "lattice-native-linux-x86_64.so"));
    }

    @Test
    void trustedDigestIsRequiredAndValidated() throws Exception {
        String previous = System.getProperty("lattice.native.sha256");
        try {
            System.clearProperty("lattice.native.sha256");
            assertEquals(null, LatticeNativeLoader.trustedDigestFor("lattice-native-linux-x86_64.so"));
            System.setProperty("lattice.native.sha256", "AB".repeat(32));
            assertEquals("ab".repeat(32), LatticeNativeLoader.trustedDigestFor("lattice-native-linux-x86_64.so"));
            System.setProperty("lattice.native.sha256", "not-a-digest");
            assertThrows(IllegalArgumentException.class,
                    () -> LatticeNativeLoader.trustedDigestFor("lattice-native-linux-x86_64.so"));
        } finally {
            if (previous == null) System.clearProperty("lattice.native.sha256");
            else System.setProperty("lattice.native.sha256", previous);
        }
    }

    @Test
    void extractionChecksPinnedDigestBeforeReturning(@TempDir Path tempDir) throws Exception {
        byte[] bytes = "native".getBytes(StandardCharsets.UTF_8);
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        String previous = System.getProperty("lattice.native.cacheDir");
        System.setProperty("lattice.native.cacheDir", tempDir.toString());
        try {
            Path extracted = LatticeNativeLoader.extractToCache("liblattice.so",
                    new ByteArrayInputStream(bytes), digest);
            org.junit.jupiter.api.Assertions.assertArrayEquals(bytes, Files.readAllBytes(extracted));
            assertThrows(IOException.class, () -> LatticeNativeLoader.extractToCache("liblattice.so",
                    new ByteArrayInputStream(bytes), "0".repeat(64)));
        } finally {
            if (previous == null) System.clearProperty("lattice.native.cacheDir");
            else System.setProperty("lattice.native.cacheDir", previous);
        }
    }

    @Test
    void downloadUrlsRequireHttpsAndTrustedRedirectHosts() throws Exception {
        assertThrows(IOException.class, () -> LatticeNativeLoader.requireHttpUri(
                "http://github.com/LatticeMC/Lattice/releases/download/native-latest/native.so", false));
        assertEquals("http", LatticeNativeLoader.requireHttpUri("http://mirror.example/native.so", true).getScheme());
        LatticeNativeLoader.requireAllowedRedirect(URI.create("https://release-assets.githubusercontent.com/native.so"), false);
        assertThrows(IOException.class, () -> LatticeNativeLoader.requireAllowedRedirect(
                URI.create("https://attacker.example/native.so"), false));
    }
}
