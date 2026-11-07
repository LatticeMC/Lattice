package com.latticemc.lattice.bridge;

import com.latticemc.lattice.nativelib.LatticeNative;
import com.latticemc.lattice.nativelib.NativeHeightmap;
import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.Palette;
import net.minecraft.world.level.levelgen.Heightmap;

public final class WorldgenHeightmapBenchmark {
    private static volatile long sink;

    public static void main(String[] args) {
        WorldgenHeightmapFixture.bootstrap();
        LatticeNative.ensureLoaded();
        if (!LatticeNative.isLoaded()) throw new IllegalStateException(LatticeNative.failureReason());
        if (LatticeNative.VERIFY) throw new IllegalStateException("Disable VERIFY for allocation measurement");
        int warmup = Integer.getInteger("heightmapBenchmark.warmup", 32);
        int samples = Integer.getInteger("heightmapBenchmark.samples", 21);
        int rounds = Integer.getInteger("heightmapBenchmark.rounds", 128);
        if (warmup < 0 || samples < 1 || rounds < 1) throw new IllegalArgumentException("Invalid benchmark counts");
        ThreadMXBean bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        if (!bean.isThreadAllocatedMemorySupported()) throw new IllegalStateException("Allocation counter unavailable");
        bean.setThreadAllocatedMemoryEnabled(true);
        System.out.printf("java=%s cpu=%s warmup=%d samples=%d rounds=%d%n",
                System.getProperty("java.version"), LatticeNative.cpuSummary(), warmup, samples, rounds);
        System.out.println("kind,sections,types,pattern,path,sample,ns-per-chunk,java-bytes-per-chunk");
        for (int sections : new int[] {4, 24, 64}) {
            for (var pattern : WorldgenHeightmapFixture.Pattern.values()) {
                for (var types : java.util.List.of(EnumSet.of(Heightmap.Types.WORLD_SURFACE),
                        EnumSet.allOf(Heightmap.Types.class))) {
                    var fixture = new WorldgenHeightmapFixture(-64, sections, pattern, types);
                    WorldgenNativeSupport.primeHeightmaps(fixture.chunk, fixture.types);
                    fixture.assertParity();
                    fixture.resetHeightmaps();
                    legacyPrimeHeightmaps(fixture.chunk, fixture.types);
                    fixture.assertParity();
                    double[][] nanos = new double[2][samples];
                    double[][] bytes = new double[2][samples];
                    for (int sample = -warmup; sample < samples; sample++) {
                        for (int order = 0; order < 2; order++) {
                            int arm = (sample + warmup + order) & 1;
                            long allocationStart = bean.getThreadAllocatedBytes(Thread.currentThread().threadId());
                            long start = System.nanoTime();
                            long checksum = 0;
                            for (int round = 0; round < rounds; round++) {
                                fixture.resetHeightmaps();
                                if (arm == 0) WorldgenNativeSupport.primeHeightmaps(fixture.chunk, fixture.types);
                                else legacyPrimeHeightmaps(fixture.chunk, fixture.types);
                                checksum += fixture.checksum();
                            }
                            long elapsed = System.nanoTime() - start;
                            long allocated = bean.getThreadAllocatedBytes(Thread.currentThread().threadId()) - allocationStart;
                            sink = checksum;
                            fixture.assertParity();
                            if (sample >= 0) {
                                nanos[arm][sample] = (double) elapsed / rounds;
                                bytes[arm][sample] = (double) allocated / rounds;
                                System.out.printf(Locale.ROOT, "sample,%d,%d,%s,%s,%d,%.3f,%.3f%n",
                                        sections, types.size(), pattern, arm == 0 ? "current-native" : "legacy-native",
                                        sample, nanos[arm][sample], bytes[arm][sample]);
                            }
                        }
                    }
                    for (int arm = 0; arm < 2; arm++) {
                        Arrays.sort(nanos[arm]);
                        Arrays.sort(bytes[arm]);
                        System.out.printf(Locale.ROOT, "summary,%d,%d,%s,%s,p50=%.3f,p95=%.3f,bytes=%.3f%n",
                                sections, types.size(), pattern, arm == 0 ? "current-native" : "legacy-native",
                                nanos[arm][samples / 2], nanos[arm][Math.min(samples - 1, (int) Math.ceil(samples * .95) - 1)],
                                bytes[arm][samples / 2]);
                    }
                }
            }
        }
    }

