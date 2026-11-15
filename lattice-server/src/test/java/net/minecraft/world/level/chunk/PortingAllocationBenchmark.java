package net.minecraft.world.level.chunk;

import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.Optional;
import java.util.stream.LongStream;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.SingleThreadedRandomSource;
import net.minecraft.world.level.levelgen.WorldgenRandom;

public final class PortingAllocationBenchmark {
    private static final ThreadLocal<int[]> BASELINE_REMAP = ThreadLocal.withInitial(() -> new int[4096]);
    private static volatile Object sink;
    private static volatile long checksum;

    private PortingAllocationBenchmark() {
    }

    public static void main(String[] args) {
        ThreadMXBean bean = (ThreadMXBean)ManagementFactory.getThreadMXBean();
        if (!bean.isThreadAllocatedMemorySupported()) {
            throw new IllegalStateException("Thread allocation measurement is unavailable");
        }
        bean.setThreadAllocatedMemoryEnabled(true);
        for (int size : new int[] {64, 4096}) {
            for (int paletteSize : new int[] {3, 17, 65}) {
                var fixture = PalettePackingParityTestSuite.fixture(size, paletteSize);
                Runnable old = () -> {
                    PalettedContainerRO.PackedData<Object> data;
                    synchronized (fixture.container()) {
                        data = baselinePack(fixture.container(), fixture.strategy());
                    }
                    consume(data);
                };
                Runnable optimized = () -> consume(fixture.container().pack(fixture.strategy()));
                report(bean, "pack/" + size + "/" + paletteSize, old, optimized);
            }
        }
        report(bean, "local-worldgen-rng/100-nextLong",
            () -> consumeRandom(new WorldgenRandom(new LegacyRandomSource(42L))),
            () -> consumeRandom(new WorldgenRandom(new SingleThreadedRandomSource(42L))));
        System.out.println("checksum=" + checksum);
    }

    private static void report(ThreadMXBean bean, String name, Runnable old, Runnable optimized) {
        int warmup = Integer.getInteger("portingBenchmark.warmup", 5000);
        int rounds = Integer.getInteger("portingBenchmark.rounds", 2000);
        int samples = Integer.getInteger("portingBenchmark.samples", 7);
        for (int round = 0; round < warmup; round++) {
            old.run();
            optimized.run();
        }
        double[] oldTime = new double[samples];
        double[] newTime = new double[samples];
        double[] oldBytes = new double[samples];
        double[] newBytes = new double[samples];
        for (int sample = 0; sample < samples; sample++) {
            if ((sample & 1) == 0) {
                measure(bean, old, rounds, oldTime, oldBytes, sample);
                measure(bean, optimized, rounds, newTime, newBytes, sample);
            } else {
                measure(bean, optimized, rounds, newTime, newBytes, sample);
                measure(bean, old, rounds, oldTime, oldBytes, sample);
            }
        }
        Arrays.sort(oldTime);
        Arrays.sort(newTime);
        Arrays.sort(oldBytes);
        Arrays.sort(newBytes);
        System.out.printf(java.util.Locale.ROOT,
            "%s baseline_ns=%.1f optimized_ns=%.1f baseline_B=%.1f optimized_B=%.1f%n",
            name, oldTime[samples / 2], newTime[samples / 2], oldBytes[samples / 2], newBytes[samples / 2]);
    }

    private static void measure(ThreadMXBean bean, Runnable work, int rounds, double[] times, double[] bytes, int index) {
        long thread = Thread.currentThread().threadId();
        long startBytes = bean.getThreadAllocatedBytes(thread);
        long startTime = System.nanoTime();
        for (int round = 0; round < rounds; round++) {
            work.run();
        }
        times[index] = (System.nanoTime() - startTime) / (double)rounds;
        bytes[index] = (bean.getThreadAllocatedBytes(thread) - startBytes) / (double)rounds;
    }

    private static void consume(PalettedContainerRO.PackedData<Object> data) {
        sink = data;
        checksum = data.storage().map(LongStream::sum).orElse(0L) + data.paletteEntries().size();
    }

    private static void consumeRandom(WorldgenRandom random) {
        long sum = 0;
        for (int index = 0; index < 100; index++) {
            sum += random.nextLong();
        }
        sink = random;
        checksum = sum;
    }

    private static PalettedContainerRO.PackedData<Object> baselinePack(PalettedContainer<Object> container, Strategy<Object> strategy) {
        // Pre-port implementation, including its already-cached remap array.
        var storage = container.data.storage();
        var oldPalette = container.data.palette();
        var palette = new HashMapPalette<Object>(storage.getBits());
        int[] contents = new int[storage.getSize()];
        storage.unpack(contents);
        int[] remap = BASELINE_REMAP.get();
        Arrays.fill(remap, 0, oldPalette.getSize(), -1);
        for (int index = 0; index < contents.length; index++) {
            int oldId = contents[index];
            if (remap[oldId] < 0) {
                remap[oldId] = palette.idFor(oldPalette.valueFor(oldId), PaletteResize.noResizeExpected());
            }
            contents[index] = remap[oldId];
        }
        int bits = strategy.getConfigurationForPaletteSize(palette.getSize()).bitsInStorage();
        Optional<LongStream> stream = bits == 0 ? Optional.empty()
            : Optional.of(Arrays.stream(new SimpleBitStorage(bits, strategy.entryCount(), contents).getRaw()));
        return new PalettedContainerRO.PackedData<>(palette.getEntries(), stream, bits);
    }
}
