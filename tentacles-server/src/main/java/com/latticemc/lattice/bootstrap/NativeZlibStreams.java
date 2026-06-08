package com.latticemc.lattice.bootstrap;

import com.latticemc.lattice.nativelib.NativeChunkSerializer;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.DataFormatException;
import java.util.zip.ZipException;

public final class NativeZlibStreams {

    private NativeZlibStreams() {}

    static IOException mapNativeInflateFailure(RuntimeException error) {
        Throwable cause = error.getCause();
        if (cause instanceof DataFormatException dataFormat) {
            ZipException zip = new ZipException(dataFormat.getMessage());
            zip.initCause(dataFormat);
            return zip;
        }
        if (cause instanceof IOException io) {
            return io;
        }
        return new IOException("native inflate failed", error);
    }

    public static InputStream inflater(InputStream source) throws IOException {
        if (!com.latticemc.lattice.nativelib.LatticeNative.isLoaded()) {
            return new java.util.zip.InflaterInputStream(source);
        }
        return new LazyNativeInflaterStream(source);
    }

    public static OutputStream deflater(OutputStream sink) {
        if (!com.latticemc.lattice.nativelib.LatticeNative.isLoaded()) {
            return new java.util.zip.DeflaterOutputStream(sink);
        }
        return new BufferingNativeDeflaterStream(sink);
    }

    private static final class LazyNativeInflaterStream extends InputStream {
        private final InputStream source;
        private ByteArrayInputStream decompressed;
        private boolean closed = false;

        LazyNativeInflaterStream(InputStream source) {
            this.source = source;
        }

        private void ensureDecompressed() throws IOException {
            if (decompressed != null || closed) return;
            final byte[] compressed = source.readAllBytes();
            final byte[] inflated;
            try {
                inflated = NativeChunkSerializer.inflateZlib(compressed, 0, compressed.length);
            } catch (RuntimeException e) {
                throw mapNativeInflateFailure(e);
            }
            decompressed = new ByteArrayInputStream(inflated);
        }

        @Override
        public int read() throws IOException {
            ensureDecompressed();
            return decompressed.read();
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            ensureDecompressed();
            return decompressed.read(b, off, len);
        }

        @Override
        public int available() throws IOException {
            if (decompressed == null) return 0;
            return decompressed.available();
        }

        @Override
        public void close() throws IOException {
            if (closed) return;
            closed = true;
            try {
                source.close();
            } finally {
                if (decompressed != null) decompressed.close();
            }
        }
    }

    private static final class BufferingNativeDeflaterStream extends FilterOutputStream {
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream(8 * 1024);
        private boolean closed = false;

        BufferingNativeDeflaterStream(OutputStream sink) {
            super(sink);
        }

        @Override
        public void write(int b) {
            buffer.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) {
            buffer.write(b, off, len);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() throws IOException {
            if (closed) return;
            closed = true;
            final byte[] payload = buffer.toByteArray();
            final byte[] compressed = NativeChunkSerializer.deflateZlib(payload, 0, payload.length);
            try {
                out.write(compressed);
                out.flush();
            } finally {
                out.close();
            }
        }
    }
}
