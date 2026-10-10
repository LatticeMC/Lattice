package net.minecraft.world.level.levelgen;

import com.latticemc.lattice.nativelib.NativeDensityFunction;
import java.util.Arrays;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.blending.Blender;

/** 独立 JVM 对照同一 Overworld 完整噪声填充；不启动服务器或写世界。 */
public final class MathFlatteningWorldgenBenchmark {
    public static void main(String[] args) {
        System.setProperty("lattice.disable", "true");
        System.setProperty("lattice.nativeDensityFunction", "false");
        System.setProperty("lattice.nativeScalarPerlin", "false");
        System.setProperty("lattice.noiseChunkSoaInterpolation", "true");
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        for (Class<?> type : new Class<?>[]{NoiseChunk.class, net.minecraft.world.level.levelgen.synth.PerlinNoise.class,
            net.minecraft.world.level.levelgen.synth.BlendedNoise.class}) {
            System.out.println("source " + type.getName() + "=" + type.getProtectionDomain().getCodeSource().getLocation());
        }
        var registries = VanillaRegistries.createLookup();
        var settings = registries.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(NoiseGeneratorSettings.OVERWORLD).value();
        var random = RandomState.create(registries, NoiseGeneratorSettings.OVERWORLD, 0x4c415454494345L);
        var lava = new Aquifer.FluidStatus(-54, Blocks.LAVA.defaultBlockState());
        var water = new Aquifer.FluidStatus(settings.seaLevel(), settings.defaultFluid());
        Aquifer.FluidPicker picker = (x, y, z) -> y < Math.min(-54, settings.seaLevel()) ? lava : water;
        int chunks = Integer.getInteger("lattice.mathBenchChunks", 8);
        int warmup = Integer.getInteger("lattice.mathBenchWarmup", 4);
        int samples = Integer.getInteger("lattice.mathBenchSamples", 7);
        long[] times = new long[samples];
        long expected = 0;
        for (int sample = -warmup; sample < samples; sample++) {
            long start = System.nanoTime(), checksum = 0;
            for (int i = 0; i < chunks; i++) checksum = checksum * 31 + fill(random, settings, picker, (25565 + i * 37) << 4, (-25565 + i * 53) << 4);
            long nanos = System.nanoTime() - start;
            if (sample == -warmup) expected = checksum;
            if (checksum != expected) throw new AssertionError("worldgen checksum changed");
            if (sample >= 0) { times[sample] = nanos; System.out.println("sample=" + sample + " ns=" + nanos + " checksum=" + checksum); }
        }
        Arrays.sort(times);
        System.out.println("nativeDensityFunction=false nativeScalarPerlin=false noiseChunkSoaInterpolation=true chunks=" + chunks
            + " median_ns=" + times[times.length / 2] + " checksum=" + expected);
        System.out.println(NativeDensityFunction.status());
    }

    private static long fill(RandomState random, NoiseGeneratorSettings settings, Aquifer.FluidPicker picker, int minX, int minZ) {
        var noise = settings.noiseSettings();
        int width = noise.getCellWidth(), height = noise.getCellHeight(), countXZ = 16 / width;
        NoiseChunk chunk = new NoiseChunk(countXZ, random, minX, minZ, noise, Beardifier.EMPTY, settings, picker, Blender.empty());
        long checksum = 0;
        chunk.initializeForFirstCellX();
        try {
            for (int cellX = 0; cellX < countXZ; cellX++) {
                chunk.advanceCellX(cellX);
                for (int cellZ = 0; cellZ < countXZ; cellZ++) {
                    for (int cellY = noise.height() / height - 1; cellY >= 0; cellY--) {
                        chunk.selectCellYZ(cellY, cellZ);
                        for (int y = height - 1; y >= 0; y--) {
                            chunk.updateForY(noise.minY() + cellY * height + y, (double)y / height);
                            for (int x = 0; x < width; x++) {
                                chunk.updateForX(minX + cellX * width + x, (double)x / width);
                                for (int z = 0; z < width; z++) {
                                    chunk.updateForZ(minZ + cellZ * width + z, (double)z / width);
                                    var state = chunk.getInterpolatedState();
                                    checksum = checksum * 31 + Block.getId(state == null ? settings.defaultBlock() : state);
                                }
                            }
                        }
                    }
                }
                chunk.swapSlices();
            }
        } finally { chunk.stopInterpolation(); }
        return checksum;
    }
}
