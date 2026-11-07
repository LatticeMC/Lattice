package net.minecraft.world.level.chunk.storage;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.latticemc.lattice.bootstrap.NativeZlibStreams;
import com.latticemc.lattice.nativelib.LatticeNative;
import java.io.ByteArrayInputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Random;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RegionZlibWriteParityTestSuite {
    @Test
    void inlineExternalOverwriteAndReopenRemainJavaReadable(@TempDir Path directory) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        LatticeNative.ensureLoaded();
        assertTrue(LatticeNative.isLoaded(), LatticeNative.failureReason());
        RegionStorageInfo info = new RegionStorageInfo("audit", Level.OVERWORLD, "chunk");
        ChunkPos small = new ChunkPos(0, 0), large = new ChunkPos(1, 0);
        for (boolean nativePath : new boolean[] {false, true}) {
            Path folder = Files.createDirectory(directory.resolve(nativePath ? "native" : "jdk"));
            Path path = folder.resolve("r.0.0.mca");
            RegionFileVersion version = version(nativePath);
            byte[] initial = random(65536), external = random(1048576 + 8192);
            byte[] replacement = random(1024);
            try (RegionFile region = new RegionFile(info, path, folder, version, false)) {
                write(region, small, initial);
                write(region, large, external);
                assertTrue(Files.exists(folder.resolve("c.1.0.mcc")));
                assertArrayEquals(initial, rawJavaRead(path, folder, small));
                assertArrayEquals(external, rawJavaRead(path, folder, large));
                write(region, large, replacement);
                assertFalse(Files.exists(folder.resolve("c.1.0.mcc")));
                assertArrayEquals(replacement, rawJavaRead(path, folder, large));
                region.clear(small);
                assertFalse(region.hasChunk(small));
                write(region, small, new byte[0]);
                region.flush();
            }
            assertEquals(0, Files.size(path) % 4096);
            try (RegionFile region = new RegionFile(info, path, folder, RegionFileVersion.VERSION_DEFLATE, false)) {
                try (var in = region.getChunkDataInputStream(small)) {
                    assertNotNull(in);
                    assertArrayEquals(new byte[0], in.readAllBytes());
                }
                try (var in = region.getChunkDataInputStream(large)) {
                    assertNotNull(in);
                    assertArrayEquals(replacement, in.readAllBytes());
                }
            }
        }
    }

    private static void write(RegionFile region, ChunkPos pos, byte[] bytes) throws Exception {
        var out = region.getChunkDataOutputStream(pos);
        out.write(bytes);
        out.close();
        out.close();
    }

    private static byte[] random(int size) {
        byte[] result = new byte[size];
        new Random(size).nextBytes(result);
        return result;
    }

    private static RegionFileVersion version(boolean nativePath) throws Exception {
        if (nativePath) {
            try (var probe = NativeZlibStreams.deflater(new java.io.ByteArrayOutputStream())) {
                assertFalse(probe instanceof DeflaterOutputStream, "native arm must own a native deflater");
            }
        }
        // Real RegionFileVersion.wrap overrides its stored wrapper when native
        // is loaded. Isolate only compression routing so the JDK arm stays JDK.
        RegionFileVersion version = mock(RegionFileVersion.class);
        when(version.getId()).thenReturn(2);
        when(version.wrap(any(OutputStream.class))).thenAnswer(call -> {
            OutputStream sink = call.getArgument(0);
            return nativePath ? NativeZlibStreams.deflater(sink) : new DeflaterOutputStream(sink);
        });
        return version;
    }

    // Independent decoder follows the vanilla header/stub format, not Lattice's read wrapper.
    private static byte[] rawJavaRead(Path path, Path directory, ChunkPos pos) throws Exception {
        byte[] region = Files.readAllBytes(path);
        int index = pos.getRegionLocalX() + pos.getRegionLocalZ() * 32;
        int offset = ByteBuffer.wrap(region).getInt(index * 4);
        int start = (offset >>> 8) * 4096;
        int length = ByteBuffer.wrap(region).getInt(start);
        int type = region[start + 4] & 0xff;
        assertEquals(2, type & 0x7f);
        byte[] compressed;
        if ((type & 0x80) != 0) {
            assertEquals(1, length);
            assertEquals(1, offset & 0xff);
            compressed = Files.readAllBytes(directory.resolve("c." + pos.x + "." + pos.z + ".mcc"));
        } else {
            assertTrue(length > 1);
            assertTrue(length + 4 <= (offset & 0xff) * 4096);
            compressed = Arrays.copyOfRange(region, start + 5, start + 4 + length);
        }
        try (var in = new InflaterInputStream(new ByteArrayInputStream(compressed))) {
            return in.readAllBytes();
        }
    }
}