    // Frozen pre-optimization priming body from 7762803; both arms use the same JNI.
    static void legacyPrimeHeightmaps(ChunkAccess chunk, Set<Heightmap.Types> types) {
        var sections = chunk.getSections();
        int sectionCount = sections.length;
        long[][] storages = new long[sectionCount][];
        int[] elementBits = new int[sectionCount];
        Heightmap[] heightmaps = new Heightmap[types.size()];
        Heightmap.Types[] heightmapTypes = new Heightmap.Types[types.size()];
        int typeIndex = 0;
        int maxPaletteSize = 1;
        for (Heightmap.Types type : types) {
            heightmapTypes[typeIndex] = type;
            heightmaps[typeIndex++] = chunk.getOrCreateHeightmapUnprimed(type);
        }
        for (int sectionIndex = 0; sectionIndex < sectionCount; ++sectionIndex) {
            var data = PalettedContainerAccess.dataOf(sections[sectionIndex].getStates());
            storages[sectionIndex] = data.storage().getBits() == 0 ? null : data.storage().getRaw();
            elementBits[sectionIndex] = data.storage().getBits();
            maxPaletteSize = Math.max(maxPaletteSize, data.palette().getSize());
        }
        int maskLongsPerSection = NativeHeightmap.maskLongsPerSection(maxPaletteSize);
        long[][] sectionMasks = new long[types.size()][sectionCount * maskLongsPerSection];
        for (int sectionIndex = 0; sectionIndex < sectionCount; ++sectionIndex) {
            var palette = PalettedContainerAccess.dataOf(sections[sectionIndex].getStates()).palette();
            int paletteSize = palette.getSize();
            for (int i = 0; i < heightmaps.length; ++i) {
                int offset = sectionIndex * maskLongsPerSection;
                legacyBuildMask(sectionMasks[i], offset, maskLongsPerSection, palette, paletteSize,
                        ((HeightmapAccessor) heightmaps[i]).lattice$isOpaque());
            }
        }
        int[] highestTaken = new int[NativeHeightmap.COLUMN_COUNT];
        int[] firstAvailable = new int[NativeHeightmap.COLUMN_COUNT];
        int minY = chunk.getMinY();
        int sectionBaseY = chunk.getSectionYFromSectionIndex(0) << 4;
        for (int i = 0; i < heightmaps.length; ++i) {
            NativeHeightmap.populateHeightmap(storages, elementBits, sectionMasks[i], maskLongsPerSection,
                    sectionCount, sectionBaseY, minY - 1, highestTaken);
            for (int column = 0; column < highestTaken.length; ++column) {
                firstAvailable[column] = highestTaken[column] + 1 - minY;
            }
            int bits = net.minecraft.util.Mth.ceillog2(chunk.getHeight() + 1);
            long[] packed = new SimpleBitStorage(bits, NativeHeightmap.COLUMN_COUNT, firstAvailable).getRaw();
            ((HeightmapAccessor) heightmaps[i]).lattice$setRawData(chunk, heightmapTypes[i], packed);
        }
    }

    private static void legacyBuildMask(long[] flatMasks, int maskOffset, int maskLongsPerSection,
            Palette<BlockState> palette, int paletteSize, Predicate<BlockState> isOpaque) {
        for (int i = 0; i < maskLongsPerSection; ++i) {
            flatMasks[maskOffset + i] = 0L;
        }
        for (int paletteIndex = 0; paletteIndex < paletteSize; ++paletteIndex) {
            if (!isOpaque.test(palette.valueFor(paletteIndex))) continue;
            int word = paletteIndex >>> 6;
            if (word < maskLongsPerSection) {
                flatMasks[maskOffset + word] |= 1L << (paletteIndex & 63);
            }
        }
    }
}
