package com.latticemc.lattice.nativelib;

import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.Locale;

public final class PaletteBulkBenchmark {
    private static volatile long sink;

    public static void main(String[] args) {
        LatticeNative.ensureLoaded();
        if (!LatticeNative.isLoaded()) throw new IllegalStateException(LatticeNative.failureReason());
        if (LatticeNative.VERIFY) throw new IllegalStateException("Disable VERIFY for timing");
        int threshold = Integer.getInteger("lattice.nativePaletteOpsMinCount", 64);
        int warmup = Integer.getInteger("paletteBenchmark.warmup", 32);
        int samples = Integer.getInteger("paletteBenchmark.samples", 21);
        int rounds = Integer.getInteger("paletteBenchmark.rounds", 16384);
        if (warmup < 0 || samples < 1 || rounds < 1) throw new IllegalArgumentException("Invalid sample counts");
        ThreadMXBean bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        if (!bean.isThreadAllocatedMemorySupported()) throw new IllegalStateException("Allocation counter unavailable");
        bean.setThreadAllocatedMemoryEnabled(true);
        System.out.printf("java=%s cpu=%s threshold=%d warmup=%d samples=%d rounds=%d%n",
                System.getProperty("java.version"), LatticeNative.cpuSummary(), threshold, warmup, samples, rounds);
        System.out.println("kind,threshold,bits,count,path,sample,ns-per-unpack,java-bytes-per-unpack");
        for (int bits : new int[] {1, 2, 4, 5, 8, 16}) {
            for (int count : new int[] {0, 1, 32, 64, 128, 256, 4096}) {
                var fixture = new PaletteBulkFixture(bits, count);
                int[] output = PaletteBulkFixture.output(count + 3);
                boolean routed = NativePaletteOps.tryBulkGet(fixture.storage.getRaw(), bits, 0, output, 0, count);
                if (routed != (count >= threshold)) throw new AssertionError("Unexpected native dispatch");
                Arrays.fill(output, PaletteBulkFixture.SENTINEL);
                fixture.storage.unpack(output);
                fixture.assertWindow(output, 0, 0, count);
                Arrays.fill(output, PaletteBulkFixture.SENTINEL);
                fixture.javaUnpack(output);
                fixture.assertWindow(output, 0, 0, count);
                // These are source-derived JNI window bytes, not measured native allocations.
                System.out.printf("route,threshold=%d,bits=%d,count=%d,native=%s,input-copy-bytes=%d,output-copy-bytes=%d%n",
                        threshold, bits, count, routed, routed ? fixture.storage.getRaw().length * 8 : 0,
                        routed ? count * 4 : 0);
                double[][] nanos = new double[2][samples];
                double[][] bytes = new double[2][samples];
                for (int sample = -warmup; sample < samples; sample++) {
                    for (int order = 0; order < 2; order++) {
                        int arm = (sample + warmup + order) & 1;
                        long allocationStart = bean.getThreadAllocatedBytes(Thread.currentThread().threadId());
                        long start = System.nanoTime();
                        long checksum = 0;
                        for (int round = 0; round < rounds; round++) {
                            if (arm == 0) fixture.storage.unpack(output);
                            else fixture.javaUnpack(output);
                            checksum += count == 0 ? output[0] : output[round % count];
                        }
                        long elapsed = System.nanoTime() - start;
                        long allocated = bean.getThreadAllocatedBytes(Thread.currentThread().threadId()) - allocationStart;
                        sink = checksum;
                        fixture.assertWindow(output, 0, 0, count);
                        if (sample >= 0) {
                            nanos[arm][sample] = (double) elapsed / rounds;
                            bytes[arm][sample] = (double) allocated / rounds;
                            System.out.printf(Locale.ROOT, "sample,%d,%d,%d,%s,%d,%.3f,%.3f%n",
                                    threshold, bits, count, arm == 0 ? "current-unpack" : "frozen-java",
                                    sample, nanos[arm][sample], bytes[arm][sample]);
                        }
                    }
                }
                for (int arm = 0; arm < 2; arm++) {
                    Arrays.sort(nanos[arm]);
                    Arrays.sort(bytes[arm]);
                    System.out.printf(Locale.ROOT, "summary,%d,%d,%d,%s,p50=%.3f,p95=%.3f,bytes=%.3f%n",
                            threshold, bits, count, arm == 0 ? "current-unpack" : "frozen-java",
                            nanos[arm][samples / 2],
                            nanos[arm][Math.min(samples - 1, (int) Math.ceil(samples * .95) - 1)],
                            bytes[arm][samples / 2]);
                }
            }
        }
        System.out.println("palette-audit-complete scenarios=42 parity=passed");
    }
}
