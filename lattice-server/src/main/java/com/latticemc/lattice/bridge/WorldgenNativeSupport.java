package com.latticemc.lattice.bridge;

import com.latticemc.lattice.nativelib.NativeDoublePerlinNoise;
import com.latticemc.lattice.nativelib.NativeHeightmap;
import com.latticemc.lattice.nativelib.NativeInterpolatedNoise;
import com.latticemc.lattice.nativelib.NativeOctavePerlinNoise;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.util.BitStorage;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.Palette;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.synth.BlendedNoise;
import net.minecraft.world.level.levelgen.synth.ImprovedNoise;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import net.minecraft.world.level.levelgen.synth.PerlinNoise;

public final class WorldgenNativeSupport {
    private WorldgenNativeSupport() {}

    public static NativeDoublePerlinNoise createDouble(PerlinNoise first, PerlinNoise second, double valueFactor) {
        PerlinSnapshot firstSnapshot = snapshot(first);
        PerlinSnapshot secondSnapshot = snapshot(second);
        if (firstSnapshot == null || secondSnapshot == null) return null;

        return NativeDoublePerlinNoise.tryCreate(
            firstSnapshot.origins(),
            firstSnapshot.permutations(),
            firstSnapshot.amplitudes(),
            firstSnapshot.lacunarity(),
            firstSnapshot.persistence(),
            secondSnapshot.origins(),
            secondSnapshot.permutations(),
            secondSnapshot.amplitudes(),
            secondSnapshot.lacunarity(),
            secondSnapshot.persistence(),
            valueFactor
        );
    }

    public static NativeInterpolatedNoise createInterpolated(
        PerlinNoise minLimitNoise,
        PerlinNoise maxLimitNoise,
        PerlinNoise mainNoise,
        double xzScale,
        double yScale,
        double xzFactor,
        double yFactor,
        double smearScaleMultiplier
    ) {
        return NativeInterpolatedNoise.tryCreate(
            createOctave(minLimitNoise),
            createOctave(maxLimitNoise),
            createOctave(mainNoise),
            xzScale,
            yScale,
            xzFactor,
            yFactor,
            smearScaleMultiplier
        );
    }

    public static void primeHeightmaps(ChunkAccess chunk, Set<Heightmap.Types> types) {
        LevelChunkSection[] sections = chunk.getSections();
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
            PalettedContainer<BlockState> states = sections[sectionIndex].getStates();
            PalettedContainerAccess.Data<BlockState> data = PalettedContainerAccess.dataOf(states);
            BitStorage storage = data.storage();
            Palette<BlockState> palette = data.palette();
            storages[sectionIndex] = storage.getBits() == 0 ? null : storage.getRaw();
            elementBits[sectionIndex] = storage.getBits();
            maxPaletteSize = Math.max(maxPaletteSize, palette.getSize());
        }

        int maskLongsPerSection = NativeHeightmap.maskLongsPerSection(maxPaletteSize);
        long[][] sectionMasks = new long[types.size()][sectionCount * maskLongsPerSection];
        for (int sectionIndex = 0; sectionIndex < sectionCount; ++sectionIndex) {
            PalettedContainer<BlockState> states = sections[sectionIndex].getStates();
            Palette<BlockState> palette = PalettedContainerAccess.dataOf(states).palette();
            int paletteSize = palette.getSize();
            for (int i = 0; i < heightmaps.length; ++i) {
                int maskOffset = sectionIndex * maskLongsPerSection;
                buildMask(
                    sectionMasks[i],
                    maskOffset,
                    maskLongsPerSection,
                    palette,
                    paletteSize,
                    ((HeightmapAccessor)heightmaps[i]).lattice$isOpaque()
                );
            }
        }

        int[] highestTaken = new int[NativeHeightmap.COLUMN_COUNT];
        int[] firstAvailable = new int[NativeHeightmap.COLUMN_COUNT];
        int minY = chunk.getMinY();
        int sectionBaseY = chunk.getSectionYFromSectionIndex(0) << 4;
        for (int i = 0; i < heightmaps.length; ++i) {
            NativeHeightmap.populateHeightmap(
                storages,
                elementBits,
                sectionMasks[i],
                maskLongsPerSection,
                sectionCount,
                sectionBaseY,
                minY - 1,
                highestTaken
            );
            for (int column = 0; column < highestTaken.length; ++column) {
                firstAvailable[column] = highestTaken[column] + 1 - minY;
            }
            int bits = net.minecraft.util.Mth.ceillog2(chunk.getHeight() + 1);
            long[] packed = new SimpleBitStorage(bits, NativeHeightmap.COLUMN_COUNT, firstAvailable).getRaw();
            ((HeightmapAccessor)heightmaps[i]).lattice$setRawData(chunk, heightmapTypes[i], packed);
        }
    }

    private static void buildMask(
        long[] flatMasks,
        int maskOffset,
        int maskLongsPerSection,
        Palette<BlockState> palette,
        int paletteSize,
        Predicate<BlockState> isOpaque
    ) {
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

    private static PerlinSnapshot snapshot(PerlinNoise noise) {
        PerlinNoiseAccessor accessor = (PerlinNoiseAccessor)(Object)noise;
        ImprovedNoise[] levels = accessor.lattice$getNoiseLevels();
        double[] amplitudes = accessor.lattice$getAmplitudesArray();
        int count = amplitudes.length;
        if (count <= 0 || levels.length != count) return null;

        double[] origins = new double[count * 3];
        byte[] permutations = new byte[count * 256];
        for (int i = 0; i < count; ++i) {
            ImprovedNoise octave = levels[i];
            if (octave == null) continue;
            origins[i * 3] = octave.xo;
            origins[i * 3 + 1] = octave.yo;
            origins[i * 3 + 2] = octave.zo;
            byte[] permutation = ((ImprovedNoiseAccessor)(Object)octave).lattice$getPermutation();
            System.arraycopy(permutation, 0, permutations, i * 256, 256);
        }

        return new PerlinSnapshot(
            origins,
            permutations,
            amplitudes,
            accessor.lattice$getLowestFreqInputFactor(),
            accessor.lattice$getLowestFreqValueFactor()
        );
    }

    private static NativeOctavePerlinNoise createOctave(PerlinNoise noise) {
        PerlinNoiseAccessor accessor = (PerlinNoiseAccessor)(Object)noise;
        ImprovedNoise[] levels = accessor.lattice$getNoiseLevels();
        double[] amplitudes = accessor.lattice$getAmplitudesArray();
        int count = amplitudes.length;
        if (count <= 0 || levels.length != count) return null;

        double[] origins = new double[count * 3];
        byte[] permutations = new byte[count * 256];
        for (int i = 0; i < count; ++i) {
            ImprovedNoise octave = levels[i];
            if (octave == null) continue;
            origins[i * 3] = octave.xo;
            origins[i * 3 + 1] = octave.yo;
            origins[i * 3 + 2] = octave.zo;
            byte[] permutation = ((ImprovedNoiseAccessor)(Object)octave).lattice$getPermutation();
            System.arraycopy(permutation, 0, permutations, i * 256, 256);
        }
        return NativeOctavePerlinNoise.tryCreate(
            origins,
            permutations,
            amplitudes,
            accessor.lattice$getLowestFreqInputFactor(),
            accessor.lattice$getLowestFreqValueFactor()
        );
    }
}
