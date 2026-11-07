package com.latticemc.lattice.bootstrap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.latticemc.lattice.nativelib.LatticeNative;
import com.latticemc.lattice.nativelib.NativeChunkSerializer;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.net.URLClassLoader;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;
import java.util.zip.ZipException;
import org.junit.jupiter.api.Test;

class NativeZlibStreamsTestSuite {
    private static final String DEFLATE_PROPERTY = "lattice.nativeRegionZlibDeflate";

    @Test
    void startupSwitchDefaultsToNativeAndExplicitFalseOrUnavailableUsesJdk() throws Exception {
        for (String property : new String[] {null, "true", "false"}) {
            for (boolean loaded : new boolean[] {false, true}) {
                try (var nativeMock = mockStatic(LatticeNative.class);
                     var serializer = mockStatic(NativeChunkSerializer.class);
                     var factory = new IsolatedFactory(property)) {
                    nativeMock.when(LatticeNative::isLoaded).thenReturn(loaded);
                    boolean useNative = loaded && !"false".equals(property);
                    serializer.when(() -> NativeChunkSerializer.deflateZlib(any(), eq(0), eq(0)))
                            .thenReturn(new byte[] {42});
                    ByteArrayOutputStream sink = new ByteArrayOutputStream();
                    OutputStream stream = factory.open(sink);
                    assertEquals(!useNative, stream instanceof DeflaterOutputStream);
                    stream.close();
                    if (useNative) assertArrayEquals(new byte[] {42}, sink.toByteArray());
                    else assertArrayEquals(new byte[0], decode(sink.toByteArray()));
                }
            }
        }
    }

    @Test
    void closeUsesInternalBufferRangeWithoutCopyAndKeepsFlushAndIdempotence() throws Exception {
        try (var nativeMock = mockStatic(LatticeNative.class);
             var serializer = mockStatic(NativeChunkSerializer.class);
             var factory = new IsolatedFactory("true")) {
            nativeMock.when(LatticeNative::isLoaded).thenReturn(true);
            serializer.when(() -> NativeChunkSerializer.deflateZlib(any(), eq(0), eq(3)))
                    .thenAnswer(call -> {
                        byte[] source = call.getArgument(0);
                        assertEquals(8192, source.length, "close must not create an exact-sized payload copy");
                        assertArrayEquals(new byte[] {1, 2, 3}, Arrays.copyOf(source, 3));
                        return new byte[] {4, 5};
                    });
            OutputStream sink = mock(OutputStream.class);
            OutputStream stream = factory.open(sink);
            stream.write(1);
            stream.write(new byte[] {0, 2, 3, 0}, 1, 2);
            stream.flush();
            verifyNoInteractions(sink);
            serializer.verifyNoInteractions();
            stream.close();
            stream.close();
            var order = inOrder(sink);
            order.verify(sink).write(new byte[] {4, 5});
            order.verify(sink).flush();
            order.verify(sink).close();
            order.verifyNoMoreInteractions();
            serializer.verify(() -> NativeChunkSerializer.deflateZlib(any(), eq(0), eq(3)), times(1));
        }
    }

    @Test
    void compressionFailurePropagatesAndPreservesOriginalSinkAndRepeatedCloseBehavior() throws Exception {
        try (var nativeMock = mockStatic(LatticeNative.class);
             var serializer = mockStatic(NativeChunkSerializer.class);
             var factory = new IsolatedFactory("true")) {
            nativeMock.when(LatticeNative::isLoaded).thenReturn(true);
            RuntimeException failure = new IllegalStateException("compress failed");
            serializer.when(() -> NativeChunkSerializer.deflateZlib(any(), eq(0), eq(0))).thenThrow(failure);
            OutputStream sink = mock(OutputStream.class);
            OutputStream stream = factory.open(sink);
            assertSame(failure, assertThrows(IllegalStateException.class, stream::close));
            stream.close();
            verifyNoInteractions(sink);
            serializer.verify(() -> NativeChunkSerializer.deflateZlib(any(), eq(0), eq(0)), times(1));
        }
    }

    @Test
    void sinkWriteOrFlushFailuresStillCloseAndCloseFailureKeepsExistingPrecedence() throws Exception {
        for (boolean flushFailure : new boolean[] {false, true}) {
            for (boolean closeFailure : new boolean[] {false, true}) {
                try (var nativeMock = mockStatic(LatticeNative.class);
                     var serializer = mockStatic(NativeChunkSerializer.class);
                     var factory = new IsolatedFactory("true")) {
                    nativeMock.when(LatticeNative::isLoaded).thenReturn(true);
                    serializer.when(() -> NativeChunkSerializer.deflateZlib(any(), eq(0), eq(0)))
                            .thenReturn(new byte[] {1});
                    OutputStream sink = mock(OutputStream.class);
                    IOException outputError = new IOException("output failed"), closeError = new IOException("close failed");
                    if (flushFailure) doThrow(outputError).when(sink).flush();
                    else doThrow(outputError).when(sink).write(any(byte[].class));
                    if (closeFailure) doThrow(closeError).when(sink).close();
                    OutputStream stream = factory.open(sink);
                    assertSame(closeFailure ? closeError : outputError, assertThrows(IOException.class, stream::close));
                    stream.close();
                    verify(sink, times(1)).close();
                }
            }
        }
    }

