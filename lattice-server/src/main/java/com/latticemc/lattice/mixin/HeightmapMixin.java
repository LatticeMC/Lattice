package com.latticemc.lattice.mixin;

import com.latticemc.lattice.bridge.HeightmapAccessor;
import com.latticemc.lattice.nativelib.LatticeNative;
import com.latticemc.lattice.nativelib.NativeHeightmap;
import com.latticemc.lattice.nativelib.NativeWorldgenToggle;
import com.latticemc.lattice.nativelib.WorldgenProfiler;
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
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Heightmap.class)
public abstract class HeightmapMixin {
    @Inject(method = "primeHeightmaps", at = @At("HEAD"), cancellable = true)
    private static void lattice$primeHeightmaps(ChunkAccess chunk, Set<Heightmap.Types> types, CallbackInfo ci) {
        long start = WorldgenProfiler.start();
        try {
            if (!NativeWorldgenToggle.heightmapEnabled()) return;
            if (types.isEmpty() || !LatticeNative.isLoaded()) {
                return;
            }

            lattice$primeHeightmapsNative(chunk, types);
            ci.cancel();
        } catch (Exception | LinkageError e) {
            LatticeNative.logFallbackOnce("native_heightmap", e.getMessage());
        } finally {
            WorldgenProfiler.end("heightmap.primeHeightmaps", start);
        }
    }

    private static void lattice$primeHeightmapsNative(ChunkAccess chunk, Set<Heightmap.Types> types) {

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
                buildMask(sectionMasks[i], maskOffset, maskLongsPerSection,
                        palette, paletteSize, ((HeightmapAccessor) heightmaps[i]).lattice$isOpaque());
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
            ((HeightmapAccessor) heightmaps[i]).lattice$setRawData(chunk, heightmapTypes[i], packed);
        }
    }

    private static void buildMask(long[] flatMasks,
                                  int maskOffset,
                                  int maskLongsPerSection,
                                  Palette<BlockState> palette,
                                  int paletteSize,
                                  Predicate<BlockState> isOpaque) {
        for (int i = 0; i < maskLongsPerSection; ++i) {
            flatMasks[maskOffset + i] = 0L;
        }

        for (int paletteIndex = 0; paletteIndex < paletteSize; ++paletteIndex) {
            if (!isOpaque.test(palette.valueFor(paletteIndex))) {
                continue;
            }

            int word = paletteIndex >>> 6;
            if (word < maskLongsPerSection) {
                flatMasks[maskOffset + word] |= 1L << (paletteIndex & 63);
            }
        }
    }
}
