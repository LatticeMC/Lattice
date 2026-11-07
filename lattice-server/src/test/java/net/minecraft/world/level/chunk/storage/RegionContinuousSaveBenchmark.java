package net.minecraft.world.level.chunk.storage;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import com.google.gson.GsonBuilder;
import com.latticemc.lattice.bootstrap.NativeZlibStreams;
import com.latticemc.lattice.nativelib.LatticeNative;
import com.latticemc.lattice.nativelib.NativeChunkSerializer;
import com.sun.management.ThreadMXBean;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

/** Synthetic continuous RegionFile writes; each JVM measures one compression arm. */
public final class RegionContinuousSaveBenchmark {
    private static final long SEED = 20261107L;
    private static final PrintStream OUTPUT = System.out;
    private static final ThreadMXBean ALLOCATION_BEAN = allocationBean();

    private RegionContinuousSaveBenchmark() {}

    public static void main(String[] args) throws Exception {
        String mode = System.getProperty("regionSave.mode", "current-native");
        Factory factory = switch (mode) {
            case "legacy-native" -> LegacyNativeStream::new;
            case "current-native" -> NativeZlibStreams::deflater;
            case "jdk" -> DeflaterOutputStream::new;
            default -> throw new IllegalArgumentException("Unknown regionSave.mode: " + mode);
        };
        int threads = positive("threads", 1);
        int warmup = Integer.getInteger("regionSave.warmup", 12);
        int samples = positive("samples", 21);
        int rounds = positive("rounds", 16);
        if (warmup < 0 || (threads != 1 && threads != 4)) {
            throw new IllegalArgumentException("Require warmup >= 0 and threads = 1 or 4");
        }
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        LatticeNative.ensureLoaded();
        if (!LatticeNative.isLoaded()) throw new IllegalStateException(LatticeNative.failureReason());
        try (OutputStream probe = NativeZlibStreams.deflater(OutputStream.nullOutputStream())) {
            if (probe instanceof DeflaterOutputStream) throw new IllegalStateException("Native arm routed to JDK");
        }
        byte[][] payloads = payloads();
        byte[][] originals = Arrays.stream(payloads).map(byte[]::clone).toArray(byte[][]::new);
        verifyCompression(payloads, factory);
        Path root = Files.createTempDirectory("lattice-region-save-audit-");
        List<Writer> writers = new ArrayList<>();
        Measurement[] measurements = new Measurement[samples];
        Map<String, Long> poolPeaks = new LinkedHashMap<>();
        Map<String, Long> diskBytes = new LinkedHashMap<>();
        List<MemoryPoolMXBean> pools = ManagementFactory.getMemoryPoolMXBeans().stream()
            .filter(pool -> pool.getType() == MemoryType.HEAP).toList();
        OUTPUT.println(String.format(Locale.ROOT,
            "region-save mode=%s pid=%d java=%s cpu=%s seed=%d threads=%d warmup=%d samples=%d rounds=%d chunksPerRound=%d level=%d root=%s%n",
            mode, ProcessHandle.current().pid(), System.getProperty("java.version"), LatticeNative.cpuSummary(),
            SEED, threads, warmup, samples, rounds, payloads.length, NativeChunkSerializer.DEFAULT_LEVEL, root));
        // Resource order ensures workers finish, even on failure, before their region owners close.
        try (Regions owners = new Regions(writers); ExecutorService executor = Executors.newFixedThreadPool(threads)) {
            for (int index = 0; index < threads; index++) {
                Path folder = Files.createDirectory(root.resolve("writer-" + index));
                Path file = folder.resolve("r.0.0.mca");
                RegionFile region = new RegionFile(new RegionStorageInfo("audit", Level.OVERWORLD, "chunk"),
                    file, folder, version(factory), false);
                writers.add(new Writer(region, file, folder, payloads));
            }
            List<Callable<Long>> calls = writers.stream()
                .<Callable<Long>>map(writer -> () -> writer.write(rounds)).toList();
            for (int index = -warmup; index < samples; index++) {
                if (index == 0) pools.forEach(MemoryPoolMXBean::resetPeakUsage);
                Gc beforeGc = gc();
                long start = System.nanoTime();
                var futures = executor.invokeAll(calls);
                long allocated = 0;
                for (var future : futures) allocated = Math.addExact(allocated, future.get());
                long elapsed = System.nanoTime() - start;
                Gc afterGc = gc();
                long heapUsed = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
                if (elapsed <= 0 || allocated < 0 || afterGc.count() < beforeGc.count()
                    || afterGc.millis() < beforeGc.millis()) throw new AssertionError("Invalid measurement");
                if (index >= 0) {
                    measurements[index] = new Measurement(elapsed, allocated,
                        afterGc.count() - beforeGc.count(), afterGc.millis() - beforeGc.millis(), heapUsed);
                }
                for (Writer writer : writers) writer.verify();
                for (int payload = 0; payload < payloads.length; payload++) {
                    if (!Arrays.equals(payloads[payload], originals[payload])) {
                        throw new AssertionError("Source payload changed: " + payload);
                    }
                }
            }
            for (MemoryPoolMXBean pool : pools) {
                poolPeaks.put(pool.getName(), pool.getPeakUsage().getUsed());
            }
        }
        for (Writer writer : writers) {
            try (RegionFile reopened = new RegionFile(new RegionStorageInfo("audit", Level.OVERWORLD, "chunk"),
                writer.file(), writer.folder(), RegionFileVersion.VERSION_DEFLATE, false)) {
                for (int index = 0; index < payloads.length; index++) {
                    try (var in = reopened.getChunkDataInputStream(new ChunkPos(index, 0))) {
                        if (in == null || !Arrays.equals(payloads[index], in.readAllBytes())) {
                            throw new AssertionError("Reopen parity: " + index);
                        }
                    }
                }
            }
        }
        long chunks = Math.multiplyExact(Math.multiplyExact((long) threads, rounds), payloads.length);
        double[] times = Arrays.stream(measurements).mapToDouble(m -> (double) m.elapsed() / chunks).sorted().toArray();
        double[] allocations = Arrays.stream(measurements).mapToDouble(m -> (double) m.allocated() / chunks).sorted().toArray();
        for (Writer writer : writers) {
            try (var paths = Files.list(writer.folder())) {
                long bytes = 0;
                for (Path file : paths.toList()) bytes = Math.addExact(bytes, Files.size(file));
                diskBytes.put(writer.folder().toString(), bytes);
            }
        }
        Report report = new Report(mode, System.getProperty("java.version"), LatticeNative.cpuSummary(),
            ProcessHandle.current().pid(), SEED,
            threads, warmup, samples, rounds, chunks, NativeChunkSerializer.DEFAULT_LEVEL, root.toString(),
            ManagementFactory.getRuntimeMXBean().getInputArguments(), Runtime.getRuntime().maxMemory(),
            measurements, poolPeaks, diskBytes, quantile(times, 0.5), quantile(times, 0.95),
            quantile(allocations, 0.5));
        Path reportFile = root.resolve("run.json");
        Files.writeString(reportFile, new GsonBuilder().setPrettyPrinting().create().toJson(report),
            StandardOpenOption.CREATE_NEW);
        OUTPUT.println(String.format(Locale.ROOT, "summary,%s,%.3f,%.3f,%.3f",
            mode, report.p50NanosPerChunk(), report.p95NanosPerChunk(), report.p50JavaBytesPerChunk()));
        OUTPUT.println("result=success report=" + reportFile);
    }

