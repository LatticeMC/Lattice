package com.latticemc.lattice.bootstrap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NativeLibraryCacheTestSuite {
    private static final byte[] CONTENT = {1, 2, 3, 4};

    @Test
    void extractionIsPrivateAndStreamsContent(@TempDir Path temporary) throws Exception {
        Path extracted = NativeLibraryCache.extract(temporary, "native.bin",
                new ByteArrayInputStream(CONTENT));
        assertArrayEquals(CONTENT, Files.readAllBytes(extracted));
        Path second = NativeLibraryCache.extract(temporary, "native.bin",
                new ByteArrayInputStream(CONTENT));
        assertNotEquals(extracted.getParent(), second.getParent());
        if (temporary.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            assertEquals(PosixFilePermissions.fromString("rwx------"),
                    Files.getPosixFilePermissions(extracted.getParent()));
        }
    }

    @Test
    void rejectsPathComponents(@TempDir Path temporary) {
        assertThrows(IOException.class, () -> NativeLibraryCache.extract(temporary, "../escape",
                new ByteArrayInputStream(CONTENT)));
    }

    @Test
    void removesPartialExtractionAfterReadFailure(@TempDir Path temporary) throws Exception {
        InputStream failing = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("test read failure");
            }
        };
        assertThrows(IOException.class, () -> NativeLibraryCache.extract(temporary, "native.bin", failing));
        try (var children = Files.list(temporary)) {
            assertEquals(0, children.count());
        }
    }

    @Test
    void rejectsWritablePosixAncestor(@TempDir Path temporary) throws Exception {
        assumeTrue(temporary.getFileSystem().supportedFileAttributeViews().contains("posix"));
        Path unsafe = Files.createDirectory(temporary.resolve("unsafe"));
        Files.setPosixFilePermissions(unsafe, PosixFilePermissions.fromString("rwxrwxrwx"));
        assertThrows(IOException.class, () -> NativeLibraryCache.extract(unsafe, "native.bin",
                new ByteArrayInputStream(CONTENT)));
    }

}
