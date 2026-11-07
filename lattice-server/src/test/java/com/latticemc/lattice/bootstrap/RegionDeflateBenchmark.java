package com.latticemc.lattice.bootstrap;

import com.latticemc.lattice.nativelib.LatticeNative;
import com.latticemc.lattice.nativelib.NativeChunkSerializer;
import com.sun.management.ThreadMXBean;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.Locale;
import java.util.Random;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

public final class RegionDeflateBenchmark {
    private static volatile long blackhole;

    private RegionDeflateBenchmark() {}

    public static void main(String[] args) throws Exception {
        LatticeNative.ensureLoaded();
        if (!LatticeNative.isLoaded()) throw new IllegalStateException(LatticeNative.failureReason());
        if (!Boolean.parseBoolean(System.getProperty("lattice.nativeRegionZlibDeflate", "true"))) {
            throw new IllegalStateException("Benchmark requires native deflate enabled");
        }
        try (OutputStream probe = NativeZlibStreams.deflater(new ByteArrayOutputStream())) {
            if (probe instanceof DeflaterOutputStream) throw new IllegalStateException("Native arm routed to JDK");
        }
        ThreadMXBean bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        if (!bean.isThreadAllocatedMemorySupported()) throw new IllegalStateException("Allocation counter unavailable");
        bean.setThreadAllocatedMemoryEnabled(true);
        int warmup = Integer.getInteger("regionBenchmark.warmup", 5);
        int samples = Integer.getInteger("regionBenchmark.samples", 15);
        int bytesPerSample = Integer.getInteger("regionBenchmark.bytesPerSample", 131072);
        if (warmup < 0 || samples < 1 || bytesPerSample < 1) {
            throw new IllegalArgumentException("Require warmup >= 0, samples >= 1, bytesPerSample >= 1");
        }
        Factory[] factories = {LegacyNativeStream::new, NativeZlibStreams::deflater, DeflaterOutputStream::new};
        String[] names = {"legacy-native", "current-native", "jdk"};
        System.out.printf("java=%s cpu=%s warmup=%d samples=%d bytesPerSample=%d level=%d%n",
                System.getProperty("java.version"), LatticeNative.cpuSummary(), warmup, samples, bytesPerSample,
                NativeChunkSerializer.DEFAULT_LEVEL);
        System.out.println("pattern,bytes,path,p50-ns,p95-ns,java-bytes-per-chunk,compressed-bytes");
        for (boolean random : new boolean[] {false, true}) {
            for (int size : new int[] {1024, 65536, 1048576}) {
                byte[] payload = new byte[size];
                new Random(0x20261107L).nextBytes(payload);
                if (!random) for (int i = 0; i < size; ++i) payload[i] = (byte) (i % 97 < 80 ? i % 7 : payload[i]);
                int rounds = Math.max(1, bytesPerSample / size);
                int[] compressed = new int[factories.length];
                byte[] legacyCompressed = null;
                for (int i = 0; i < factories.length; ++i) {
                    ByteArrayOutputStream sink = new ByteArrayOutputStream();
                    try (OutputStream stream = factories[i].open(sink)) { stream.write(payload); }
                    byte[] zipped = sink.toByteArray();
                    try (InflaterInputStream in = new InflaterInputStream(new ByteArrayInputStream(zipped))) {
                        if (!Arrays.equals(payload, in.readAllBytes())) throw new AssertionError("round-trip " + names[i]);
                    }
                    if (i == 0) legacyCompressed = zipped;
                    else if (i == 1 && !Arrays.equals(legacyCompressed, zipped)) {
                        throw new AssertionError("native output bytes changed");
                    }
                    compressed[i] = zipped.length;
                }
                double[][] nanos = new double[3][samples], bytes = new double[3][samples];
                for (int iteration = -warmup; iteration < samples; ++iteration) {
                    for (int j = 0; j < factories.length; ++j) {
                        int index = (iteration & 1) == 0 ? j : factories.length - 1 - j;
                        CountingSink sink = new CountingSink();
                        long beforeBytes = bean.getCurrentThreadAllocatedBytes();
                        long beforeTime = System.nanoTime();
                        for (int k = 0; k < rounds; ++k) {
                            try (OutputStream stream = factories[index].open(sink)) { stream.write(payload); }
                        }
                        long elapsed = System.nanoTime() - beforeTime;
                        long allocated = bean.getCurrentThreadAllocatedBytes() - beforeBytes;
                        blackhole += sink.bytes;
                        if (iteration >= 0) {
                            nanos[index][iteration] = (double) elapsed / rounds;
                            bytes[index][iteration] = (double) allocated / rounds;
                        }
                    }
                }
                for (int i = 0; i < factories.length; ++i) {
                    for (int sample = 0; sample < samples; ++sample) {
                        System.out.printf(Locale.ROOT, "sample,%s,%d,%s,%d,%.3f,%.3f%n",
                                random ? "random" : "structured", size, names[i], sample,
                                nanos[i][sample], bytes[i][sample]);
                    }
                    Arrays.sort(nanos[i]);
                    Arrays.sort(bytes[i]);
                    System.out.printf(Locale.ROOT, "%s,%d,%s,%.3f,%.3f,%.3f,%d%n",
                            random ? "random" : "structured", size, names[i], percentile(nanos[i], 0.5),
                            percentile(nanos[i], 0.95), percentile(bytes[i], 0.5), compressed[i]);
                }
            }
        }
        System.out.println("result=success blackhole=" + blackhole);
    }

    private static double percentile(double[] values, double percentile) {
        return values[Math.min(values.length - 1, (int) Math.ceil(values.length * percentile) - 1)];
    }

    private interface Factory {
        OutputStream open(OutputStream sink);
    }

    private static final class CountingSink extends OutputStream {
        long bytes;
        @Override public void write(int b) { ++bytes; }
        @Override public void write(byte[] data, int offset, int length) { bytes += length; }
    }

    // Frozen pre-optimization write path, retained for same-JVM A/B.
    private static final class LegacyNativeStream extends FilterOutputStream {
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream(8192);
        private boolean closed;
        LegacyNativeStream(OutputStream sink) { super(sink); }
        @Override public void write(int b) { buffer.write(b); }
        @Override public void write(byte[] b, int off, int len) { buffer.write(b, off, len); }
        @Override public void flush() {}
        @Override public void close() throws IOException {
            if (closed) return;
            closed = true;
            byte[] payload = buffer.toByteArray();
            byte[] compressed = NativeChunkSerializer.deflateZlib(payload, 0, payload.length);
            try {
                out.write(compressed);
                out.flush();
            } finally {
                out.close();
            }
        }
    }
}