    private static int positive(String name, int fallback) {
        int value = Integer.getInteger("regionSave." + name, fallback);
        if (value < 1) throw new IllegalArgumentException("Require regionSave." + name + " >= 1");
        return value;
    }

    private static byte[][] payloads() {
        int[] sizes = {1024, 65536, 1048576};
        byte[][] values = new byte[6][];
        for (int index = 0; index < values.length; index++) {
            byte[] payload = new byte[sizes[index % sizes.length]];
            new Random(SEED + index).nextBytes(payload);
            if (index < 3) {
                for (int i = 0; i < payload.length; i++) {
                    if (i % 97 < 80) payload[i] = (byte) (i % 7);
                }
            }
            values[index] = payload;
        }
        return values;
    }

    private static void verifyCompression(byte[][] payloads, Factory selected) throws IOException {
        for (byte[] payload : payloads) {
            byte[] legacy = compress(payload, LegacyNativeStream::new);
            byte[] current = compress(payload, NativeZlibStreams::deflater);
            if (!Arrays.equals(legacy, current)) throw new AssertionError("Native compressed bytes changed");
            byte[] compressed = compress(payload, selected);
            try (var in = new InflaterInputStream(new ByteArrayInputStream(compressed))) {
                if (!Arrays.equals(payload, in.readAllBytes())) throw new AssertionError("Compression parity");
            }
        }
    }

    private static byte[] compress(byte[] payload, Factory factory) throws IOException {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        try (OutputStream out = factory.open(sink)) {
            out.write(payload);
        }
        return sink.toByteArray();
    }

    private static RegionFileVersion version(Factory factory) throws IOException {
        // Only routing is mocked. Stub-only avoids retaining every measured invocation.
        RegionFileVersion version = mock(RegionFileVersion.class, withSettings().stubOnly());
        when(version.getId()).thenReturn(2);
        when(version.wrap(any(OutputStream.class))).thenAnswer(call -> factory.open(call.getArgument(0)));
        return version;
    }