    @Test
    void invalidWritesDoNotReachCompressor() throws Exception {
        try (var nativeMock = mockStatic(LatticeNative.class);
             var serializer = mockStatic(NativeChunkSerializer.class);
             var factory = new IsolatedFactory("true")) {
            nativeMock.when(LatticeNative::isLoaded).thenReturn(true);
            OutputStream stream = factory.open(new ByteArrayOutputStream());
            assertThrows(NullPointerException.class, () -> stream.write(null, 0, 0));
            assertThrows(IndexOutOfBoundsException.class, () -> stream.write(new byte[2], 1, 2));
            assertThrows(IndexOutOfBoundsException.class, () -> stream.write(new byte[2], -1, 1));
            serializer.verifyNoInteractions();
        }
    }

    @Test
    void nativeAndExplicitJavaRoundTripAcrossIndependentConcurrentWrites() throws Exception {
        LatticeNative.ensureLoaded();
        assertTrue(LatticeNative.isLoaded(), LatticeNative.failureReason());
        try (var pool = Executors.newFixedThreadPool(4);
             var nativeFactory = new IsolatedFactory("true");
             var javaFactory = new IsolatedFactory("false")) {
            var tasks = new java.util.ArrayList<Callable<Void>>();
            for (int worker = 0; worker < 4; ++worker) {
                final int seed = worker;
                tasks.add(() -> {
                    for (int size : new int[] {0, 1, 1024, 65536, 1048576}) {
                        byte[] payload = new byte[size];
                        new Random(seed * 31L + size).nextBytes(payload);
                        for (IsolatedFactory factory : new IsolatedFactory[] {nativeFactory, javaFactory}) {
                            ByteArrayOutputStream sink = new ByteArrayOutputStream();
                            OutputStream stream = factory.open(sink);
                            int split = size / 3;
                            stream.write(payload, 0, split);
                            stream.flush();
                            stream.write(payload, split, size - split);
                            stream.close();
                            stream.close();
                            assertArrayEquals(payload, decode(sink.toByteArray()));
                        }
                    }
                    return null;
                });
            }
            for (var future : pool.invokeAll(tasks)) future.get();
        }
    }

    @Test
    void javaReaderRejectsTruncatedAndBadChecksumOutputFromBothWriters() throws Exception {
        LatticeNative.ensureLoaded();
        assertTrue(LatticeNative.isLoaded(), LatticeNative.failureReason());
        byte[] payload = new byte[65536];
        new Random(42).nextBytes(payload);
        for (String property : new String[] {"true", "false"}) {
            try (var factory = new IsolatedFactory(property)) {
                ByteArrayOutputStream sink = new ByteArrayOutputStream();
                try (OutputStream stream = factory.open(sink)) {
                    assertEquals("false".equals(property), stream instanceof DeflaterOutputStream);
                    stream.write(payload);
                }
                byte[] compressed = sink.toByteArray();
                assertArrayEquals(payload, decode(compressed));
                byte[] truncated = Arrays.copyOf(compressed, compressed.length - 1);
                assertThrows(EOFException.class, () -> decode(truncated));
                byte[] corrupt = compressed.clone();
                corrupt[corrupt.length - 1] ^= 1;
                assertThrows(ZipException.class, () -> decode(corrupt));
            }
        }
    }

    private static byte[] decode(byte[] bytes) throws IOException {
        try (var in = new InflaterInputStream(new ByteArrayInputStream(bytes))) {
            return in.readAllBytes();
        }
    }

    // Reload only the startup switch owner, keeping mocks and JNI in the parent loader.
    private static final class IsolatedFactory implements AutoCloseable {
        private final URLClassLoader loader;
        private final java.lang.reflect.Method deflater;

        IsolatedFactory(String value) throws Exception {
            String original = System.getProperty(DEFLATE_PROPERTY);
            loader = new URLClassLoader(new java.net.URL[] {
                    NativeZlibStreams.class.getProtectionDomain().getCodeSource().getLocation()
            }, NativeZlibStreams.class.getClassLoader()) {
                @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                    if (!name.startsWith(NativeZlibStreams.class.getName())) return super.loadClass(name, resolve);
                    synchronized (getClassLoadingLock(name)) {
                        Class<?> type = findLoadedClass(name);
                        if (type == null) type = findClass(name);
                        if (resolve) resolveClass(type);
                        return type;
                    }
                }
            };
            try {
                if (value == null) System.clearProperty(DEFLATE_PROPERTY);
                else System.setProperty(DEFLATE_PROPERTY, value);
                Class<?> type = Class.forName(NativeZlibStreams.class.getName(), true, loader);
                deflater = type.getMethod("deflater", OutputStream.class);
            } finally {
                if (original == null) System.clearProperty(DEFLATE_PROPERTY);
                else System.setProperty(DEFLATE_PROPERTY, original);
            }
        }

        OutputStream open(OutputStream sink) throws Exception {
            try {
                return (OutputStream) deflater.invoke(null, sink);
            } catch (InvocationTargetException error) {
                if (error.getCause() instanceof Exception cause) throw cause;
                throw error;
            }
        }

        @Override public void close() throws IOException { loader.close(); }
    }
}