    private static ThreadMXBean allocationBean() {
        if (!(ManagementFactory.getThreadMXBean() instanceof ThreadMXBean bean)
            || !bean.isThreadAllocatedMemorySupported()) throw new IllegalStateException("Allocation counter unavailable");
        if (!bean.isThreadAllocatedMemoryEnabled()) bean.setThreadAllocatedMemoryEnabled(true);
        return bean;
    }

    private static Gc gc() {
        long count = 0, millis = 0;
        for (var collector : ManagementFactory.getGarbageCollectorMXBeans()) {
            long collections = collector.getCollectionCount(), time = collector.getCollectionTime();
            if (collections < 0 || time < 0) throw new IllegalStateException("GC counter unavailable");
            count += collections;
            millis += time;
        }
        return new Gc(count, millis);
    }

    private static double quantile(double[] values, double quantile) {
        return values[(int) Math.ceil(values.length * quantile) - 1];
    }

    private interface Factory {
        OutputStream open(OutputStream sink) throws IOException;
    }

    private record Gc(long count, long millis) {}
    private record Measurement(long elapsed, long allocated, long gcCount, long gcMillis, long heapUsed) {}
    private record Report(String mode, String javaVersion, String cpu, long pid, long seed, int threads, int warmup,
                          int samples, int rounds, long chunksPerSample, int level, String root,
                          List<String> jvmArgs, long maxHeapBytes, Measurement[] measurements,
                          Map<String, Long> poolPeaks, Map<String, Long> diskBytes,
                          double p50NanosPerChunk, double p95NanosPerChunk, double p50JavaBytesPerChunk) {}

    private record Regions(List<Writer> writers) implements AutoCloseable {
        @Override public void close() throws IOException {
            IOException failure = null;
            for (Writer writer : writers) {
                try {
                    writer.region().close();
                } catch (IOException error) {
                    if (failure == null) failure = error;
                    else failure.addSuppressed(error);
                }
            }
            if (failure != null) throw failure;
        }
    }

    private record Writer(RegionFile region, Path file, Path folder, byte[][] payloads) {
        long write(int rounds) throws IOException {
            long before = ALLOCATION_BEAN.getCurrentThreadAllocatedBytes();
            for (int round = 0; round < rounds; round++) {
                for (int index = 0; index < payloads.length; index++) {
                    try (var out = region.getChunkDataOutputStream(new ChunkPos(index, 0))) {
                        out.write(payloads[index]);
                    }
                }
            }
            region.flush();
            long after = ALLOCATION_BEAN.getCurrentThreadAllocatedBytes();
            if (before < 0 || after < before) throw new IllegalStateException("Invalid thread allocation counter");
            return after - before;
        }

        void verify() throws IOException {
            byte[] bytes = Files.readAllBytes(file);
            ByteBuffer header = ByteBuffer.wrap(bytes);
            for (int index = 0; index < payloads.length; index++) {
                int offset = header.getInt(index * 4);
                int start = (offset >>> 8) * 4096;
                int sectors = offset & 0xff;
                if (start < 8192 || sectors == 0 || (long) start + 5 > bytes.length) {
                    throw new AssertionError("Invalid region offset");
                }
                int length = header.getInt(start), type = bytes[start + 4] & 0xff;
                if ((type & 0x7f) != 2) throw new AssertionError("Unexpected compression type");
                byte[] compressed;
                if ((type & 0x80) != 0) {
                    if (length != 1 || sectors != 1) throw new AssertionError("Invalid external stub");
                    compressed = Files.readAllBytes(folder.resolve("c." + index + ".0.mcc"));
                } else {
                    if (length <= 1 || (long) length + 4 > sectors * 4096L
                        || (long) start + 4 + length > bytes.length) throw new AssertionError("Invalid inline length");
                    if (Files.exists(folder.resolve("c." + index + ".0.mcc"))) {
                        throw new AssertionError("Unexpected stale external file");
                    }
                    compressed = Arrays.copyOfRange(bytes, start + 5, start + 4 + length);
                }
                try (var in = new InflaterInputStream(new ByteArrayInputStream(compressed))) {
                    if (!Arrays.equals(payloads[index], in.readAllBytes())) throw new AssertionError("Region parity");
                }
            }
        }
    }

    // Frozen legacy buffering contract; compressor and DLL are shared with the current arm.
    private static final class LegacyNativeStream extends FilterOutputStream {
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream(8192);
        private boolean closed;

        LegacyNativeStream(OutputStream sink) { super(sink); }
        @Override public void write(int value) { buffer.write(value); }
        @Override public void write(byte[] bytes, int offset, int length) { buffer.write(bytes, offset, length); }
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
